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
    /** overview, invite, remove or leave */
    val action: String,
    val memberId: Long? = null,
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
)

@Serializable
data class FamilyInviteLink(val url: String, val expiresAt: String)

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
