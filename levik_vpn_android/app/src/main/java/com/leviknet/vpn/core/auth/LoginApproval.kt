package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.PendingApprovalWire
import java.time.Instant

/** A sign-in on another device waiting for this phone to pick its number. */
data class PendingApproval(
    val id: String,
    val deviceName: String,
    val platformLabel: String,
    val choices: List<Int>,
    val expiresAt: Instant,
)

/**
 * Approving sign-ins by number matching, docs/app-platform.md "Approving a
 * sign-in". A new device shows a number; this phone, signed in to the same
 * account, offers three and the user picks the one on the new device.
 */
internal object LoginApproval {
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val ACCOUNT_HINT = Regex("v1\\.[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{8,300}\\.[A-Za-z0-9_-]{22}")

    // Control and format (bidi) characters could disguise the device name.
    private val HIDDEN_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
    private val SPACES = Regex("\\s+")
    private const val MAX_APPROVALS = 5
    private const val MAX_HINT_LENGTH = 400

    /** Kept after signing out so the next sign-in can be approved on the user's other apps. */
    fun isAccountHint(value: String?): Boolean =
        value != null && value.length <= MAX_HINT_LENGTH && ACCOUNT_HINT.matches(value)

    fun matchNumber(value: Int?): Int? = value?.takeIf { it in 10..99 }

    /** The server's list, checked field by field; expired and malformed entries are dropped. */
    fun parse(items: List<PendingApprovalWire>, now: Instant): List<PendingApproval> =
        items.take(MAX_APPROVALS).mapNotNull { item -> parseItem(item, now) }

    /** A number of the offered three, or null for "this is not me". */
    fun isAnswer(approval: PendingApproval, answer: Int?): Boolean =
        answer == null || answer in approval.choices

    private fun parseItem(item: PendingApprovalWire, now: Instant): PendingApproval? {
        val id = item.id?.takeIf(UUID::matches) ?: return null
        val deviceName = text(item.deviceName, 120) ?: return null
        val platformLabel = text(item.platformLabel, 40) ?: return null
        val choices = item.choices?.mapNotNull(::matchNumber)
            ?.takeIf { it.size == 3 && item.choices.size == 3 && it.toSet().size == 3 }
            ?: return null
        val expiresAt = item.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        if (!expiresAt.isAfter(now)) return null
        return PendingApproval(id, deviceName, platformLabel, choices, expiresAt)
    }

    private fun text(value: String?, max: Int): String? {
        val cleaned = value?.replace(HIDDEN_CHARACTERS, " ")?.replace(SPACES, " ")?.trim()
        return cleaned?.takeIf { it.isNotEmpty() && it.length <= max }
    }
}
