package com.leviknet.vpn.core.network

import kotlinx.serialization.Serializable

/** Invites, family and gifts: /api/mobile/v1/invites, /family and /referrals on the website. */

@Serializable
data class InviteCodeRequest(val code: String)

@Serializable
class EmptyRequest

@Serializable
data class InvitePreview(
    /** referral, family or gift */
    val kind: String,
    /** active, used or expired */
    val status: String,
    val inviterName: String? = null,
    val discountPercent: Int? = null,
    val expiresAt: String? = null,
    val seats: Int? = null,
    val usedSeats: Int? = null,
    val subscriptionExpiresAt: String? = null,
    val tariffId: String? = null,
    val tariffTitle: String? = null,
    val months: Int? = null,
)

@Serializable
data class InvitePreviewResponse(val ok: Boolean, val invite: InvitePreview)

@Serializable
data class InviteClaimResult(
    val kind: String,
    val discountPercent: Int? = null,
    val familyId: Long? = null,
    val tariffId: String? = null,
    val tariffTitle: String? = null,
    val months: Int? = null,
)

@Serializable
data class InviteClaimResponse(val ok: Boolean, val result: InviteClaimResult)

@Serializable
data class FamilyRequest(
    /** overview, invite, remove, leave or help */
    val action: String,
    val memberId: Long? = null,
    /** For help: whether the member lets the owner help with their apps. */
    val allowed: Boolean? = null,
)

@Serializable
data class FamilyMember(
    val id: Long,
    /** owner or member */
    val role: String,
    val name: String,
    val devices: Int,
    val lastActiveAt: String? = null,
    val joinedAt: String = "",
    /** The member lets the owner help with their apps. */
    val helpAllowed: Boolean = false,
)

@Serializable
data class FamilyOverview(
    val available: Boolean,
    val seats: Int,
    /** none, owner or member */
    val role: String,
    val familyId: Long? = null,
    val expiresAt: String? = null,
    val ownerName: String? = null,
    val usedSeats: Int? = null,
    val members: List<FamilyMember> = emptyList(),
    /** For a member: whether they let the owner help with their apps. */
    val helpAllowed: Boolean = false,
)

@Serializable
data class FamilyInviteLink(val url: String, val expiresAt: String)

/** A member's app the owner may help with, /api/mobile/v1/family/help; never its device id. */
@Serializable
data class FamilyHelpDevice(
    val ref: String,
    val name: String,
    /** android, windows, macos or linux */
    val platform: String,
    val appVersion: String? = null,
    /** The app takes commands, so help reaches it. */
    val remote: Boolean = false,
    val online: Boolean = false,
    val vpnConnected: Boolean = false,
)

@Serializable
data class FamilyHelpee(
    val memberId: Long,
    val name: String,
    val lastActiveAt: String? = null,
    /** False until the member signs in to an app with their Levik account. */
    val linked: Boolean = false,
    val devices: List<FamilyHelpDevice> = emptyList(),
)

@Serializable
data class FamilyHelpCommand(
    val id: String,
    val memberId: Long,
    val deviceRef: String,
    /** refresh_profile, auto_server or check_update */
    val command: String,
    /** pending, delivered, done, failed, unsupported or expired */
    val state: String,
    val detail: String? = null,
) {
    val final: Boolean get() = state in FINAL_STATES

    private companion object {
        val FINAL_STATES = setOf("done", "failed", "unsupported", "expired")
    }
}

@Serializable
data class FamilyHelpResponse(
    val ok: Boolean,
    val members: List<FamilyHelpee> = emptyList(),
    val commands: List<FamilyHelpCommand> = emptyList(),
) {
    fun latestFor(deviceRef: String): FamilyHelpCommand? = commands.firstOrNull { it.deviceRef == deviceRef }
}

@Serializable
data class FamilyHelpRequest(val memberId: Long, val deviceRef: String, val command: String)

@Serializable
data class FamilyHelpIssued(val ok: Boolean, val command: FamilyHelpCommand)

@Serializable
data class FamilyResponse(
    val ok: Boolean,
    val family: FamilyOverview,
    val invite: FamilyInviteLink? = null,
)

@Serializable
data class ReferralLink(
    val code: String,
    val url: String,
    val discountPercent: Int,
    val rewardDays: Int,
    val invited: Int,
    val rewarded: Int,
)

@Serializable
data class GiftLink(
    val id: Long,
    val orderId: Long = 0,
    val tariffId: String,
    val tariffTitle: String,
    val months: Int,
    /** active, used or expired */
    val status: String,
    val url: String? = null,
    val createdAt: String = "",
    val expiresAt: String? = null,
)

@Serializable
data class ReferralsResponse(
    val ok: Boolean,
    val referral: ReferralLink? = null,
    val gifts: List<GiftLink> = emptyList(),
    val giftTariffIds: List<String> = emptyList(),
)
