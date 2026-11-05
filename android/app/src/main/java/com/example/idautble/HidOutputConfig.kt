package com.example.idautble

internal const val HID_SEPARATOR_LIMIT = 32

/** Separator tokens are encoded as individual bytes in SEPHEX. */
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
private val hidEndingLabels = mapOf(
    "ENTER" to "Enter",
    "TAB" to "Tab",
    "NONE" to "None",
    "SPACE" to "Space",
    "RIGHT" to "Right",
    "LEFT" to "Left",
    "DOWN" to "Down",
    "UP" to "Up",
    "ESC" to "Escape",
    "BACKSPACE" to "Backspace",
    "DELETE" to "Delete",
    "INSERT" to "Insert",
    "HOME" to "Home",
    "END" to "End",
    "PAGEUP" to "Page up",
    "PAGEDOWN" to "Page down",
    "F1" to "F1",
    "F2" to "F2",
    "F3" to "F3",
    "F4" to "F4",
    "F5" to "F5",
    "F6" to "F6",
    "F7" to "F7",
    "F8" to "F8",
    "F9" to "F9",
    "F10" to "F10",
    "F11" to "F11",
    "F12" to "F12",
)
internal fun hidEndingOptions(extended: Boolean) =
    if (extended) hidEndingLabels else hidEndingLabels.filterKeys { it in setOf("ENTER", "TAB", "NONE") }

internal fun hidSeparatorOptions(allowArrows: Boolean, extended: Boolean) =
    hidBaseTokens.keys + (if (allowArrows || extended) hidArrowTokens.keys else emptySet()) +
        (if (extended) hidExtendedTokens.keys else emptySet())

data class HidOutputConfig(
    val fields: String = "PU",
    val separator: String = "\t",
    val ending: String = "ENTER",
    val layout: String = "US",
) {
    fun wire(): String {
        require(fields in setOf("P", "U", "PU") && ending in hidEndingLabels && layout in setOf("US", "ES"))
        require(separator.length <= HID_SEPARATOR_LIMIT && separator.all { hidOutputCharacter(it) || it in hidTokensByByte })
        val hex = if (separator.isEmpty()) "-" else separator.map { "%02X".format(it.code) }.joinToString("")
        return "HIDCFG=1;FIELDS=$fields;SEPHEX=$hex;END=$ending;LAYOUT=$layout"
    }
    fun preview(): String = buildString {
        if (fields != "U") append("PALLET123")
        if (fields == "PU") append(separator)
        if (fields != "P") append("LOC01")
        hidSeparatorTokens["{$ending}"]?.let { append(it) }
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
)

private fun hidOutputCharacter(c: Char) = c == '\t' || c == '\n' || c.code in 32..126

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
    if (text.length > 160 || !text.startsWith("HIDCFG=1;")) return null
    val values = linkedMapOf<String, String>()
    for (part in text.substring(9).split(';')) {
        val pair = part.split('=', limit = 2)
        if (pair.size != 2 || values.put(pair[0], pair[1]) != null) return null
    }
    if (values.keys != setOf("FIELDS", "SEPHEX", "END", "LAYOUT")) return null
    val hex = values["SEPHEX"] ?: return null
    val separator = if (hex == "-") "" else {
        if (hex.isEmpty() || hex.length > HID_SEPARATOR_LIMIT * 2 || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    }
    val result = HidOutputConfig(values.getValue("FIELDS"), separator, values.getValue("END"), values.getValue("LAYOUT"))
    return runCatching { result.wire(); result }.getOrNull()
}
