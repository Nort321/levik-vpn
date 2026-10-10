package com.leviknet.vpn.ui.growth

import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.FamilyInviteLink
import com.leviknet.vpn.core.network.FamilyOverview
import com.leviknet.vpn.core.network.FamilyResponse
import com.leviknet.vpn.core.network.GiftLink
import com.leviknet.vpn.core.network.InviteClaimResult
import com.leviknet.vpn.core.network.InvitePreview
import com.leviknet.vpn.core.network.ReferralLink
import com.leviknet.vpn.data.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An invite opened from https://leviknet.org/i/<code>. Nothing is accepted without a tap. */
data class InviteUiState(
    val code: String,
    val preview: InvitePreview? = null,
    val loading: Boolean = false,
    val claiming: Boolean = false,
    val claimed: InviteClaimResult? = null,
    /** A server reason such as invite_used, or account_required / network. */
    val error: String? = null,
    /** Hidden while the person signs in; it comes back on its own afterwards. */
    val waitingForSignIn: Boolean = false,
)

data class FamilyUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val family: FamilyOverview? = null,
    val inviteLink: FamilyInviteLink? = null,
    val referral: ReferralLink? = null,
    val gifts: List<GiftLink> = emptyList(),
    val error: String? = null,
    val notice: FamilyNotice? = null,
)

enum class FamilyNotice { MEMBER_REMOVED, LEFT }

/** Referral, family and gift state for the app; screens live in GrowthScreens.kt. */
class GrowthController(
    private val repository: AppRepository,
    private val scope: CoroutineScope,
    private val isAuthenticated: () -> Boolean,
    private val onAccessChanged: () -> Unit,
) {
    private val mutableInvite = MutableStateFlow<InviteUiState?>(null)
    val invite: StateFlow<InviteUiState?> = mutableInvite.asStateFlow()

    private val mutableFamily = MutableStateFlow(FamilyUiState())
    val family: StateFlow<FamilyUiState> = mutableFamily.asStateFlow()

    private var inviteJob: Job? = null
    private var familyJob: Job? = null

    fun openInvite(code: String) {
        mutableInvite.value = InviteUiState(code = code)
        loadInvitePreview()
    }

    /** Called again once the person has signed in. */
    fun loadInvitePreview() {
        val current = mutableInvite.value ?: return
        if (current.claimed != null || current.loading) return
        if (!isAuthenticated()) {
            mutableInvite.value = current.copy(error = ACCOUNT_REQUIRED)
            return
        }
        inviteJob?.cancel()
        mutableInvite.value = current.copy(loading = true, error = null)
        inviteJob = scope.launch {
            val result = runGrowth { repository.invitePreview(current.code) }
            mutableInvite.update { state ->
                state?.takeIf { it.code == current.code }?.copy(
                    loading = false,
                    preview = result.getOrNull(),
                    error = result.exceptionOrNull()?.let(::errorCode),
                )
            }
        }
    }

    fun claimInvite() {
        val current = mutableInvite.value ?: return
        if (current.claiming || current.claimed != null || current.preview?.status != "active") return
        mutableInvite.value = current.copy(claiming = true, error = null)
        inviteJob = scope.launch {
            val result = runGrowth { repository.claimInvite(current.code) }
            result.getOrNull()?.let { claimed ->
                if (claimed.kind != "referral") onAccessChanged()
            }
            mutableInvite.update { state ->
                state?.takeIf { it.code == current.code }?.copy(
                    claiming = false,
                    claimed = result.getOrNull(),
                    error = result.exceptionOrNull()?.let(::errorCode),
                )
            }
        }
    }

    fun waitForSignIn() {
        mutableInvite.update { it?.copy(waitingForSignIn = true) }
    }

    fun onSignedIn() {
        val current = mutableInvite.value ?: return
        if (!current.waitingForSignIn && current.error != ACCOUNT_REQUIRED) return
        mutableInvite.value = current.copy(waitingForSignIn = false, error = null)
        loadInvitePreview()
    }

    fun dismissInvite() {
        inviteJob?.cancel()
        mutableInvite.value = null
    }

    fun openFamily() {
        mutableFamily.value = FamilyUiState(open = true)
        refreshFamily()
    }

    fun closeFamily() {
        familyJob?.cancel()
        mutableFamily.value = FamilyUiState()
    }

    fun refreshFamily() {
        if (!isAuthenticated()) {
            mutableFamily.update { it.copy(loading = false, error = ACCOUNT_REQUIRED) }
            return
        }
        familyJob?.cancel()
        mutableFamily.update { it.copy(loading = true, error = null) }
        familyJob = scope.launch {
            val family = runGrowth { repository.family(ACTION_OVERVIEW) }
            // Referral links and gifts are optional; a failure there must not hide the family.
            val referrals = runGrowth { repository.referrals() }.getOrNull()
            mutableFamily.update { state ->
                state.copy(
                    loading = false,
                    family = family.getOrNull()?.family ?: state.family,
                    referral = referrals?.referral,
                    gifts = referrals?.gifts.orEmpty(),
                    error = family.exceptionOrNull()?.let(::errorCode),
                )
            }
        }
    }

    fun createFamilyInvite() = familyAction(ACTION_INVITE)

    fun removeFamilyMember(memberId: Long) = familyAction(ACTION_REMOVE, memberId, FamilyNotice.MEMBER_REMOVED)

    fun leaveFamily() = familyAction(ACTION_LEAVE, notice = FamilyNotice.LEFT)

    private fun familyAction(action: String, memberId: Long? = null, notice: FamilyNotice? = null) {
        if (mutableFamily.value.busy) return
        mutableFamily.update { it.copy(busy = true, error = null, notice = null) }
        familyJob = scope.launch {
            val result: Result<FamilyResponse> = runGrowth { repository.family(action, memberId) }
            if (result.isSuccess && action == ACTION_LEAVE) onAccessChanged()
            mutableFamily.update { state ->
                val response = result.getOrNull()
                state.copy(
                    busy = false,
                    family = response?.family ?: state.family,
                    inviteLink = response?.invite ?: state.inviteLink.takeIf { action != ACTION_INVITE },
                    error = result.exceptionOrNull()?.let(::errorCode),
                    notice = notice.takeIf { result.isSuccess },
                )
            }
        }
    }

    private suspend fun <T> runGrowth(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLogger.w(TAG, "Growth request failed: ${error.javaClass.simpleName}")
        Result.failure(error)
    }

    private fun errorCode(error: Throwable): String = when (error) {
        is ApiException.Rejected -> error.code
        is ApiException.Unauthorized -> ACCOUNT_REQUIRED
        is ApiException.Network -> NETWORK
        else -> UNKNOWN
    }

    companion object {
        const val ACCOUNT_REQUIRED = "account_required"
        const val NETWORK = "network"
        const val UNKNOWN = "unknown"
        private const val TAG = "Growth"
        private const val ACTION_OVERVIEW = "overview"
        private const val ACTION_INVITE = "invite"
        private const val ACTION_REMOVE = "remove"
        private const val ACTION_LEAVE = "leave"
    }
}
