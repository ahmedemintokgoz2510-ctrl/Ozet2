package com.ozet.app.engine

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Tablo satırlarını özetleyicinin anladığı `| başlık: değer | ... |` biçimine çevirir. */
internal object TableFmt {
    private val NUMERICISH = Regex("^[\\d\\s.,%₺$€£+\\-–—/:]+$")

    fun render(rawRows: List<List<String>>): List<String> {
        val rows = rawRows.map { r -> r.map { it.replace('|', '/').replace(Regex("\\s+"), " ").trim() } }
            .filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return emptyList()
        val first = rows[0]
        val hasHeader = rows.size >= 2 && first.size >= 2 && first.all { it.isNotEmpty() && !NUMERICISH.matches(it) }
        val out = ArrayList<String>()
        if (hasHeader) {
            for (r in rows.drop(1)) {
                val cells = r.mapIndexedNotNull { i, v ->
                    if (v.isEmpty() || v == "—" || v == "-" || v == "–") null
                    else if (i < first.size && first[i].isNotEmpty()) "${first[i]}: $v" else v
                }
                if (cells.isNotEmpty()) out.add("| " + cells.joinToString(" | ") + " |")
            }
        } else {
            for (r in rows) {
                val cells = r.filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) out.add("| " + cells.joinToString(" | ") + " |")
            }
        }
        return out
    }
}

/** Baytları metne çevirir: BOM, katı UTF-8, olmazsa Windows-1254 (Türkçe ANSI). */
object TextDecode {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1254") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }
}

/** DOCX: başlıklar, numaralı/madde listeleri, tablolar; silinmiş (izlenen değişiklik) metin ve yorumlar hariç. */
object DocxReader {
    val PARTS = setOf("word/document.xml", "word/styles.xml", "word/numbering.xml")

    private class Para {
        val text = StringBuilder()
        var style: String? = null
        var numId: String? = null
        var ilvl = 0
        var outline = -1
    }

    private class Tbl {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
    }

    private class Block(val kind: Kind, val text: String) { enum class Kind { HEAD, LIST, PARA } }

    private val HEAD_NAMES = listOf("heading", "başlık", "baslik", "title", "überschrift", "titre", "título", "titulo", "encabezado", "intestazione")

    fun read(parts: Map<String, String>): String {
        val doc = parts["word/document.xml"] ?: return ""
        val headingStyles = HashSet<String>()
        parts["word/styles.xml"]?.let { parseStyles(it, headingStyles) }
        val numFmt = HashMap<String, Map<Int, String>>()
        val numToAbs = HashMap<String, String>()
        parts["word/numbering.xml"]?.let { parseNumbering(it, numFmt, numToAbs) }

        val blocks = ArrayList<Block>()
        val paras = ArrayList<Para>()
        val tables = ArrayList<Tbl>()
        val counters = HashMap<String, IntArray>()
        var inT = false
        var inPPr = false
        var delDepth = 0
        var fallbackDepth = 0

        fun emit(kind: Block.Kind, text: String) {
            if (text.isBlank()) return
            val t = tables.lastOrNull()
            if (t != null) {
                if (t.cell.isNotEmpty()) t.cell.append(' ')
                t.cell.append(text.trim())
            } else blocks.add(Block(kind, text.trim()))
        }

        MiniXml.parse(doc) { ev ->
            when (ev) {
                is MiniXml.Start -> when (ev.name) {
                    "p" -> paras.add(Para())
                    "pPr" -> inPPr = true
                    "pStyle" -> if (inPPr) paras.lastOrNull()?.style = ev.attr("val")
                    "numId" -> if (inPPr) paras.lastOrNull()?.numId = ev.attr("val")
                    "ilvl" -> if (inPPr) paras.lastOrNull()?.ilvl = ev.attr("val")?.toIntOrNull() ?: 0
                    "outlineLvl" -> if (inPPr) paras.lastOrNull()?.outline = ev.attr("val")?.toIntOrNull() ?: -1
                    "t" -> inT = true
                    "tab", "br", "cr" -> if (!inPPr && delDepth == 0 && fallbackDepth == 0) paras.lastOrNull()?.text?.append(' ')
                    "noBreakHyphen" -> if (delDepth == 0) paras.lastOrNull()?.text?.append('-')
                    "del", "moveFrom" -> delDepth++
                    "Fallback" -> fallbackDepth++
                    "tbl" -> tables.add(Tbl())
                    "tr" -> tables.lastOrNull()?.row = ArrayList()
                    "tc" -> tables.lastOrNull()?.cell?.setLength(0)
                }
                is MiniXml.Text -> if (inT && delDepth == 0 && fallbackDepth == 0) paras.lastOrNull()?.text?.append(ev.text)
                is MiniXml.End -> when (ev.name) {
                    "t" -> inT = false
                    "pPr" -> inPPr = false
                    "del", "moveFrom" -> if (delDepth > 0) delDepth--
                    "Fallback" -> if (fallbackDepth > 0) fallbackDepth--
                    "p" -> {
                        val p = paras.removeLastOrNull()
                        if (p != null) {
                            val text = p.text.toString().replace(Regex("\\s+"), " ").trim()
                            val numbered = p.numId != null && p.numId != "0"
                            val isHead = (p.style != null && p.style in headingStyles) || (p.outline in 0..8)
                            when {
                                text.isEmpty() -> {}
                                isHead && !numbered -> emit(Block.Kind.HEAD, text)
                                numbered -> {
                                    val fmt = numFmt[numToAbs[p.numId!!] ?: ""]?.get(p.ilvl) ?: "bullet"
                                    if (fmt == "bullet" || fmt == "none") emit(Block.Kind.LIST, "• $text")
                                    else {
                                        val c = counters.getOrPut(p.numId!!) { IntArray(10) }
                                        val lv = p.ilvl.coerceIn(0, 9)
                                        c[lv]++
                                        for (k in lv + 1 until 10) c[k] = 0
                                        emit(Block.Kind.LIST, "${c[lv]}. $text")
                                    }
                                }
                                else -> emit(Block.Kind.PARA, text)
                            }
                        }
                    }
                    "tc" -> tables.lastOrNull()?.let { t -> t.row.add(t.cell.toString().trim()); t.cell.setLength(0) }
                    "tr" -> tables.lastOrNull()?.let { t -> t.rows.add(t.row); t.row = ArrayList() }
                    "tbl" -> {
                        val t = tables.removeLastOrNull()
                        if (t != null) {
                            val lines = TableFmt.render(t.rows)
                            val parent = tables.lastOrNull()
                            if (parent != null) {
                                for (l in lines) { if (parent.cell.isNotEmpty()) parent.cell.append(' '); parent.cell.append(l.trim('|', ' ')) }
                            } else for (l in lines) blocks.add(Block(Block.Kind.LIST, l))
                        }
                    }
                }
            }
        }
        return render(blocks)
    }

    private fun render(blocks: List<Block>): String {
        val sb = StringBuilder()
        var prev: Block.Kind? = null
        for (b in blocks) {
            if (sb.isNotEmpty()) sb.append(if (prev == Block.Kind.LIST && b.kind == Block.Kind.LIST) "\n" else "\n\n")
            if (b.kind == Block.Kind.HEAD) sb.append("# ")
            sb.append(b.text)
            prev = b.kind
        }
        return sb.toString()
    }

    private fun parseStyles(xml: String, out: MutableSet<String>) {
        var id: String? = null
        var name: String? = null
        var outline = false
        var inPPr = false
        MiniXml.parse(xml) { ev ->
            when (ev) {
                is MiniXml.Start -> when (ev.name) {
                    "style" -> { id = ev.attr("styleId"); name = null; outline = false }
                    "name" -> if (id != null) name = ev.attr("val")
                    "pPr" -> inPPr = true
                    "outlineLvl" -> if (inPPr && id != null) outline = (ev.attr("val")?.toIntOrNull() ?: 9) <= 8
                }
                is MiniXml.End -> when (ev.name) {
                    "pPr" -> inPPr = false
                    "style" -> {
                        val sid = id
                        val n = name?.lowercase().orEmpty()
                        if (sid != null && (outline || HEAD_NAMES.any { n.startsWith(it) }) && !n.startsWith("toc")) out.add(sid)
                        id = null
                    }
                }
                else -> {}
            }
        }
    }

    private fun parseNumbering(xml: String, fmts: MutableMap<String, Map<Int, String>>, numToAbs: MutableMap<String, String>) {
        var absId: String? = null
        var lvl = -1
        var numId: String? = null
        var cur = HashMap<Int, String>()
        MiniXml.parse(xml) { ev ->
            when (ev) {
                is MiniXml.Start -> when (ev.name) {
                    "abstractNum" -> { absId = ev.attr("abstractNumId"); cur = HashMap() }
                    "lvl" -> lvl = ev.attr("ilvl")?.toIntOrNull() ?: -1
                    "numFmt" -> if (absId != null && lvl >= 0) cur[lvl] = ev.attr("val") ?: "decimal"
                    "num" -> numId = ev.attr("numId")
                    "abstractNumId" -> if (numId != null) numToAbs[numId!!] = ev.attr("val") ?: ""
                }
                is MiniXml.End -> when (ev.name) {
                    "abstractNum" -> { absId?.let { fmts[it] = cur }; absId = null }
                    "lvl" -> lvl = -1
                    "num" -> numId = null
                }
                else -> {}
            }
        }
    }
}

/** PPTX: slayt başlığı + gövde + tablo + GRAFİK VERİSİ + KONUŞMACI NOTLARI, sunum sırasına göre. */
object PptxReader {
    fun accepts(name: String): Boolean =
        name == "ppt/presentation.xml" || name == "ppt/_rels/presentation.xml.rels" ||
            (name.startsWith("ppt/slides/") && name.endsWith(".xml") || name.startsWith("ppt/slides/_rels/") && name.endsWith(".rels")) ||
            (name.startsWith("ppt/notesSlides/") && name.endsWith(".xml")) ||
            (name.startsWith("ppt/charts/") && name.endsWith(".xml") && '/' !in name.removePrefix("ppt/charts/"))

    private val SKIP_PH = setOf("sldNum", "ftr", "dt", "hdr")
    private val SHORT_KEY = Regex("^[^.!?]{1,18}$")

    private class Item(val text: String, val bullet: Boolean)

    fun read(parts: Map<String, String>): String {
        val order = slideOrder(parts)
        val out = StringBuilder()
        for (path in order) {
            val xml = parts[path] ?: continue
            val rels = parseRels(parts["ppt/slides/_rels/" + path.substringAfterLast('/') + ".rels"])
            val slide = readShapes(xml, rels, parts)
            val notesPath = rels.values.firstOrNull { it.startsWith("ppt/notesSlides/") }
            val notes = notesPath?.let { parts[it] }?.let { readShapes(it, emptyMap(), parts, notes = true) }

            var title = slide.title
            val items = ArrayList(slide.items)
            if (title == null && items.isNotEmpty() && !items[0].bullet && items[0].text.length <= 80 && !items[0].text.endsWith(".")) {
                title = items.removeAt(0).text
            }
            val merged = mergeKeyValue(items)
            val sb = StringBuilder()
            if (title != null) sb.append("# ").append(title).append("\n\n")
            for (it in merged) sb.append(it).append(if (it.startsWith("•") || it.startsWith("|") || it.matches(Regex("^\\d+\\. .*"))) "\n" else "\n\n")
            for (c in slide.charts) sb.append(c).append("\n\n")
            if (notes != null) {
                val nt = notes.items.joinToString("\n\n") { it.text }
                if (nt.isNotBlank()) sb.append(nt).append("\n\n")
            }
            if (sb.isNotBlank()) out.append(sb.toString().trimEnd()).append("\n\n")
        }
        return out.toString().trim()
    }

    /** "%27" + "Giyilebilir cihaz…" ya da "Kasım 2026" + "Ürün testleri…" gibi ayrı kutulardaki değer/etiket çiftlerini birleştirir. */
    private fun mergeKeyValue(items: List<Item>): List<String> {
        val res = ArrayList<String>()
        var i = 0
        while (i < items.size) {
            val a = items[i]
            val b = items.getOrNull(i + 1)
            if (b != null && !a.bullet && !b.bullet && SHORT_KEY.matches(a.text) && a.text.any { it.isDigit() } &&
                b.text.length > a.text.length && !b.text.startsWith("|")
            ) {
                res.add(asSentence(a.text + ": " + b.text)); i += 2
            } else {
                res.add(if (a.bullet && !a.text.startsWith("•") && !a.text.matches(Regex("^\\d+\\. .*"))) "• " + a.text else if (a.bullet) a.text else asSentence(a.text))
                i++
            }
        }
        return res
    }

    /** Slayttaki tek başına duran "Toplam: 97.500 adet" gibi olgu satırlarının başlık sanılmaması için nokta ile bitirir. */
    private fun asSentence(t: String): String {
        if (t.startsWith("|") || t.startsWith("•") || t.last() in ".!?:;…") return t
        return if (t.any { it.isDigit() } || ':' in t) "$t." else t
    }

    private fun slideOrder(parts: Map<String, String>): List<String> {
        val all = parts.keys.filter { Regex("^ppt/slides/slide\\d+\\.xml$").matches(it) }
        val pres = parts["ppt/presentation.xml"]
        val rels = parseRels(parts["ppt/_rels/presentation.xml.rels"], base = "ppt/")
        if (pres != null && rels.isNotEmpty()) {
            val ids = ArrayList<String>()
            MiniXml.parse(pres) { ev -> if (ev is MiniXml.Start && ev.name == "sldId") ev.attr("r:id")?.let { ids.add(it) } }
            val ordered = ids.mapNotNull { rels[it] }.filter { it in parts }
            if (ordered.isNotEmpty()) return ordered
        }
        return all.sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
    }

    private fun parseRels(xml: String?, base: String = "ppt/slides/"): Map<String, String> {
        if (xml == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        MiniXml.parse(xml) { ev ->
            if (ev is MiniXml.Start && ev.name == "Relationship") {
                val id = ev.attr("Id") ?: return@parse
                val target = ev.attr("Target") ?: return@parse
                if (ev.attr("TargetMode") == "External") return@parse
                m[id] = resolve(base, target)
            }
        }
        return m
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(base.trimEnd('/').split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) when (seg) { "..", "" -> if (seg == "..") parts.removeLastOrNull(); "." -> {}; else -> parts.add(seg) }
        return parts.joinToString("/")
    }

    private class Slide(val title: String?, val items: List<Item>, val charts: List<String>)

    private fun readShapes(xml: String, rels: Map<String, String>, parts: Map<String, String>, notes: Boolean = false): Slide {
        var title: String? = null
        val items = ArrayList<Item>()
        val charts = ArrayList<String>()
        val cur = StringBuilder()
        var inP = false
        var inT = false
        var bullet = false
        var auto = false
        var phType: String? = null
        var inSp = 0
        var tbl: ArrayList<List<String>>? = null
        var row: ArrayList<String>? = null
        val cell = StringBuilder()
        var inCell = false
        var autoCount = 0
        var fallback = 0

        MiniXml.parse(xml) { ev ->
            when (ev) {
                is MiniXml.Start -> when (ev.name) {
                    "sp" -> { inSp++; phType = null }
                    "ph" -> phType = ev.attr("type") ?: "body"
                    "Fallback" -> fallback++
                    "tbl" -> { tbl = ArrayList() }
                    "tr" -> row = ArrayList()
                    "tc" -> { inCell = true; cell.setLength(0) }
                    "p" -> { inP = true; cur.setLength(0); bullet = false; auto = false }
                    "buChar" -> bullet = true
                    "buAutoNum" -> { bullet = true; auto = true }
                    "t" -> inT = true
                    "br" -> if (inP) cur.append(' ')
                    "chart" -> ev.attr("r:id")?.let { id -> rels[id]?.let { p -> parts[p]?.let { x -> chartText(x)?.let { charts.add(it) } } } }
                }
                is MiniXml.Text -> if (inT && inP && fallback == 0) cur.append(ev.text)
                is MiniXml.End -> when (ev.name) {
                    "t" -> inT = false
                    "Fallback" -> if (fallback > 0) fallback--
                    "sp" -> { if (inSp > 0) inSp--; phType = null }
                    "p" -> {
                        inP = false
                        val text = cur.toString().replace(Regex("\\s+"), " ").trim()
                        if (text.isNotEmpty()) {
                            if (inCell) { if (cell.isNotEmpty()) cell.append(' '); cell.append(text) }
                            else if (phType !in SKIP_PH) {
                                if (!notes && (phType == "title" || phType == "ctrTitle") && title == null) title = text
                                else if (notes && phType != "body") { /* notlar sayfasında slayt görüntüsü/numara yer tutucuları atlanır */ }
                                else {
                                    if (auto) autoCount++ else autoCount = 0
                                    items.add(Item(if (auto) "$autoCount. $text" else text, bullet))
                                }
                            }
                        }
                    }
                    "tc" -> { row?.add(cell.toString().trim()); inCell = false; cell.setLength(0) }
                    "tr" -> { row?.let { r -> tbl?.add(r) }; row = null }
                    "tbl" -> { tbl?.let { t -> for (l in TableFmt.render(t)) items.add(Item(l, false)) }; tbl = null }
                }
            }
        }
        return Slide(title, items, charts)
    }

    /** Grafik XML'inden: başlık, seri adı, kategori ve değerleri okunur bir cümleye çevirir. */
    private fun chartText(xml: String): String? {
        var title: String? = null
        val series = ArrayList<Triple<String, List<String>, List<String>>>()
        var inTitle = false
        var inTx = false
        var inCat = false
        var inVal = false
        var inV = false
        var inT = false
        var sName = ""
        var cats = ArrayList<String>()
        var vals = ArrayList<String>()
        val buf = StringBuilder()
        var depthSer = 0
        MiniXml.parse(xml) { ev ->
            when (ev) {
                is MiniXml.Start -> when (ev.name) {
                    "title" -> if (depthSer == 0) { inTitle = true; buf.setLength(0) }
                    "ser" -> { depthSer++; sName = ""; cats = ArrayList(); vals = ArrayList() }
                    "tx" -> if (depthSer > 0) { inTx = true; buf.setLength(0) }
                    "cat" -> inCat = true
                    "val" -> inVal = true
                    "v" -> { inV = true; buf.setLength(0) }
                    "t" -> inT = true
                }
                is MiniXml.Text -> if (inV || (inTitle && inT)) buf.append(ev.text)
                is MiniXml.End -> when (ev.name) {
                    "t" -> inT = false
                    "title" -> if (inTitle) { title = buf.toString().trim().ifEmpty { null }; inTitle = false }
                    "v" -> {
                        inV = false
                        val v = buf.toString().trim()
                        if (inCat) cats.add(v) else if (inVal) vals.add(v) else if (inTx) sName = v
                    }
                    "tx" -> inTx = false
                    "cat" -> inCat = false
                    "val" -> inVal = false
                    "ser" -> { depthSer--; series.add(Triple(sName, cats, vals)) }
                }
            }
        }
        if (series.isEmpty()) return null
        val sb = StringBuilder()
        for ((i, s) in series.withIndex()) {
            if (i > 0) sb.append("\n")
            val head = listOfNotNull(title, s.first.ifEmpty { null }).distinct().joinToString(" – ")
            if (head.isNotEmpty()) sb.append(head).append(": ")
            sb.append(s.second.zip(s.third).joinToString("; ") { (c, v) -> "$c ${fmtNum(v)}" }.ifEmpty { s.third.joinToString("; ") { fmtNum(it) } }).append('.')
        }
        return sb.toString()
    }

    private fun fmtNum(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        if (d != Math.floor(d) || Math.abs(d) >= 1e15) return v
        return String.format(java.util.Locale.ROOT, "%,d", d.toLong()).replace(',', '.')
    }
}
