package com.leviknet.vpn.vpn

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Actual public editor redirects use /edit/d/; /edit/api is not a navigation target. */
internal fun isYandexGuestEditorPath(path: String): Boolean =
    path.startsWith("/edit/d/") || path.startsWith("/docs/")

internal fun isYandexFastGuestChallengePath(path: String): Boolean =
    path == "/showcaptchafast" || path == "/checkcaptchafast"

/** Reads only the anonymous, editable OnlyOffice bootstrap; never account cookies. */
internal fun parseYandexGuestConfig(encoded: String, now: Instant = Instant.now()): YandexProviderAuth {
    require(encoded.toByteArray().size in 2..131_072) { "Invalid guest editor response" }
    val root = Json.parseToJsonElement(encoded) as? JsonObject
        ?: throw IllegalArgumentException("Invalid guest editor response")
    val user = root["user"] as? JsonObject
    require((user?.get("auth") as? JsonPrimitive)?.booleanOrNull != true &&
        (user?.get("uid") as? JsonPrimitive)?.content in setOf(null, "", "0", "null")
    ) { "Open the document as an anonymous guest" }
    val action = root["officeActionData"] as? JsonObject
        ?: throw IllegalArgumentException("This editor is not supported yet")
    require((action["is_owner"] as? JsonPrimitive)?.booleanOrNull == false) {
        "Open the public editing link as a guest"
    }
    val editor = action["editor_config"] as? JsonObject
        ?: throw IllegalArgumentException("Missing guest editor configuration")
    val permissions = ((editor["document"] as? JsonObject)?.get("permissions") as? JsonObject)
    require((permissions?.get("edit") as? JsonPrimitive)?.booleanOrNull == true) {
        "Enable editing for everyone with the link"
    }
    val token = (editor["token"] as? JsonPrimitive)?.content.orEmpty()
    return YandexProviderAuth(
        balancerUrl = (action["balancer_url"] as? JsonPrimitive)?.content.orEmpty(),
        token = token,
        validUntil = YandexContract.guestCaptureDeadline(token, now),
    ).also { YandexContract.validateProviderAuth(it, now) }
}
