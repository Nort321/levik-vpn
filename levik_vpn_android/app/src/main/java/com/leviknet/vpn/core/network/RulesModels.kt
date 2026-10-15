package com.leviknet.vpn.core.network

import kotlinx.serialization.Serializable

/** The account's rules, /api/mobile/v1/rules; docs/app-platform.md "Account rules". */

@Serializable
data class AccountRules(
    /** Category id to "direct" or "proxy"; a category left out follows the mode. */
    val categories: Map<String, String> = emptyMap(),
    /** The user's own sites, newest first. */
    val domains: List<DomainRule> = emptyList(),
    /** 0 until the account first sets a rule. */
    val revision: Long = 0,
)

@Serializable
data class DomainRule(val domain: String, val action: String)

@Serializable
data class RuleText(val ru: String, val en: String) {
    fun localized(russian: Boolean): String = if (russian) ru else en
}

@Serializable
data class RuleCategory(
    val id: String,
    val title: RuleText,
    val apps: List<RuleText> = emptyList(),
)

@Serializable
data class RulesResponse(
    val ok: Boolean,
    val rules: AccountRules,
    val categories: List<RuleCategory> = emptyList(),
)

@Serializable
data class RulesChanged(val ok: Boolean, val rules: AccountRules)
