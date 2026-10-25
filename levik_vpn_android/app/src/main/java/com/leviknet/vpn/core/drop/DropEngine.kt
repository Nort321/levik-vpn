package com.leviknet.vpn.core.drop

import java.io.File
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** The website's LevikDrop requests, docs/levik-drop.md "Website API". */
interface DropApi {
    suspend fun presence(key: String)

    suspend fun clearPresence()

    suspend fun devices(): JsonObject

    suspend fun transfers(): JsonObject

    suspend fun create(receiverDeviceId: String, fileCount: Int, totalBytes: Long): JsonObject

    suspend fun update(id: String, action: String, reason: String? = null): JsonObject
}

/** Where received files can go. */
interface DropFolders {
    /** Downloads/Levik through MediaStore; null where the system has none (Android 9 and older). */
    val default: String?

    fun open(folder: String): DropTargets
}

/**
 * LevikDrop in the Android app, docs/levik-drop.md: presence while the app is
 * online, the website's transfers, one relay link per open transfer, the
 * user's answers. State changes on one thread at a time ([dispatcher]).
 */
class DropEngine(
    private val api: DropApi,
    secrets: DropSecrets,
    private val loadIdentity: () -> DropIdentity,
    stateFile: File,
    private val sources: DropSources,
    private val folders: DropFolders,
    /** The website's error code of a failed request, if it gave one. */
    private val errorCode: (Throwable) -> String?,
    private val log: (String) -> Unit,
    private val joiner: RelayJoiner = DropRelay.tls,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> log("LevikDrop: ${failure.javaClass.simpleName}") },
    )
    private val store = DropStore(stateFile, scope)
    private val pins = DropPins(secrets)
    private var identity: DropIdentity? = null
    private var loaded: Job? = null
    private var storeLoaded = false
    private var running = false
    private var epoch = 0L
    private var online = false
    private var presenceJob: Job? = null
    private var presenceSent = false
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var refreshTimer: Job? = null
    private var creating = 0
    private var web = linkedMapOf<String, WebTransfer>()
    private var webLoaded = false
    private var devices = emptyList<DropDevice>()
    private var available = false
    private var loadedAt: Long? = null
    private var quota: DropQuota? = null
    private var maxFiles = DropWire.MAX_FILES
    private var maxBytes = DropWire.MAX_TOTAL
    private var error: String? = null
    private val links = mutableMapOf<String, RelayLink>()
    private val live = mutableMapOf<String, Live>()
    private val prompts = mutableMapOf<String, PendingPrompt>()
    private val targets = mutableMapOf<String, TargetFiles>()
    private val protocolFailures = mutableMapOf<String, Int>()
    private val resuming = mutableSetOf<String>()
    private val announced = mutableSetOf<String>()
    private var changedJob: Job? = null
    private var savedAt = 0L

    private val mutableSnapshot = MutableStateFlow(DropSnapshot())
    val snapshot: StateFlow<DropSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableNotices = MutableSharedFlow<DropNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<DropNotice> = mutableNotices.asSharedFlow()

    private class WebTransfer(
        val id: String,
        val sender: Boolean,
        val peerDeviceId: String,
        val peerName: String,
        val peerPlatform: String?,
        val state: DropState,
        val reason: String?,
        val fileCount: Int,
        val totalBytes: Long,
        val createdAtMs: Long,
        val finishedAtMs: Long?,
    )

    private class Live(
        var done: Long = 0,
        var samples: List<Pair<Long, Long>> = emptyList(),
        var path: SessionPath? = null,
        var link: LinkStatus? = null,
        var verificationCode: String? = null,
    )

    private enum class Answer { YES, NO, SUPERSEDED }

    private class PendingPrompt(val prompt: DropPrompt, val result: CompletableDeferred<Answer>)

    private suspend fun load() {
        if (identity != null) return
        val job = loaded?.takeIf(Job::isActive) ?: scope.launch {
            if (!storeLoaded) {
                store.load()
                storeLoaded = true
            }
            withContext(Dispatchers.IO) {
                try {
                    pins.load()
                    identity = loadIdentity()
                } catch (failure: Exception) {
                    // The key could not be made (Keystore busy): the next call tries again.
                    log("LevikDrop key: ${failure.javaClass.simpleName}")
                }
            }
        }.also { loaded = it }
        job.join()
        if (identity == null) throw IllegalStateException("LevikDrop key unavailable")
    }

    /** Signed in: links for open transfers the user started or accepted. */
    fun start() {
        scope.launch {
            if (running) return@launch
            running = true
            val generation = epoch
            try {
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == epoch) running = false
                log("LevikDrop: ${error.javaClass.simpleName}")
                return@launch
            }
            if (!current(generation)) return@launch
            updatePresence()
            refreshNowInternal()
            changed()
        }
    }

    /**
     * Online while the app is on the screen or the VPN runs: the phone can
     * receive. Offline, transfers the user started or accepted go on; offers
     * wait for the app.
     */
    fun setOnline(value: Boolean) {
        scope.launch {
            if (online == value) return@launch
            online = value
            updatePresence()
            if (value) {
                refreshNowInternal()
            } else {
                for (id in links.keys.toList()) if (!held(id)) stopLink(id)
            }
            changed()
        }
    }

    /** Before the session token goes: links stop and the website hears this app is gone. */
    suspend fun beforeSignOut() = withContext(dispatcher) { stopInternal() }

    /** Sign-out ends this app's transfers on the website; partial entries go here. */
    suspend fun signedOut() = withContext(dispatcher) {
        stopInternal()
        for (stored in store.transfers.toList()) {
            if (stored is StoredReceiver && !stored.finished) removeStoredPartials(stored)
            store.remove(stored.id)
        }
        store.flush()
        web = linkedMapOf()
        webLoaded = false
        devices = emptyList()
        available = false
        loadedAt = null
        quota = null
        protocolFailures.clear()
        resuming.clear()
        announced.clear()
        live.clear()
        targets.clear()
        error = null
        publish()
    }

    private suspend fun stopInternal() {
        epoch++
        running = false
        refreshJob?.cancel()
        refreshJob = null
        refreshAgain = false
        changedJob?.cancel()
        changedJob = null
        refreshTimer?.cancel()
        refreshTimer = null
        presenceJob?.cancel()
        presenceJob = null
        if (presenceSent) {
            presenceSent = false
            try {
                api.clearPresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The website forgets the presence on its own.
            }
        }
        for (id in links.keys.toList()) stopLink(id)
        prompts.values.forEach { it.result.complete(Answer.SUPERSEDED) }
        prompts.clear()
        targets.values.forEach { it.closeWriting() }
        store.flush()
    }

    /** The `drop` live event, a new stream, or the screen showing the list. */
    fun refresh() {
        scope.launch { refreshNowInternal() }
    }

    /** Waits for the refresh in flight (the refresh button). */
    suspend fun refreshNow() {
        val job = withContext(dispatcher) { refreshNowInternal() }
        job?.join()
    }

    private fun refreshNowInternal(): Job? {
        if (!running) return null
        refreshJob?.takeIf(Job::isActive)?.let {
            refreshAgain = true
            return it
        }
        val generation = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                load()
                requireCurrent(generation)
                fetch(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(generation)) error = errorCode(failure) ?: NETWORK
            } finally {
                if (current(generation)) {
                    refreshJob = null
                    changed()
                    if (refreshAgain) {
                        refreshAgain = false
                        refreshNowInternal()
                    } else {
                        scheduleRefresh()
                    }
                }
            }
        }
        refreshJob = job
        job.start()
        return job
    }

    /** null: Downloads/Levik (on Android 9 and older nothing until the user picks a folder). */
    suspend fun setFolder(folder: String?): Unit = withContext(dispatcher) {
        runCatching { load() }
        store.folder = folder
        publish()
    }

    // Sending ------------------------------------------------------------------------------

    suspend fun send(deviceId: String, refs: List<String>): Unit = withContext(dispatcher) {
        if (!running) throw DropUserError(DropUserError.Kind.SIGNED_OUT)
        val generation = epoch
        load()
        requireCurrent(generation)
        if (web.values.any { it.state.open }) throw DropUserError(DropUserError.Kind.BUSY)
        val unique = refs.distinct()
        if (unique.isEmpty()) throw DropUserError(DropUserError.Kind.NOTHING)
        if (unique.size > maxFiles) throw DropUserError(DropUserError.Kind.TOO_MANY_FILES, maxFiles.toString())
        val files = sources.describe(unique)
        requireCurrent(generation)
        val total = files.sumOf(SourceFile::size)
        if (total > maxBytes) throw DropUserError(DropUserError.Kind.TOO_LARGE)
        creating++
        try {
            val response = api.create(deviceId, files.size, total)
            requireCurrent(generation)
            val transfer = parseWebTransfer(response["transfer"]) ?: throw DropUserError(DropUserError.Kind.BAD_RESPONSE)
            store.put(StoredSender(transfer.id, transfer.peerDeviceId, files))
            web[transfer.id] = transfer
            ensureLink(transfer.id)
        } finally {
            creating--
            if (current(generation)) changed()
        }
    }

    // The user's answers and buttons -------------------------------------------------------

    suspend fun answer(id: String, answer: DropAnswer, code: String?): Unit = withContext(dispatcher) {
        val pending = prompts[id]
        val transfer = web[id]
        if (pending == null) {
            // The other app is not connected: an offer can still be declined.
            if (answer == DropAnswer.DECLINE && transfer?.sender == false && transfer.state == DropState.OFFERED) {
                decline(id)
                return@withContext
            }
            throw DropUserError(DropUserError.Kind.STALE)
        }
        val prompt = pending.prompt
        val yes = answer == DropAnswer.ACCEPT || answer == DropAnswer.CONFIRM
        if (prompt.code != null && yes && code != prompt.code) throw DropUserError(DropUserError.Kind.STALE)
        if ((prompt.kind == DropPrompt.Kind.OFFER) != (answer == DropAnswer.ACCEPT || answer == DropAnswer.DECLINE)) {
            throw DropUserError(DropUserError.Kind.STALE)
        }
        if (answer == DropAnswer.ACCEPT && store.folder == null && folders.default == null) {
            throw DropUserError(DropUserError.Kind.NO_FOLDER)
        }
        prompts.remove(id)
        pending.result.complete(if (yes) Answer.YES else Answer.NO)
        changed()
    }

    suspend fun control(id: String, action: DropAction): Unit = withContext(dispatcher) {
        web[id] ?: throw DropUserError(DropUserError.Kind.STALE)
        val session = links[id]?.current
        when (action) {
            DropAction.PAUSE -> {
                if (session != null && !session.finished) {
                    session.pause()
                    return@withContext
                }
                stopLink(id)
                step(id, "pause", "user")
            }
            DropAction.RESUME -> {
                resuming += id
                ensureLink(id)
                changed()
            }
            DropAction.CANCEL -> {
                if (session != null && !session.finished) {
                    session.cancel()
                    return@withContext
                }
                stopLink(id)
                discard(id)
                step(id, "cancel")
            }
        }
    }

    // Website ------------------------------------------------------------------------------

    private suspend fun fetch(generation: Long): Unit = coroutineScope {
        val listCall = async { api.transfers() }
        val devicesCall = async {
            try {
                api.devices()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = errorCode(failure)
                if (current(generation) && (code == "drop_unavailable" || code?.startsWith("http_4") == true)) available = false
                null
            }
        }
        val list = try {
            listCall.await()
        } finally {
            devicesCall.await()
        }
        requireCurrent(generation)
        devicesCall.await()?.let { response ->
            available = (response["available"] as? JsonPrimitive)?.booleanOrNull == true
            devices = parseDevices(response["devices"])
            (response["limits"] as? JsonObject)?.let { limits ->
                val files = number(limits["maxFiles"])
                val bytes = number(limits["maxBytes"])
                if (files != null && bytes != null) {
                    maxFiles = minOf(files, DropWire.MAX_FILES.toLong()).toInt()
                    maxBytes = minOf(bytes, DropWire.MAX_TOTAL)
                }
            }
        }
        (list["transfers"] as? JsonArray)?.let { items ->
            web = items.mapNotNull(::parseWebTransfer).associateByTo(linkedMapOf(), WebTransfer::id)
            webLoaded = true
        }
        (list["quota"] as? JsonObject)?.let { value ->
            val limit = number(value["limitBytes"])
            val used = number(value["usedBytes"])
            val resets = time(value["resetsAt"])
            if (limit != null && used != null && resets != null) quota = DropQuota(limit, used, resets)
        }
        loadedAt = clock()
        error = null
        reconcile()
    }

    private suspend fun step(id: String, action: String, reason: String? = null) {
        val generation = epoch
        requireCurrent(generation)
        try {
            val response = api.update(id, action, reason)
            requireCurrent(generation)
            parseWebTransfer(response["transfer"])?.let { web[id] = it }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val code = errorCode(failure)
            // Already over on the website: the list says how.
            if (!current(generation) || code != "transfer_ended" && code != "transfer_not_found" && code != "invalid_action") throw failure
            refreshNowInternal()
        } finally {
            if (current(generation)) changed()
        }
    }

    private fun quietStep(id: String, action: String, reason: String? = null) {
        val generation = epoch
        scope.launch {
            if (!current(generation)) return@launch
            try {
                step(id, action, reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("LevikDrop: $action failed (${errorCode(failure) ?: failure.javaClass.simpleName})")
            }
        }
    }

    private suspend fun reconcile() {
        for (transfer in web.values.toList()) {
            val stored = store.find(transfer.id)
            if (transfer.state.open) {
                if (transfer.sender) {
                    if (stored == null) {
                        // The files of this offer are not known here (another installation): it cannot go on.
                        if (creating == 0) quietStep(transfer.id, "fail", "source_unreadable")
                        continue
                    }
                } else if (stored == null) {
                    store.put(
                        StoredReceiver(
                            id = transfer.id,
                            peerDeviceId = transfer.peerDeviceId,
                            decision = if (transfer.state == DropState.OFFERED) null else ACCEPTED,
                        ),
                    )
                } else if (stored is StoredReceiver && stored.decision == DECLINED) {
                    quietStep(transfer.id, "decline")
                    continue
                }
                if (transfer.state == DropState.PAUSED && transfer.id !in resuming) stopLink(transfer.id) else ensureLink(transfer.id)
                continue
            }
            stopLink(transfer.id)
            resuming -= transfer.id
            if (stored != null && !stored.finished) {
                if (transfer.state != DropState.DONE) {
                    discard(transfer.id)
                } else if (stored is StoredReceiver && announced.add(transfer.id)) {
                    mutableNotices.tryEmit(DropNotice.Received(transfer.id, transfer.peerName, transfer.fileCount, stored.folder))
                }
                stored.finished = true
                store.save()
                targets.remove(transfer.id)
            }
        }
        if (!webLoaded) return
        for (stored in store.transfers.toList()) {
            if (web.containsKey(stored.id)) continue
            if (stored is StoredReceiver && !stored.finished) removeStoredPartials(stored)
            store.remove(stored.id)
        }
    }

    private fun scheduleRefresh() {
        refreshTimer?.cancel()
        refreshTimer = null
        if (!running || web.values.none { it.state.open }) return
        refreshTimer = scope.launch {
            delay(OPEN_REFRESH_MS)
            refreshNowInternal()
        }
    }

    private fun updatePresence() {
        val wanted = running && online
        if (wanted && presenceJob == null) {
            presenceJob = scope.launch { presenceLoop() }
        } else if (!wanted && presenceJob != null) {
            presenceJob?.cancel()
            presenceJob = null
            if (presenceSent) {
                presenceSent = false
                scope.launch { runCatching { api.clearPresence() } }
            }
        }
    }

    private suspend fun presenceLoop() {
        val generation = epoch
        val key = identity?.spki ?: return
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        while (current(generation) && online) {
            val wait = try {
                api.presence(encoded)
                requireCurrent(generation)
                presenceSent = true
                PRESENCE_MS
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = errorCode(failure)
                if (code != "drop_unavailable" && code?.startsWith("http_404") != true) log("LevikDrop presence: ${code ?: failure.javaClass.simpleName}")
                if (code == "drop_unavailable" || code?.startsWith("http_4") == true) PRESENCE_RETRY_MS else PRESENCE_MS
            }
            delay(wait)
        }
    }

    // Links --------------------------------------------------------------------------------

    /** The user started (sender) or accepted (receiver) it. */
    private fun held(id: String): Boolean = when (val stored = store.find(id)) {
        is StoredSender -> true
        is StoredReceiver -> stored.decision == ACCEPTED
        null -> false
    }

    private fun ensureLink(id: String) {
        if (!running || links.containsKey(id)) return
        if (!online && !held(id)) return
        val link = RelayLink(hostFor(id), scope, joiner)
        links[id] = link
        link.start()
    }

    private fun stopLink(id: String) {
        val link = links.remove(id) ?: return
        link.stop()
        supersede(id)
        live[id]?.let {
            it.path = null
            it.link = null
            it.verificationCode = null
        }
    }

    private fun supersede(id: String) {
        prompts.remove(id)?.result?.complete(Answer.SUPERSEDED)
    }

    private fun liveFor(id: String): Live = live.getOrPut(id) { Live() }

    private fun hostFor(id: String): LinkHost {
        val generation = epoch
        return object : LinkHost {
            override suspend fun connect(): RelayConnection {
                requireCurrent(generation)
                val response = api.update(id, "connect")
                requireCurrent(generation)
                resuming -= id
                parseWebTransfer(response["transfer"])?.let { web[id] = it }
                val connection = response["connection"] as? JsonObject ?: throw IllegalStateException("no relay connection")
                val token = (connection["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 2_048 }
                val endpoints = DropRelay.parseEndpoints(connection["endpoints"])
                if (token == null || endpoints.isEmpty()) throw IllegalStateException("no relay connection")
                return RelayConnection(token, endpoints)
            }

            override fun work(allowLan: Boolean): SessionWork {
                requireCurrent(generation)
                return workFor(id, allowLan)
            }

            override fun status(status: LinkStatus) {
                if (!current(generation)) return
                val state = liveFor(id)
                state.link = status
                if (status != LinkStatus.SESSION) {
                    state.path = null
                    state.verificationCode = null
                    supersede(id)
                }
                changed()
            }

            override fun outcome(outcome: SessionOutcome) {
                scope.launch { if (current(generation)) onOutcome(id, outcome) }
            }

            override fun stopped(reason: String, error: Throwable?) {
                scope.launch { if (current(generation)) onStopped(id, reason, error) }
            }
        }
    }

    private fun workFor(id: String, allowLan: Boolean): SessionWork {
        val generation = epoch
        val stored = store.find(id)
        val transfer = web[id]
        val own = identity
        if (stored == null || transfer == null || own == null) throw IllegalStateException("unknown transfer")
        var peerKey: ByteArray? = null
        val trust = { key: ByteArray -> pins.trust(stored.peerDeviceId, key) }
        val confirm: suspend (String, Trust) -> Boolean = { code, level ->
            val key = peerKey
            if (key == null) {
                false
            } else {
                val answer = ask(id, DropPrompt(DropPrompt.Kind.CONFIRM, code, level))
                if (answer == Answer.YES) withContext(Dispatchers.IO) { pins.pin(stored.peerDeviceId, key) }
                answer == Answer.YES
            }
        }
        val events = SessionEvents(
            keyed = { code, level, key ->
                if (current(generation)) {
                    peerKey = key
                    liveFor(id).verificationCode = code.takeIf { stored !is StoredSender && level != Trust.TRUSTED }
                    protocolFailures.remove(id)
                    changed()
                }
            },
            path = { path ->
                if (current(generation)) {
                    liveFor(id).path = path
                    changed()
                }
            },
            progress = { done -> if (current(generation)) progress(id, done) },
        )
        if (stored is StoredSender) {
            return SenderWork(id, own, PLATFORM, transfer.peerPlatform, trust, allowLan, events, confirm, stored.files, sources)
        }
        stored as StoredReceiver
        return ReceiverWork(
            id, own, PLATFORM, transfer.peerPlatform, trust, allowLan, events, confirm,
            manifest = stored.manifest,
            targets = targetFilesFor(stored),
            decide = decide@{ manifest, code, level ->
                stored.manifest = manifest
                store.save()
                changed()
                if (stored.decision != ACCEPTED) {
                    val key = peerKey ?: return@decide null
                    announceOffer(id, manifest)
                    val answer = ask(id, DropPrompt(DropPrompt.Kind.OFFER, code.takeIf { level != Trust.TRUSTED }, level))
                    if (answer == Answer.SUPERSEDED) return@decide null
                    if (answer == Answer.NO) {
                        decline(id)
                        return@decide null
                    }
                    if (level != Trust.TRUSTED) withContext(Dispatchers.IO) { pins.pin(stored.peerDeviceId, key) }
                }
                val files = prepareTargets(stored, manifest)
                if (stored.decision != ACCEPTED) {
                    step(id, "accept")
                    stored.decision = ACCEPTED
                    store.save()
                }
                files
            },
        )
    }

    private fun targetFilesFor(stored: StoredReceiver): TargetFiles? {
        val planned = stored.targets ?: return null
        val folder = stored.folder ?: return null
        return targets.getOrPut(stored.id) {
            // A file in place is written down at once: a restart must not offer it again.
            TargetFiles(folders.open(folder), planned) { store.save() }
        }
    }

    private suspend fun prepareTargets(stored: StoredReceiver, manifest: List<ManifestFile>): TargetFiles {
        val folder = store.folder ?: folders.default ?: throw TransferFailure("storage_error", "no folder")
        val destination = folders.open(folder)
        val needed = manifest.sumOf(ManifestFile::size)
        val free = withContext(Dispatchers.IO) { runCatching { destination.freeBytes() }.getOrNull() }
        if (free != null && free < needed + FREE_MARGIN) throw TransferFailure("storage_full")
        stored.folder = folder
        stored.targets = TargetFiles.plan(destination, manifest)
        store.save()
        return targetFilesFor(stored) ?: throw IllegalStateException("no target files")
    }

    private suspend fun ask(id: String, prompt: DropPrompt): Answer {
        supersede(id)
        val pending = PendingPrompt(prompt, CompletableDeferred())
        prompts[id] = pending
        publish()
        return pending.result.await()
    }

    private fun announceOffer(id: String, manifest: List<ManifestFile>) {
        val transfer = web[id] ?: return
        if (!announced.add("offer:$id")) return
        mutableNotices.tryEmit(DropNotice.Offer(id, transfer.peerName, manifest.map(ManifestFile::name)))
    }

    private suspend fun decline(id: String) {
        (store.find(id) as? StoredReceiver)?.let {
            it.decision = DECLINED
            store.save()
        }
        step(id, "decline")
    }

    private suspend fun discard(id: String) {
        val stored = store.find(id) as? StoredReceiver ?: return
        val files = targets.remove(id)
        if (files != null) files.discardPartials() else removeStoredPartials(stored)
    }

    private suspend fun removeStoredPartials(stored: StoredReceiver) {
        val planned = stored.targets ?: return
        val folder = stored.folder ?: return
        runCatching { TargetFiles.removePartials(folders.open(folder), planned) }
    }

    private fun progress(id: String, done: Long) {
        val state = liveFor(id)
        val now = clock()
        state.done = done
        state.samples = state.samples.filter { now - it.first <= RATE_WINDOW_MS } + (now to done)
        links[id]?.progressed()
        // Finished files must survive a crash; progress alone is re-read from the disk.
        if (now - savedAt > SAVE_EVERY_MS) {
            savedAt = now
            store.save()
        }
        changed()
    }

    private suspend fun onOutcome(id: String, outcome: SessionOutcome) {
        val generation = epoch
        supersede(id)
        liveFor(id).apply {
            path = null
            verificationCode = null
            samples = emptyList()
        }
        val stored = store.find(id)
        try {
            when (outcome) {
                is SessionOutcome.Lost -> {
                    if (!outcome.detail.startsWith("protocol")) return
                    val failures = (protocolFailures[id] ?: 0) + 1
                    protocolFailures[id] = failures
                    log("LevikDrop: session broke (${outcome.detail})")
                    if (failures >= PROTOCOL_FAILURES) {
                        stopLink(id)
                        discard(id)
                        step(id, "fail", "protocol")
                    }
                }
                SessionOutcome.Done -> {
                    links.remove(id)
                    step(id, "complete")
                }
                is SessionOutcome.Declined -> links.remove(id)
                is SessionOutcome.Paused -> {
                    links.remove(id)
                    if (outcome.bySelf) step(id, "pause", "user")
                }
                is SessionOutcome.Cancelled -> {
                    links.remove(id)
                    discard(id)
                    step(id, "cancel")
                }
                is SessionOutcome.Failed -> {
                    links.remove(id)
                    discard(id)
                    step(id, "fail", outcome.reason)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("LevikDrop: ${errorCode(failure) ?: failure.javaClass.simpleName}")
        } finally {
            if (current(generation)) {
                if (stored != null) store.save()
                refreshNowInternal()
            }
        }
    }

    private suspend fun onStopped(id: String, reason: String, failure: Throwable?) {
        val generation = epoch
        links.remove(id)
        liveFor(id).apply {
            link = null
            path = null
            verificationCode = null
        }
        val transfer = web[id]
        val code = failure?.let(errorCode)
        try {
            if ((reason == "limit" || code == "quota_exceeded") && transfer?.sender == true && transfer.state == DropState.ACTIVE) {
                step(id, "pause", "quota")
            } else if (code == "subscription_required") {
                error = code
            } else if (failure != null && code != "transfer_ended" && code != "transfer_not_found") {
                // The website is out of reach: the next refresh tries again.
                log("LevikDrop: connect failed (${code ?: failure.javaClass.simpleName})")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stepFailure: Exception) {
            log("LevikDrop: ${errorCode(stepFailure) ?: stepFailure.javaClass.simpleName}")
        }
        if (!current(generation)) return
        resuming -= id
        changed()
        scheduleRefresh()
    }

    // Snapshot -----------------------------------------------------------------------------

    private fun current(generation: Long): Boolean = running && generation == epoch

    private fun requireCurrent(generation: Long) {
        if (!current(generation)) throw CancellationException("LevikDrop session changed")
    }

    private fun view(transfer: WebTransfer): DropTransferView {
        val stored = store.find(transfer.id)
        val state = live[transfer.id]
        val samples = state?.samples.orEmpty()
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val rate = if (first != null && last != null && last.first - first.first >= 1_000) {
            maxOf(0L, (last.second - first.second) * 1_000 / (last.first - first.first))
        } else {
            null
        }
        val done = if (transfer.state == DropState.DONE) transfer.totalBytes else state?.done ?: 0L
        return DropTransferView(
            id = transfer.id,
            sender = transfer.sender,
            peerDeviceId = transfer.peerDeviceId,
            peerName = transfer.peerName,
            peerPlatform = transfer.peerPlatform,
            state = transfer.state,
            reason = transfer.reason,
            fileCount = transfer.fileCount,
            totalBytes = transfer.totalBytes,
            files = when (stored) {
                is StoredSender -> stored.files.map { DropFileInfo(it.name, it.size) }
                is StoredReceiver -> stored.manifest?.map { DropFileInfo(it.name, it.size) }
                null -> null
            },
            doneBytes = minOf(done, transfer.totalBytes),
            bytesPerSecond = rate.takeIf { transfer.state.open },
            path = state?.path,
            link = if (links.containsKey(transfer.id)) state?.link ?: LinkStatus.CONNECTING else null,
            prompt = prompts[transfer.id]?.prompt,
            folder = (stored as? StoredReceiver)?.folder,
            saved = (stored as? StoredReceiver)?.targets?.any { it.final != null } == true,
            held = links.containsKey(transfer.id) && held(transfer.id),
            createdAtMs = transfer.createdAtMs,
            finishedAtMs = transfer.finishedAtMs,
            verificationCode = state?.verificationCode,
        )
    }

    private fun publish() {
        changedJob?.cancel()
        changedJob = null
        mutableSnapshot.value = DropSnapshot(
            available = available,
            loadedAtMs = loadedAt,
            devices = devices,
            transfers = web.values.sortedByDescending(WebTransfer::createdAtMs).map(::view),
            quota = quota,
            maxFiles = maxFiles,
            maxBytes = maxBytes,
            folder = store.folder ?: folders.default,
            error = error,
        )
    }

    /** Coalesced: progress comes many times a second. */
    private fun changed() {
        if (changedJob?.isActive == true) return
        changedJob = scope.launch {
            delay(CHANGED_MS)
            changedJob = null
            publish()
        }
    }

    companion object {
        const val PLATFORM = "android"
        const val NETWORK = "network"
        private const val ACCEPTED = "accepted"
        private const val DECLINED = "declined"
        private const val PRESENCE_MS = 60_000L
        private const val PRESENCE_RETRY_MS = 5 * 60_000L

        /** While a transfer is open the list is fetched this often anyway (missed events, retries). */
        private const val OPEN_REFRESH_MS = 30_000L
        private const val PROTOCOL_FAILURES = 5
        private const val RATE_WINDOW_MS = 4_000L
        private const val SAVE_EVERY_MS = 2_000L
        private const val CHANGED_MS = 250L
        private const val FREE_MARGIN = 64L * 1024 * 1024
        private val PLATFORMS = setOf("android", "windows", "macos", "linux")

        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun number(value: JsonElement?): Long? =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }?.takeIf { it >= 0 }

        private fun time(value: JsonElement?): Long? = text(value)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        private fun parseWebTransfer(value: JsonElement?): WebTransfer? {
            val obj = value as? JsonObject ?: return null
            val id = text(obj["id"])?.takeIf(DropWire::isTransferId) ?: return null
            val sender = when (text(obj["role"])) {
                "sender" -> true
                "receiver" -> false
                else -> return null
            }
            val peerDeviceId = text(obj["peerDeviceId"]) ?: return null
            val peerName = text(obj["peerName"]) ?: return null
            val state = DropState.of(text(obj["state"])) ?: return null
            val createdAt = time(obj["createdAt"]) ?: return null
            val fileCount = number(obj["fileCount"]) ?: return null
            val totalBytes = number(obj["totalBytes"]) ?: return null
            return WebTransfer(
                id = id,
                sender = sender,
                peerDeviceId = peerDeviceId,
                peerName = peerName.take(80),
                peerPlatform = text(obj["peerPlatform"])?.takeIf(PLATFORMS::contains),
                state = state,
                reason = text(obj["reason"])?.take(40),
                fileCount = fileCount.toInt(),
                totalBytes = totalBytes,
                createdAtMs = createdAt,
                finishedAtMs = time(obj["finishedAt"]),
            )
        }

        internal fun parseDevices(value: JsonElement?): List<DropDevice> = (value as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if ((obj["current"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val platform = text(obj["platform"])?.takeIf(PLATFORMS::contains) ?: return@mapNotNull null
            DropDevice(
                deviceId = text(obj["deviceId"]) ?: return@mapNotNull null,
                name = text(obj["name"])?.take(80) ?: return@mapNotNull null,
                platform = platform,
                ready = (obj["ready"] as? JsonPrimitive)?.booleanOrNull == true,
                supported = text(obj["keyId"]) != null,
            )
        }
    }
}
