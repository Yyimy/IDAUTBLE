package com.example.idautble

internal const val HID_SEPARATOR_LIMIT = 32

/** Separator tokens are encoded as individual bytes in SEPHEX. */
internal val hidArrowTokens = mapOf(
    "{RIGHT}" to '\u0080',
    "{LEFT}" to '\u0081',
    "{DOWN}" to '\u0082',
    "{UP}" to '\u0083',
)
private val hidSeparatorTokens = mapOf("{TAB}" to '\t', "{ENTER}" to '\n', "{SPACE}" to ' ') + hidArrowTokens
private val hidTokensByByte = hidSeparatorTokens.entries.associate { (token, byte) -> byte to token }

data class HidOutputConfig(
    val fields: String = "PU",
    val separator: String = "\t",
    val ending: String = "ENTER",
    val layout: String = "US",
) {
    fun wire(): String {
        require(fields in setOf("P", "U", "PU") && ending in setOf("ENTER", "TAB", "NONE") && layout in setOf("US", "ES"))
        require(separator.length <= HID_SEPARATOR_LIMIT && separator.all { hidOutputCharacter(it) || it in hidTokensByByte })
        val hex = if (separator.isEmpty()) "-" else separator.map { "%02X".format(it.code) }.joinToString("")
        return "HIDCFG=1;FIELDS=$fields;SEPHEX=$hex;END=$ending;LAYOUT=$layout"
    }
    fun preview(): String = buildString {
        if (fields != "U") append("PALLET123")
        if (fields == "PU") append(separator)
        if (fields != "P") append("LOC01")
        if (ending == "ENTER") append('\n') else if (ending == "TAB") append('\t')
    }.let(::hidSeparatorPattern)
}

data class HidOutputState(
    val supported: Boolean = false,
    val config: HidOutputConfig? = null,
    val busy: Boolean = false,
    val message: String = "",
    val revision: Long = 0,
    val arrowSeparators: Boolean = false,
)

private fun hidOutputCharacter(c: Char) = c == '\t' || c == '\n' || c.code in 32..126

fun hidSeparatorPattern(bytes: String): String = buildString {
    bytes.forEach { byte ->
        append(if (byte == '{') "{{" else hidTokensByByte[byte] ?: byte.toString())
    }
}

/** null means invalid: never silently truncate or strip an unrecognised token. */
fun decodeHidSeparator(pattern: String, allowArrows: Boolean = true): String? {
    if (pattern.length > HID_SEPARATOR_LIMIT * 7) return null
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
