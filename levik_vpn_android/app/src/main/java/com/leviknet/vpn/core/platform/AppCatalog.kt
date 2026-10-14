package com.leviknet.vpn.core.platform

import com.leviknet.vpn.core.network.ServiceEndpoints
import com.leviknet.vpn.data.SplitTunnelMode
import java.security.PublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Apps and sites grouped by meaning (banks, payments, government services),
 * signed with the Levik configuration key like the service host list:
 * config/levik-categories-v1.json in the main repository, docs/app-platform.md.
 * The catalog only travels to the phone; what is installed never leaves it.
 */
data class CatalogCategory(val id: String, val titleRu: String, val titleEn: String)

data class CatalogApp(
    val id: String,
    val category: String,
    val nameRu: String,
    val nameEn: String,
    val androidPackages: List<String>,
    val domains: List<String>,
    /** Known to refuse working while a VPN is on: the app offers to let it bypass the VPN. */
    val suggestDirect: Boolean,
) {
    fun name(russian: Boolean): String = if (russian) nameRu else nameEn
}

data class AppCatalog(val sequence: Long, val categories: List<CatalogCategory>, val apps: List<CatalogApp>)

/** An installed app from the catalog that goes through the VPN and may not work there. */
data class DirectAppSuggestion(val app: CatalogApp, val packageName: String)

object AppCatalogPolicy {
    const val FILE_NAME = "levik-categories-v1.json"
    internal const val MAX_BYTES = 64 * 1024
    private const val MAX_CATEGORIES = 50
    private const val MAX_APPS = 500
    private const val MAX_ITEMS = 20
    private const val MAX_NAME = 60
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val PACKAGE = Regex("^(?=.{3,200}$)[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val DOMAIN = Regex("^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** The catalog sits next to the service host list on every mirror. */
    fun urls(configUrls: List<String>): List<String> =
        configUrls.map { it.substringBeforeLast('/') + "/" + FILE_NAME }.distinct()

    /** Verifies the signature over the exact bytes, then the content. Null when anything is off. */
    fun verify(body: ByteArray, signature: String, key: PublicKey? = null): AppCatalog? {
        if (body.size > MAX_BYTES || !ServiceEndpoints.signatureValid(body, signature, key)) return null
        return parse(body)
    }

    internal fun parse(body: ByteArray): AppCatalog? = runCatching {
        val root = Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject ?: return null
        if ((root["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1) return null
        val sequence = (root["sequence"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (sequence == null || sequence < 1) return null
        val categories = (root["categories"] as? JsonArray).orEmpty().take(MAX_CATEGORIES)
            .mapNotNull(::parseCategory)
            .distinctBy(CatalogCategory::id)
        val known = categories.mapTo(mutableSetOf(), CatalogCategory::id)
        val apps = (root["apps"] as? JsonArray).orEmpty().take(MAX_APPS)
            .mapNotNull(::parseApp)
            .filter { it.category in known }
            .distinctBy(CatalogApp::id)
        AppCatalog(sequence, categories, apps)
    }.getOrNull()

    private fun parseCategory(value: JsonElement): CatalogCategory? {
        val item = value as? JsonObject ?: return null
        val id = item.text("id")?.takeIf(ID::matches) ?: return null
        val title = item["title"] as? JsonObject ?: return null
        return CatalogCategory(id, title.name("ru") ?: return null, title.name("en") ?: return null)
    }

    private fun parseApp(value: JsonElement): CatalogApp? {
        val item = value as? JsonObject ?: return null
        val id = item.text("id")?.takeIf(ID::matches) ?: return null
        val category = item.text("category")?.takeIf(ID::matches) ?: return null
        val name = item["name"] as? JsonObject ?: return null
        val packages = item.strings("android").filter(PACKAGE::matches)
        val domains = item.strings("domains").map(String::lowercase).filter(DOMAIN::matches)
        if (packages.isEmpty() && domains.isEmpty()) return null
        return CatalogApp(
            id = id,
            category = category,
            nameRu = name.name("ru") ?: return null,
            nameEn = name.name("en") ?: return null,
            androidPackages = packages,
            domains = domains,
            suggestDirect = (item["suggestDirect"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
        )
    }

    /** Whether Android sends this app's traffic into the tunnel under the user's split tunnelling. */
    fun goesThroughVpn(mode: SplitTunnelMode, packages: Set<String>, packageName: String): Boolean = when (mode) {
        SplitTunnelMode.OFF -> true
        SplitTunnelMode.DISALLOWED -> packageName !in packages
        SplitTunnelMode.ALLOWED -> packageName in packages
    }

    /**
     * Split tunnelling that lets the app bypass the VPN and keeps every other
     * app as it was. From "all apps through the VPN" it starts a fresh
     * exclusion list, so a hidden old selection never takes other apps out.
     */
    fun routeDirect(mode: SplitTunnelMode, packages: Set<String>, packageName: String): Pair<SplitTunnelMode, Set<String>> =
        when (mode) {
            SplitTunnelMode.OFF -> SplitTunnelMode.DISALLOWED to setOf(packageName)
            SplitTunnelMode.DISALLOWED -> SplitTunnelMode.DISALLOWED to packages + packageName
            SplitTunnelMode.ALLOWED -> SplitTunnelMode.ALLOWED to packages - packageName
        }

    /** The first installed app worth asking about: suggested, still in the tunnel and not answered before. */
    fun nextSuggestion(
        catalog: AppCatalog?,
        mode: SplitTunnelMode,
        packages: Set<String>,
        answered: Set<String>,
        installed: (String) -> Boolean,
    ): DirectAppSuggestion? {
        catalog ?: return null
        for (app in catalog.apps) {
            if (!app.suggestDirect) continue
            val packageName = app.androidPackages.firstOrNull { candidate ->
                candidate !in answered && goesThroughVpn(mode, packages, candidate) && installed(candidate)
            } ?: continue
            return DirectAppSuggestion(app, packageName)
        }
        return null
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.name(key: String): String? =
        text(key)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

    private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .distinct()
        .take(MAX_ITEMS)
}
