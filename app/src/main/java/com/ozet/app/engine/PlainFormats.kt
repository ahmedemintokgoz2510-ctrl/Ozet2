package com.ozet.app.engine

import java.nio.charset.Charset

/** RTF -> düz metin. Türkçe karakterler (\'hh ve \uN), paragraf/sekme, gizli hedef grupları (yazı tipi tablosu, resim...) doğru işlenir. */
object RtfReader {
    private val SKIP_DEST = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "footerl", "footerr",
        "footnote", "generator", "themedata", "colorschememapping", "latentstyles", "listtable", "listoverridetable", "revtbl", "rsidtbl", "xmlnstbl", "datastore", "object", "fldinst")

    fun read(rtf: String): String {
        if (!rtf.trimStart().startsWith("{\\rtf")) return rtf
        val cp = Regex("\\\\ansicpg(\\d+)").find(rtf)?.groupValues?.get(1)
        val charset: Charset = runCatching { Charset.forName("windows-" + (cp ?: "1254")) }.getOrDefault(Charsets.ISO_8859_1)
        val out = StringBuilder()
        val skipStack = ArrayList<Boolean>()
        var skip = false
        var uc = 1
        var i = 0
        val n = rtf.length
        var pendingSkip = 0
        while (i < n) {
            val c = rtf[i]
            when (c) {
                '{' -> { skipStack.add(skip); i++
                    // {\* ...} veya bilinen hedef grubu
                    if (i + 1 < n && rtf[i] == '\\' && rtf[i + 1] == '*') skip = true
                    else if (i < n && rtf[i] == '\\') {
                        var j = i + 1
                        while (j < n && rtf[j].isLetter()) j++
                        if (rtf.substring(i + 1, j) in SKIP_DEST) skip = true
                    }
                }
                '}' -> { skip = skipStack.removeLastOrNull() ?: false; i++ }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = rtf[i]
                    when {
                        d == '\'' -> {
                            val hex = rtf.substring(i + 1, minOf(i + 3, n))
                            val b = hex.toIntOrNull(16)
                            if (b != null && !skip) { if (pendingSkip > 0) pendingSkip-- else out.append(String(byteArrayOf(b.toByte()), charset)) }
                            i += 3
                        }
                        d == '\\' || d == '{' || d == '}' -> { if (!skip) out.append(d); i++ }
                        d == '~' -> { if (!skip) out.append(' '); i++ }
                        d == '_' -> { if (!skip) out.append('-'); i++ }
                        d == '-' || d == '|' || d == ':' -> i++
                        d.isLetter() -> {
                            var j = i
                            while (j < n && rtf[j].isLetter()) j++
                            val word = rtf.substring(i, j)
                            var k = j
                            if (k < n && (rtf[k] == '-' || rtf[k].isDigit())) { k++; while (k < n && rtf[k].isDigit()) k++ }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < n && rtf[k] == ' ') k++
                            if (!skip) when (word) {
                                "par", "line", "sect", "page", "row" -> out.append('\n')
                                "tab", "cell" -> out.append(' ')
                                "endash" -> out.append('–'); "emdash" -> out.append('—')
                                "lquote" -> out.append('‘'); "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“'); "rdblquote" -> out.append('”')
                                "bullet" -> out.append('•')
                                "uc" -> uc = param ?: 1
                                "u" -> if (param != null) {
                                    out.append((if (param < 0) param + 65536 else param).toChar())
                                    pendingSkip = uc
                                }
                            }
                            i = k
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (!skip) { if (pendingSkip > 0) pendingSkip-- else out.append(c) }
                    i++
                }
            }
        }
        return out.toString().lines().joinToString("\n") { it.trim() }.replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}

/** HTML -> düz metin (script/style atılır; blok etiketleri satır sonu olur; sayısal ve yaygın adlı varlıklar çözülür). */
object HtmlReader {
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "euro" to "€",
        "copy" to "©", "reg" to "®", "ouml" to "ö", "Ouml" to "Ö", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç", "Ccedil" to "Ç",
        "auml" to "ä", "Auml" to "Ä", "szlig" to "ß", "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á", "iacute" to "í",
        "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "bull" to "•", "middot" to "·"
    )
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#\\d+|[A-Za-z]+);")

    fun read(html: String): String {
        var s = html
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<(script|style|head|noscript)\\b.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|h[1-6]|li|div|tr|table|section|article|blockquote|ul|ol)>"), "\n")
            .replace(Regex("(?i)</t[dh]>"), " | ")
            .replace(Regex("(?s)<[^>]*>"), " ")
        s = ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { cp -> if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else "" } ?: m.value
                v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { cp -> if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else "" } ?: m.value
                else -> NAMED[v] ?: m.value
            }
        }
        return s.lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }.replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
