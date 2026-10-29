package com.leviknet.vpn.core.sharing

import java.time.Clock
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A temporary LOCAL relay credential. It never contains the owner's account/profile keys. */
@Serializable
data class SharingInvitation(
    val v: Int = 1,
    val host: String,
    val port: Int,
    val method: String = METHOD,
    val password: String,
    val name: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "SharingInvitation(name=$name, expiresAt=$expiresAt)"

    companion object {
        const val METHOD = "aes-256-gcm"
        const val PREFIX = "levik-share://v1/"
        const val MAX_LIFETIME_MS = 60 * 60 * 1000L
        private val codec = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false; encodeDefaults = true }
        private val fields = setOf("v", "host", "port", "method", "password", "name", "expiresAt")

        fun parse(value: String, clock: Clock = Clock.systemUTC()): SharingInvitation {
            val text = value.trim()
            require(text.length in PREFIX.length + 1..2048 && text.startsWith(PREFIX)) { "Некорректное приглашение Levik" }
            val encoded = text.removePrefix(PREFIX)
            require(encoded.matches(Regex("[A-Za-z0-9_-]+"))) { "Некорректное приглашение Levik" }
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded) { "Некорректное приглашение Levik" }
            val source = bytes.decodeToString(throwOnInvalidSequence = true)
            require(codec.parseToJsonElement(source).jsonObject.keys == fields) { "Неподдерживаемое приглашение Levik" }
            return codec.decodeFromString<SharingInvitation>(source).also { it.validate(clock.millis()) }
        }
    }

    fun validate(nowMs: Long) {
        require(v == 1 && method == METHOD) { "Обновите Levik для этого приглашения" }
        require(isPrivateIpv4(host) && port in 1..65535) { "Приглашение должно вести к телефону в локальной сети" }
        require(password.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Некорректный ключ приглашения" }
        val key = Base64.getUrlDecoder().decode(password)
        require(key.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(key) == password) { "Некорректный ключ приглашения" }
        require(name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() }) { "Некорректное имя телефона" }
        require(expiresAt > nowMs && expiresAt - nowMs <= MAX_LIFETIME_MS + 120_000) { "Приглашение истекло. Попросите владельца создать новое" }
    }

    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(codec.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))
}

fun isPrivateIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it.startsWith('0')) }) return false
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 } || numbers[3] == 0 || numbers[3] == 255) return false
    return numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 192 && numbers[1] == 168
}

data class SharingHostState(
    val address: String,
    val proxyPort: Int,
    val guestPort: Int,
    val invitation: String,
    val expiresAt: Long,
    val ready: Boolean = false,
) {
    override fun toString(): String = "SharingHostState(ready=$ready, expiresAt=$expiresAt)"
}

data class SharingGuestState(val name: String, val expiresAt: Long, val connected: Boolean = false)
data class SharingSnapshot(
    val busy: Boolean = false,
    val error: String? = null,
    val host: SharingHostState? = null,
    val guest: SharingGuestState? = null,
)

object SharingStateStore {
    private val mutable = MutableStateFlow(SharingSnapshot())
    val state: StateFlow<SharingSnapshot> = mutable.asStateFlow()
    internal fun update(block: (SharingSnapshot) -> SharingSnapshot) = mutable.update(block)
    internal fun clear() { mutable.value = SharingSnapshot() }
    fun clearError() = mutable.update { it.copy(error = null) }
}
