package com.example.idautble

internal const val HID_SEPARATOR_LIMIT = 32

/** Key tokens are encoded as individual bytes in SEPHEX, PREHEX and ENDHEX. */
internal val hidArrowTokens = mapOf(
    "{RIGHT}" to '\u0080',
    "{LEFT}" to '\u0081',
    "{DOWN}" to '\u0082',
    "{UP}" to '\u0083',
)
private val hidExtendedTokens = mapOf(
    "{ESC}" to '\u0084',
    "{BACKSPACE}" to '\u0085',
    "{DELETE}" to '\u0086',
    "{INSERT}" to '\u0087',
    "{HOME}" to '\u0088',
    "{END}" to '\u0089',
    "{PAGEUP}" to '\u008a',
    "{PAGEDOWN}" to '\u008b',
    "{F1}" to '\u008c',
    "{F2}" to '\u008d',
    "{F3}" to '\u008e',
    "{F4}" to '\u008f',
    "{F5}" to '\u0090',
    "{F6}" to '\u0091',
    "{F7}" to '\u0092',
    "{F8}" to '\u0093',
    "{F9}" to '\u0094',
    "{F10}" to '\u0095',
    "{F11}" to '\u0096',
    "{F12}" to '\u0097',
)
private val hidBaseTokens = mapOf("{TAB}" to '\t', "{ENTER}" to '\n', "{SPACE}" to ' ')
private val hidSeparatorTokens = hidBaseTokens + hidArrowTokens + hidExtendedTokens
private val hidTokensByByte = hidSeparatorTokens.entries.associate { (token, byte) -> byte to token }
internal fun legacyHidEnding(bytes: String, extended: Boolean = true): String? {
    if (bytes.isEmpty()) return "NONE"
    val key = bytes.singleOrNull() ?: return null
    if (!extended && key != '\n' && key != '\t') return null
    return hidTokensByByte[key]?.removeSurrounding("{", "}")
}

internal fun hidSeparatorOptions(allowArrows: Boolean, extended: Boolean) =
    hidBaseTokens.keys + (if (allowArrows || extended) hidArrowTokens.keys else emptySet()) +
        (if (extended) hidExtendedTokens.keys else emptySet())

internal val hidTypingSpeeds = mapOf(50 to "Slow", 30 to "Normal", 20 to "Fast")
internal val hidNavigationPauses = mapOf(0 to "Off", 100 to "100 ms", 250 to "250 ms")

data class HidOutputConfig(
    val fields: String = "PU",
    val separator: String = "\t",
    val ending: String = "\n",
    val layout: String = "US",
    val prefix: String = "",
    val hotkey: String = "NONE",
    val locationFirst: Boolean = false,
    val reportMs: Int = 30,
    val navigationPauseMs: Int = 0,
) {
    fun wire(
        includePrefix: Boolean = prefix.isNotEmpty(),
        includeHotkey: Boolean = hotkey != "NONE",
        includeEndingSequence: Boolean = legacyHidEnding(ending) == null,
        includeOutputOptions: Boolean = locationFirst || reportMs != 30 || navigationPauseMs != 0,
    ): String {
        require(fields in setOf("P", "U", "PU") && layout in setOf("US", "ES"))
        require(reportMs in hidTypingSpeeds && navigationPauseMs in hidNavigationPauses)
        val end = if (includeEndingSequence) "ENDHEX=${encodeHidSequence(ending)}"
            else "END=${requireNotNull(legacyHidEnding(ending))}"
        val base = "HIDCFG=1;FIELDS=$fields;SEPHEX=${encodeHidSequence(separator)};$end;LAYOUT=$layout"
        return buildString {
            append(base)
            if (includePrefix) append(";PREHEX=${encodeHidSequence(prefix)}")
            if (includeHotkey) {
                require(validHidHotkey(hotkey))
                append(";HOTKEY=$hotkey")
            }
            if (includeOutputOptions) {
                append(if (locationFirst) ";ORDER=UP" else ";ORDER=PU")
                append(";TIMING=$reportMs,$navigationPauseMs")
            }
        }
    }
    fun preview(): String = buildString {
        append(prefix)
        when (fields) {
            "P" -> append("PALLET123")
            "U" -> append("LOC01")
            else -> {
                append(if (locationFirst) "LOC01" else "PALLET123")
                append(separator)
                append(if (locationFirst) "PALLET123" else "LOC01")
            }
        }
        append(ending)
    }.let(::hidSeparatorPattern)
}

data class HidOutputState(
    val supported: Boolean = false,
    val config: HidOutputConfig? = null,
    val busy: Boolean = false,
    val message: String = "",
    val revision: Long = 0,
    val arrowSeparators: Boolean = false,
    val extendedKeys: Boolean = false,
    val prefixKeys: Boolean = false,
    val readHotkey: Boolean = false,
    val endingSequence: Boolean = false,
    val outputOptions: Boolean = false,
)

private val hidHotkeyPattern = Regex("(?:CTRL\\+(?:ALT\\+)?|ALT\\+)(?:SHIFT\\+)?(?:[A-Z0-9]|F(?:[1-9]|1[0-2]))")
internal fun validHidHotkey(value: String) = value == "NONE" || hidHotkeyPattern.matches(value)

private fun hidOutputCharacter(c: Char) = c == '\t' || c == '\n' || c.code in 32..126

private fun encodeHidSequence(bytes: String): String {
    require(bytes.length <= HID_SEPARATOR_LIMIT && bytes.all { hidOutputCharacter(it) || it in hidTokensByByte })
    return if (bytes.isEmpty()) "-" else bytes.map { "%02X".format(it.code) }.joinToString("")
}

private fun decodeHidSequence(hex: String): String? {
    if (hex == "-") return ""
    if (hex.isEmpty() || hex.length > HID_SEPARATOR_LIMIT * 2 || hex.length % 2 != 0 ||
        !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
    return hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
}

fun hidSeparatorPattern(bytes: String): String = buildString {
    bytes.forEach { byte ->
        append(if (byte == '{') "{{" else hidTokensByByte[byte] ?: byte.toString())
    }
}

/** null means invalid: never silently truncate or strip an unrecognised token. */
fun decodeHidSeparator(pattern: String, allowArrows: Boolean = true, extended: Boolean = true): String? {
    if (pattern.length > HID_SEPARATOR_LIMIT * "{BACKSPACE}".length) return null
    val out = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        if (pattern[i] != '{') {
            if (!hidOutputCharacter(pattern[i])) return null
            out.append(pattern[i++])
        } else if (pattern.startsWith("{{", i)) { out.append('{'); i += 2 }
        else {
            val end = pattern.indexOf('}', i)
            if (end < 0) return null
            val token = pattern.substring(i, end + 1)
            if (!extended && token in hidExtendedTokens) return null
            if (!allowArrows && token in hidArrowTokens) return null
            out.append(hidSeparatorTokens[token] ?: return null)
            i = end + 1
        }
        if (out.length > HID_SEPARATOR_LIMIT) return null
    }
    return out.toString()
}

fun parseHidOutputConfig(text: String): HidOutputConfig? {
    if (text.length > 320 || !text.startsWith("HIDCFG=1;")) return null
    val values = linkedMapOf<String, String>()
    for (part in text.substring(9).split(';')) {
        val pair = part.split('=', limit = 2)
        if (pair.size != 2 || values.put(pair[0], pair[1]) != null) return null
    }
    val required = setOf("FIELDS", "SEPHEX", "LAYOUT")
    val endingKeys = values.keys - required - setOf("PREHEX", "HOTKEY", "ORDER", "TIMING")
    if (!values.keys.containsAll(required) || endingKeys != setOf("END") && endingKeys != setOf("ENDHEX")) return null
    val ending = if ("ENDHEX" in values) decodeHidSequence(values.getValue("ENDHEX")) ?: return null
        else when (val name = values.getValue("END")) {
            "NONE" -> ""
            else -> hidSeparatorTokens["{$name}"]?.toString() ?: return null
        }
    val separator = decodeHidSequence(values.getValue("SEPHEX")) ?: return null
    val prefix = decodeHidSequence(values["PREHEX"] ?: "-") ?: return null
    val hotkey = values["HOTKEY"] ?: "NONE"
    val order = values["ORDER"]
    val timing = values["TIMING"]
    if ((order == null) != (timing == null)) return null
    if (order != null && order != "PU" && order != "UP") return null
    val reportMs = if (timing == null) 30 else hidTypingSpeeds.keys.firstOrNull {
        timing.substringBefore(',') == it.toString()
    } ?: return null
    val pauseMs = if (timing == null) 0 else hidNavigationPauses.keys.firstOrNull {
        timing.substringAfter(',') == it.toString()
    } ?: return null
    val result = HidOutputConfig(values.getValue("FIELDS"), separator, ending, values.getValue("LAYOUT"),
        prefix, hotkey, order == "UP", reportMs, pauseMs)
    return runCatching { result.wire(); result }.getOrNull()
}
