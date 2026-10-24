package com.leviknet.vpn.core.drop

import java.text.Normalizer

/**
 * docs/levik-drop.md "Saving": a name from the other app is never a path.
 * The desktop apps apply the same rules (src/main/platform/levikDrop/fileNames.ts).
 */
object DropNames {
    private const val MAX_NAME_BYTES = 180

    // C0/C1 controls, bidirectional overrides and isolates, zero-width marks, BOM.
    private val INVISIBLE = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u2069\\ufeff]")
    private val RESERVED_CHARACTERS = Regex("[/\\\\:*?\"<>|]")

    // JavaScript's \s, so both ends cut the same characters.
    private const val SPACE = "\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
    private val LEADING = Regex("^[.$SPACE]+")
    private val TRAILING = Regex("[.$SPACE]+$")
    private val DEVICE_NAMES = Regex("^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]|conin\\$|conout\\$)$", RegexOption.IGNORE_CASE)

    private fun utf8Length(value: String): Int = value.encodeToByteArray().size

    /** Cuts [value] to at most [bytes] UTF-8 bytes without splitting a character. */
    private fun cut(value: String, bytes: Int): String {
        val result = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val length = utf8Length(character)
            if (used + length > bytes) break
            result.append(character)
            used += length
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        // ".bashrc"-like names have no extension; a long tail is not one either.
        if (dot <= 0 || name.length - dot > 16) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    fun safeFileName(raw: String): String {
        var name = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(INVISIBLE, "").replace(RESERVED_CHARACTERS, "_")
        name = name.replace(LEADING, "_").replace(TRAILING, "")
        if (name.isEmpty() || name == "_") name = "file"
        val (stem, extension) = splitExtension(name)
        if (DEVICE_NAMES.matches(stem)) name = "${stem}_$extension"
        if (utf8Length(name) > MAX_NAME_BYTES) {
            val (longStem, longExtension) = splitExtension(name)
            val keep = if (utf8Length(longExtension) <= 32) longExtension else ""
            name = cut(longStem, MAX_NAME_BYTES - utf8Length(keep)).replace(TRAILING, "") + keep
            if (name == keep) name = "file$keep"
        }
        return name
    }

    /** `name.ext`, then `name (1).ext`, `name (2).ext`… */
    fun numberedName(name: String, attempt: Int): String {
        if (attempt == 0) return name
        val (stem, extension) = splitExtension(name)
        return "$stem ($attempt)$extension"
    }

    /** The hidden partial file kept next to the target while it arrives. */
    fun partialName(name: String): String = ".$name.levikdrop"
}
