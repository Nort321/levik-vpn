package com.leviknet.vpn.ui.rules

import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.AccountRules
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.RuleCategory
import com.leviknet.vpn.core.platform.RuleAction
import com.leviknet.vpn.core.platform.RuleChange
import com.leviknet.vpn.core.platform.SharedSite
import com.leviknet.vpn.data.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RulesUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val rules: AccountRules? = null,
    val categories: List<RuleCategory> = emptyList(),
    /** invalid_domain, too_many_rules, rate_limited, account_required, network or unknown. */
    val error: String? = null,
    /** The site just saved, for the confirmation line. */
    val saved: SavedRule? = null,
    /** A site shared to the app, waiting for "through the VPN" or "directly". */
    val shared: String? = null,
    /** Something was shared that names no site. */
    val sharedWithoutSite: Boolean = false,
)

data class SavedRule(val domain: String, val action: RuleAction?)

/**
 * The account's rules on the phone (Direct builds), docs/app-platform.md
 * "Account rules". A saved change reaches this phone's VPN through the
 * "rules" live event, like a change made anywhere else.
 */
class RulesController(
    private val repository: AppRepository,
    private val scope: CoroutineScope,
    private val isAuthenticated: () -> Boolean,
    val enabled: Boolean,
) {
    private val mutableState = MutableStateFlow(RulesUiState())
    val state: StateFlow<RulesUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    fun open() {
        if (!enabled) return
        mutableState.update { it.copy(open = true, error = null, saved = null) }
        refresh()
    }

    fun close() {
        loadJob?.cancel()
        mutableState.update { it.copy(open = false, loading = false, error = null, saved = null) }
    }

    fun refresh() {
        if (!enabled || mutableState.value.loading) return
        if (!isAuthenticated()) {
            mutableState.update { it.copy(error = ACCOUNT_REQUIRED) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            val result = runRules { repository.accountRules() }
            mutableState.update { state ->
                val response = result.getOrNull()
                state.copy(
                    loading = false,
                    rules = response?.rules ?: state.rules,
                    categories = response?.categories ?: state.categories,
                    error = result.exceptionOrNull()?.let(::errorCode),
                )
            }
        }
    }

    fun setCategory(category: String, action: RuleAction?) =
        change(RuleChange.SetCategory(category, action), saved = null)

    fun setDomain(input: String, action: RuleAction): Boolean {
        val domain = SharedSite.normalize(input.trim().substringAfter("://").substringBefore('/'))
        if (domain == null) {
            mutableState.update { it.copy(error = INVALID_DOMAIN, saved = null) }
            return false
        }
        change(RuleChange.SetDomain(domain, action), SavedRule(domain, action))
        return true
    }

    fun removeDomain(domain: String) = change(RuleChange.RemoveDomain(domain), SavedRule(domain, null))

    /** A link shared from another app; nothing is saved without a tap. */
    fun openShared(text: String?) {
        if (!enabled) return
        val site = SharedSite.from(text)
        mutableState.update { it.copy(shared = site, sharedWithoutSite = site == null, error = null, saved = null) }
    }

    fun dismissShared() = mutableState.update { it.copy(shared = null, sharedWithoutSite = false, error = null) }

    fun applyShared(action: RuleAction) {
        val site = mutableState.value.shared ?: return
        if (!isAuthenticated()) {
            mutableState.update { it.copy(error = ACCOUNT_REQUIRED) }
            return
        }
        change(RuleChange.SetDomain(site, action), SavedRule(site, action), closeShared = true)
    }

    private fun change(change: RuleChange, saved: SavedRule?, closeShared: Boolean = false) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, saved = null) }
        scope.launch {
            val result = runRules { repository.changeAccountRules(change) }
            mutableState.update { state ->
                state.copy(
                    busy = false,
                    rules = result.getOrNull() ?: state.rules,
                    error = result.exceptionOrNull()?.let(::errorCode),
                    saved = saved.takeIf { result.isSuccess },
                    shared = if (closeShared && result.isSuccess) null else state.shared,
                )
            }
        }
    }

    private suspend fun <T> runRules(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLogger.w(TAG, "Rules request failed: ${error.javaClass.simpleName}")
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
        const val INVALID_DOMAIN = "invalid_domain"
        const val NETWORK = "network"
        const val UNKNOWN = "unknown"
        private const val TAG = "Rules"
    }
}
