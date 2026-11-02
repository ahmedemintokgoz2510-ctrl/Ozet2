package com.ozet.app.engine

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Küçük, bağımlılıksız XML okuyucu. DOCX / PPTX / grafik XML'leri için yeterlidir ve Android ile JVM'de aynı çalışır
 * (böylece birim testlerle doğrulanabilir). DTD ve harici varlıkları işlemez (XXE yoktur); yalnızca temel varlıklar
 * ve sayısal karakter referansları çözülür.
 */
internal object MiniXml {
    sealed interface Ev

    class Start(val name: String, val qname: String, val attrs: Map<String, String>) : Ev {
        /** Önek verilirse ("r:id") tam eşleşir; önek verilmezse ("val") herhangi bir önekle eşleşir ("w:val"). */
        fun attr(key: String): String? {
            attrs[key]?.let { return it }
            if (':' in key) return null
            for ((k, v) in attrs) if (k.substringAfter(':') == key) return v
            return null
        }
    }

    class End(val name: String) : Ev
    class Text(val text: String) : Ev

    fun local(qname: String): String = qname.substringAfter(':')

    fun parse(xml: String, on: (Ev) -> Unit) {
        var i = 0
        val n = xml.length
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                on(Text(decode(xml.substring(i))))
                return
            }
            if (lt > i) on(Text(decode(xml.substring(i, lt))))
            when {
                xml.startsWith("<!--", lt) -> {
                    val e = xml.indexOf("-->", lt + 4)
                    i = if (e < 0) n else e + 3
                }
                xml.startsWith("<![CDATA[", lt) -> {
                    val e = xml.indexOf("]]>", lt + 9)
                    val end = if (e < 0) n else e
                    on(Text(xml.substring(lt + 9, end)))
                    i = if (e < 0) n else e + 3
                }
                xml.startsWith("<?", lt) || xml.startsWith("<!", lt) -> {
                    val e = xml.indexOf('>', lt + 2)
                    i = if (e < 0) n else e + 1
                }
                else -> {
                    val gt = tagEnd(xml, lt + 1)
                    if (gt < 0) return
                    var body = xml.substring(lt + 1, gt)
                    if (body.startsWith("/")) {
                        on(End(local(body.substring(1).trim())))
                    } else {
                        val self = body.endsWith("/")
                        if (self) body = body.substring(0, body.length - 1)
                        var sp = 0
                        while (sp < body.length && !body[sp].isWhitespace()) sp++
                        val q = body.substring(0, sp)
                        val attrs = if (sp >= body.length) emptyMap() else parseAttrs(body, sp)
                        on(Start(local(q), q, attrs))
                        if (self) on(End(local(q)))
                    }
                    i = gt + 1
                }
            }
        }
    }

    private fun tagEnd(s: String, from: Int): Int {
        var quote = '\u0000'
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun parseAttrs(body: String, from: Int): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = from
        val n = body.length
        while (i < n) {
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) break
            val ks = i
            while (i < n && body[i] != '=' && !body[i].isWhitespace()) i++
            val key = body.substring(ks, i)
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n || body[i] != '=') {
                if (key.isEmpty()) i++
                continue
            }
            i++
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) break
            val q = body[i]
            if (q != '"' && q != '\'') { i++; continue }
            i++
            val vs = i
            while (i < n && body[i] != q) i++
            if (key.isNotEmpty()) m[key] = decode(body.substring(vs, i))
            i++
        }
        return m
    }

    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);")

    private fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"
                v == "lt" -> "<"
                v == "gt" -> ">"
                v == "quot" -> "\""
                v == "apos" -> "'"
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { cp -> codePoint(cp) } ?: m.value
                else -> v.substring(1).toIntOrNull()?.let { cp -> codePoint(cp) } ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else ""
}

/** OOXML (zip) paketinden yalnızca istenen XML parçalarını belleğe okur; medya dosyalarına hiç dokunmaz. */
internal object OoxmlZip {
    fun readParts(input: InputStream, accept: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && accept(e.name)) {
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    while (true) {
                        val r = zis.read(tmp)
                        if (r < 0) break
                        buf.write(tmp, 0, r)
                    }
                    out[e.name] = String(buf.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
                }
                e = zis.nextEntry
            }
        }
        return out
    }
}
