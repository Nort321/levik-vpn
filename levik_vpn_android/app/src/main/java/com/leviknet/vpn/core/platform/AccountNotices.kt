package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A notice of the notification center, docs/app-platform.md "Notifications":
 * a subscription that ends soon, a family member who needs help. Taking it
 * from the server delivers it; no other app and no Telegram message repeats it.
 */
data class AccountNotice(
    val id: String,
    val title: String,
    val body: String,
    val target: CabinetTarget,
)

object AccountNotices {
    /** The tap on a notice opens its cabinet page signed in. */
    const val ACTION_OPEN = "com.leviknet.vpn.action.OPEN_ACCOUNT_NOTICE"
    const val EXTRA_TARGET = "cabinet_target"

    private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private const val MAX_TITLE = 80
    private const val MAX_BODY = 400
    private const val MAX_NOTICES = 20

    /** Entries that are not a valid notice are skipped; an unknown page opens the cabinet. */
    fun parse(element: JsonElement?): List<AccountNotice> =
        (element as? JsonArray).orEmpty().asSequence()
            .mapNotNull { item ->
                val notice = item as? JsonObject ?: return@mapNotNull null
                val id = notice.text("id")?.takeIf(ID::matches) ?: return@mapNotNull null
                val title = notice.text("title")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }
                    ?: return@mapNotNull null
                val body = notice.text("body")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_BODY }
                    ?: return@mapNotNull null
                AccountNotice(id, title, body, target(notice.text("target")))
            }
            .take(MAX_NOTICES)
            .toList()

    fun target(path: String?): CabinetTarget =
        CabinetTarget.entries.firstOrNull { it.path == path } ?: CabinetTarget.DASHBOARD

    /** The page of a tapped notice; null for any other intent or a forged name. */
    fun openedTarget(action: String?, name: String?): CabinetTarget? =
        if (action == ACTION_OPEN) CabinetTarget.entries.firstOrNull { it.name == name } ?: CabinetTarget.DASHBOARD else null

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
