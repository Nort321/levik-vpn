package com.leviknet.vpn.core.platform

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The remote, docs/app-platform.md "Remote". Disconnecting is deliberately not
 * a command: an app answers "unsupported" to anything outside this list.
 */
enum class RemoteCommand(val wire: String) {
    CONNECT("connect"),
    AUTO_SERVER("auto_server"),
    REFRESH_PROFILE("refresh_profile"),
    CHECK_UPDATE("check_update"),
    COLLECT_DIAGNOSTICS("collect_diagnostics"),
    ;

    companion object {
        fun of(wire: String?): RemoteCommand? = entries.firstOrNull { it.wire == wire }
    }
}

enum class RemoteCommandState(val wire: String, val final: Boolean) {
    PENDING("pending", false),
    DELIVERED("delivered", false),
    DONE("done", true),
    FAILED("failed", true),
    UNSUPPORTED("unsupported", true),
    EXPIRED("expired", true),
    ;

    companion object {
        fun of(wire: String?): RemoteCommandState? = entries.firstOrNull { it.wire == wire }
    }
}

enum class RemotePlatform(val wire: String) {
    ANDROID("android"),
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
    ;

    companion object {
        fun of(wire: String?): RemotePlatform? = entries.firstOrNull { it.wire == wire }
    }
}

/** A command for this phone; an unknown name is kept so the phone can answer "unsupported". */
data class DeliveredCommand(
    val id: String,
    val command: String,
    val replyKey: String?,
    /** app, web or family: family is the family owner helping, with repairs only. */
    val via: String? = null,
) {
    val fromFamily: Boolean get() = via == VIA_FAMILY

    private companion object {
        const val VIA_FAMILY = "family"
    }
}

data class RemoteDevice(
    /** The id the app sends as X-HWID. */
    val deviceId: String,
    val name: String,
    val platform: RemotePlatform,
    val appVersion: String?,
    val current: Boolean,
    /** The app asks for commands, so the remote reaches it. */
    val remote: Boolean,
    val online: Boolean,
    val vpnConnected: Boolean,
    val country: String?,
)

data class IssuedCommand(
    val id: String,
    val deviceId: String,
    val command: RemoteCommand,
    val state: RemoteCommandState,
    val detail: String?,
)

data class RemoteSnapshot(
    val devices: List<RemoteDevice> = emptyList(),
    /** Newest first. */
    val commands: List<IssuedCommand> = emptyList(),
    /** Diagnostics links opened with this phone's key, by command id. */
    val notes: Map<String, String> = emptyMap(),
    val loadedAtMs: Long? = null,
) {
    fun latestFor(deviceId: String): IssuedCommand? = commands.firstOrNull { it.deviceId == deviceId }
}

enum class CommandResult(val wire: String) { DONE("done"), FAILED("failed"), UNSUPPORTED("unsupported") }

data class CommandOutcome(val result: CommandResult, val detail: String? = null) {
    companion object {
        val DONE = CommandOutcome(CommandResult.DONE)
        val FAILED = CommandOutcome(CommandResult.FAILED)
        val UNSUPPORTED = CommandOutcome(CommandResult.UNSUPPORTED)
    }
}

object RemoteCommands {
    /** Detail codes the website and the other apps explain, docs/app-platform.md "Remote". */
    const val VPN_PERMISSION = "vpn_permission"
    const val BUSY = "busy"
    const val ALREADY_CONNECTED = "already_connected"
    const val UP_TO_DATE = "up_to_date"
    const val UPDATE_CHECK_FAILED = "update_check_failed"
    const val AVAILABLE_PREFIX = "available:"

    /** What the family owner may run on a member's phone, docs/app-platform.md "Family help". */
    val FAMILY_COMMANDS = setOf(RemoteCommand.AUTO_SERVER, RemoteCommand.REFRESH_PROFILE, RemoteCommand.CHECK_UPDATE)

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val COUNTRY = Regex("^[A-Z]{2}$")
    private val NOTE_LINK = Regex("^https://note\\.leviknet\\.(?:org|com)/[A-Za-z0-9_-]+#[A-Za-z0-9_-]+$")
    private val DETAIL_UNSAFE = Regex("[^A-Za-z0-9._:-]")
    private const val MAX_ITEMS = 50
    private const val OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    fun parseDelivered(value: JsonElement?): List<DeliveredCommand> = items(value).mapNotNull { entry ->
        val id = entry.text("id", 36)?.takeIf(UUID::matches) ?: return@mapNotNull null
        val command = entry.text("command", 40) ?: return@mapNotNull null
        DeliveredCommand(id, command, entry.text("replyKey", 1_000), entry.text("via", 20))
    }

    fun parseDevices(value: JsonElement?): List<RemoteDevice> = items(value).mapNotNull { entry ->
        val deviceId = entry.text("deviceId", 160) ?: return@mapNotNull null
        val name = entry.text("name", 160) ?: return@mapNotNull null
        val platform = RemotePlatform.of(entry.text("platform", 20)) ?: return@mapNotNull null
        val presence = entry["presence"] as? JsonObject
        RemoteDevice(
            deviceId = deviceId,
            name = name,
            platform = platform,
            appVersion = entry.text("appVersion", 40),
            current = entry.flag("current"),
            remote = entry.flag("remote"),
            online = presence?.flag("online") == true,
            vpnConnected = presence?.flag("vpnConnected") == true,
            country = presence?.text("country", 2)?.takeIf(COUNTRY::matches),
        )
    }

    fun parseIssued(value: JsonElement?): List<IssuedCommand> = items(value).mapNotNull { entry ->
        val id = entry.text("id", 36)?.takeIf(UUID::matches) ?: return@mapNotNull null
        IssuedCommand(
            id = id,
            deviceId = entry.text("deviceId", 160) ?: return@mapNotNull null,
            command = RemoteCommand.of(entry.text("command", 40)) ?: return@mapNotNull null,
            state = RemoteCommandState.of(entry.text("state", 20)) ?: return@mapNotNull null,
            detail = entry.text("detail", 1_000),
        )
    }

    /** "available:<version>" in the characters the server accepts. */
    fun availableDetail(versionName: String): String =
        (AVAILABLE_PREFIX + versionName.replace(DETAIL_UNSAFE, "")).take(80)

    /** The diagnostics link, encrypted to the sender's key (RSA-OAEP, SHA-256). */
    fun seal(replyKey: String, link: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(replyKey)))
        val cipher = Cipher.getInstance(OAEP).apply { init(Cipher.ENCRYPT_MODE, key, OAEP_SPEC) }
        return Base64.getEncoder().encodeToString(cipher.doFinal(link.toByteArray(Charsets.UTF_8)))
    }

    /** A fresh key for one diagnostics request; the private half never leaves memory. */
    fun createReplyKey(): Pair<String, PrivateKey> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.public.encoded) to pair.private
    }

    /** The note link from a finished diagnostics command, or null when it is not one. */
    fun open(privateKey: PrivateKey, sealed: String): String? = runCatching {
        val cipher = Cipher.getInstance(OAEP).apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP_SPEC) }
        String(cipher.doFinal(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
    }.getOrNull()?.takeIf(NOTE_LINK::matches)

    private fun items(value: JsonElement?): List<JsonObject> =
        (value as? JsonArray).orEmpty().take(MAX_ITEMS).mapNotNull { it as? JsonObject }

    private fun JsonObject.text(name: String, max: Int): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= max }

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true
}
