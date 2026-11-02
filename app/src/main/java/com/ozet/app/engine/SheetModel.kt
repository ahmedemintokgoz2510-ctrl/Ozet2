package com.ozet.app.engine

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Tablo hücresi (POI'den bağımsız; Excel/CSV okuyucuları bu modele çevirir). */
class SCell(
    val kind: Kind,
    val text: String = "",
    val num: Double? = null,
    val decimals: Int = 0,
    val grouping: Boolean = false,
    val suffix: String = "",
    val formula: String? = null
) {
    enum class Kind { TEXT, NUM, PCT, DATE, BOOL, ERR }

    val isBlank: Boolean get() = kind == Kind.TEXT && text.isBlank()
    val isNumber: Boolean get() = (kind == Kind.NUM || kind == Kind.PCT) && num != null
}

class SGrid(
    val name: String,
    val hidden: Boolean,
    val rows: List<List<SCell?>>,
    val truncated: Boolean = false,
    /** Kaynak veride Türkçe sayı yazımı (1.250,50) görüldü; çıktı da aynı yazımı korur. */
    val turkishNumbers: Boolean = false
)

/**
 * Çalışma sayfalarını özetleyicinin işleyebileceği metne çevirir:
 *  - tablo başlıkları algılanır, her veri satırı "| Başlık: değer | ... |" olur
 *  - TOPLAM satırları (etiketten ya da dikey SUM formülünden) veri sayılmaz; istatistiğe katılmaz, ayrı cümle olur
 *  - sayısal sütunlar için toplam / ortalama / en düşük / en yüksek (hangi satırda olduğuyla) cümleleri üretilir
 *  - sayfa dışı serbest metin satırları (not, madde) aynen korunur
 */
object SheetRenderer {
    private val TOTAL_LABEL = Regex("(?iu)^\\s*(genel\\s+)?(ara\\s+)?(toplam|total|grand total|subtotal|sum|toplamı)\\b.*")
    private val VERT_SUM = Regex("(?i)^\\s*(?:SUM|SUBTOTAL|TOPLAM|ALTTOPLAM)\\s*\\(\\s*(?:\\d+\\s*[,;]\\s*)?\\$?([A-Z]{1,3})\\$?\\d+\\s*:\\s*\\$?\\1\\$?\\d+\\s*\\)\\s*$")
    private val ID_HEADER = Regex("(?iu)^(no|id|kod|code|sıra|sira|yıl|yil|year|ay|month|tarih|date|#|telefon|phone|zip|posta)(\\b.*)?$")

    private class Col(val index: Int, val header: String, val values: ArrayList<Pair<Double, String>> = ArrayList(), var sample: SCell? = null, var allPct: Boolean = true)

    fun render(grids: List<SGrid>): String {
        val visible = grids.filter { !it.hidden && it.rows.any { r -> r.any { c -> c != null && !c.isBlank } } }
        if (visible.isEmpty()) return ""
        val tr = looksTurkish(visible)
        val out = StringBuilder()
        for (g in visible) {
            out.append("# ").append(g.name.trim().ifEmpty { "Sheet" }).append("\n\n")
            renderGrid(g, tr, out)
            if (g.truncated) out.append(if (tr) "Not: sayfanın yalnızca ilk satırları okundu.\n\n" else "Note: only the first rows of the sheet were read.\n\n")
        }
        return out.toString().trim()
    }

    private val TR_WORDS = setOf(
        "toplam", "tutar", "adet", "satış", "bölge", "ürün", "kalem", "fiyat", "miktar", "tarih", "müşteri", "açıklama", "ad", "soyad",
        "ay", "yıl", "gelir", "gider", "bütçe", "oran", "not", "durum", "sorumlu", "birim", "stok", "kategori", "şehir", "il", "ilçe"
    )

    /** Dil tahmini: Türkçeye özgü harfler (ı ğ ş İ), ya da Türkçe tablo sözcükleri, ya da kaynağın Türkçe sayı yazımı. */
    private fun looksTurkish(grids: List<SGrid>): Boolean {
        if (grids.any { it.turkishNumbers }) return true
        var strong = 0
        var weak = 0
        var foreign = 0
        var words = 0
        var seen = 0
        loop@ for (g in grids) for (r in g.rows) for (c in r) {
            if (c == null || c.kind != SCell.Kind.TEXT) continue
            for (ch in c.text) when (ch) {
                'ı', 'ğ', 'ş', 'İ', 'Ğ', 'Ş' -> strong++
                'ç', 'Ç', 'ö', 'Ö', 'ü', 'Ü' -> weak++
                'ä', 'Ä', 'ß', 'é', 'è', 'à', 'ñ', 'â' -> foreign++
            }
            for (w in c.text.lowercase().split(Regex("[^\\p{L}]+"))) if (w in TR_WORDS) words++
            seen += c.text.length
            if (seen > 6000) break@loop
        }
        return strong >= 1 || words >= 1 || (weak >= 3 && foreign == 0)
    }

    private fun nonEmpty(r: List<SCell?>): List<Int> = r.indices.filter { r[it] != null && !r[it]!!.isBlank }

    private fun isHeaderRow(rows: List<List<SCell?>>, i: Int): Boolean {
        val ne = nonEmpty(rows[i])
        if (ne.size < 2 || ne.any { rows[i][it]!!.kind != SCell.Kind.TEXT }) return false
        val next = rows.getOrNull(i + 1) ?: return false
        val nn = nonEmpty(next)
        if (nn.isEmpty()) return false
        return nn.any { next[it]!!.kind != SCell.Kind.TEXT } || nn.size >= 2
    }

    private fun renderGrid(g: SGrid, tr: Boolean, out: StringBuilder) {
        val rows = g.rows
        var i = 0
        while (i < rows.size) {
            val ne = nonEmpty(rows[i])
            if (ne.isEmpty()) { i++; continue }
            if (isHeaderRow(rows, i)) { i = renderTable(rows, i, tr, out); continue }
            val text = ne.joinToString("; ") { show(rows[i][it]!!, tr) }
            if (ne.size == 1 && text.equals(g.name.trim(), ignoreCase = true)) { i++; continue }
            out.append(endSentence(text)).append(if (ne.size == 1 && looksNumbered(text)) "\n" else "\n\n")
            i++
        }
    }

    private fun looksNumbered(t: String) = Regex("^(\\d{1,2}[.)]|[•*-])\\s").containsMatchIn(t)

    private fun endSentence(t: String): String {
        val l = t.trimEnd().lastOrNull() ?: return t
        return if (l.isLetterOrDigit() && ';' in t) "$t." else t
    }

    private fun renderTable(rows: List<List<SCell?>>, start: Int, tr: Boolean, out: StringBuilder): Int {
        val hdr = rows[start]
        val cols = nonEmpty(hdr).associateWith { Col(it, hdr[it]!!.text.trim()) }
        var i = start + 1
        val rowLines = StringBuilder()
        val totals = ArrayList<List<SCell?>>()
        while (i < rows.size && nonEmpty(rows[i]).isNotEmpty()) {
            val r = rows[i]
            val ne = nonEmpty(r)
            if (isTotalRow(r)) { totals.add(r); i++; continue }
            val label = ne.mapNotNull { r[it] }.filter { it.kind == SCell.Kind.TEXT }.take(2).joinToString(" – ") { it.text.trim() }
            val cells = ArrayList<String>()
            for (c in ne) {
                val cell = r[c]!!
                val h = cols[c]?.header.orEmpty()
                val shown = show(cell, tr)
                cells.add(if (h.isNotEmpty()) "$h: $shown" else shown)
                val col = cols[c]
                if (col != null && cell.isNumber) {
                    col.values.add(cell.num!! to label)
                    if (col.sample == null) col.sample = cell
                    if (cell.kind != SCell.Kind.PCT) col.allPct = false
                }
            }
            rowLines.append("| ").append(cells.joinToString(" | ") { it.replace('|', '/') }).append(" |\n")
            i++
        }
        // Önce en önemli sayılar (genel toplam, sütun istatistikleri), sonra ayrıntı satırları.
        for (t in totals) {
            val ne = nonEmpty(t)
            val lab = t.mapNotNull { it }.firstOrNull { it.kind == SCell.Kind.TEXT && !it.isBlank }?.text?.trim().orEmpty()
            val parts = ne.filter { t[it]!!.kind != SCell.Kind.TEXT }.map { c ->
                val h = cols[c]?.header.orEmpty()
                (if (h.isNotEmpty()) "$h " else "") + show(t[c]!!, tr)
            }
            if (parts.isNotEmpty()) out.append(lab.ifEmpty { if (tr) "Toplam" else "Total" }).append(": ").append(parts.joinToString("; ")).append(".\n\n")
        }
        val label = if (tr) "sütunu" else "column"
        for (c in cols.values) {
            if (c.values.size < 2 || c.sample == null || skipStats(c)) continue
            out.append(statSentence(c, tr, label)).append("\n\n")
        }
        out.append(rowLines).append('\n')
        return i
    }

    private fun isTotalRow(r: List<SCell?>): Boolean {
        val firstText = r.firstOrNull { it != null && it.kind == SCell.Kind.TEXT && !it.isBlank }
        if (firstText != null && TOTAL_LABEL.matches(firstText.text)) return true
        return r.any { it?.formula != null && VERT_SUM.matches(it.formula) }
    }

    private fun skipStats(c: Col): Boolean {
        if (ID_HEADER.matches(c.header)) return true
        val v = c.values.map { it.first }
        return v.all { it == Math.floor(it) && it in 1900.0..2100.0 }
    }

    private fun statSentence(c: Col, tr: Boolean, colWord: String): String {
        val s = c.sample!!
        val vals = c.values
        val sum = vals.sumOf { it.first }
        val avg = sum / vals.size
        val min = vals.minByOrNull { it.first }!!
        val max = vals.maxByOrNull { it.first }!!
        fun f(v: Double) = smart(v, s, tr)
        fun withLabel(p: Pair<Double, String>) = if (p.second.isNotEmpty()) "${f(p.first)} (${p.second})" else f(p.first)
        val head = "${c.header} $colWord"
        return if (tr) {
            val total = if (c.allPct) "" else "toplam ${f(sum)}, "
            "$head: ${total}ortalama ${f(avg)}, en düşük ${withLabel(min)}, en yüksek ${withLabel(max)}."
        } else {
            val total = if (c.allPct) "" else "total ${f(sum)}, "
            "$head: ${total}average ${f(avg)}, lowest ${withLabel(min)}, highest ${withLabel(max)}."
        }
    }

    private fun smart(v: Double, like: SCell, tr: Boolean): String {
        val pct = like.kind == SCell.Kind.PCT
        val value = if (pct) v * 100 else v
        val whole = abs(value - Math.rint(value)) < 1e-9
        val d = if (whole) 0 else if (pct) maxOf(like.decimals, 1) else 2
        var s = fmtNum(value, d, like.grouping || (!pct && abs(value) >= 10_000), tr)
        if (d > 0) s = trimZeros(s, tr)
        return decorate(s, like, tr)
    }

    private fun trimZeros(s: String, tr: Boolean): String {
        val sep = if (tr) ',' else '.'
        if (sep !in s) return s
        return s.trimEnd('0').trimEnd(sep)
    }

    private fun decorate(num: String, c: SCell, tr: Boolean): String =
        if (c.kind == SCell.Kind.PCT) (if (tr) "%$num" else "$num%")
        else if (c.suffix.isNotEmpty()) (if (c.suffix == "$" || c.suffix == "£") c.suffix + num else "$num ${c.suffix}")
        else num

    fun show(c: SCell, tr: Boolean): String = when (c.kind) {
        SCell.Kind.TEXT, SCell.Kind.DATE, SCell.Kind.BOOL, SCell.Kind.ERR -> c.text.trim()
        SCell.Kind.NUM -> decorate(fmtNum(c.num ?: 0.0, c.decimals, c.grouping, tr), c, tr)
        SCell.Kind.PCT -> decorate(fmtNum((c.num ?: 0.0) * 100, c.decimals, false, tr), c, tr)
    }

    fun fmtNum(v: Double, decimals: Int, grouping: Boolean, tr: Boolean): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val bd = BigDecimal(v).setScale(decimals.coerceIn(0, 10), RoundingMode.HALF_UP)
        val plain = bd.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if ('.' in plain) plain.substringAfter('.') else ""
        val g = if (grouping) intPart.reversed().chunked(3).joinToString(if (tr) "." else ",").reversed() else intPart
        val sign = if (bd.signum() < 0) "-" else ""
        return sign + g + (if (frac.isNotEmpty()) (if (tr) "," else ".") + frac else "")
    }
}

/** CSV/TSV: tırnaklı alanlar, çok satırlı alanlar, ayraç (`,` `;` sekme `|`) tespiti ve sayı tanıma. */
object CsvParser {
    fun toGrid(text: String, name: String = "Sheet1"): SGrid {
        val clean = text.removePrefix("﻿")
        val delim = detectDelimiter(clean)
        val rows = ArrayList<List<SCell?>>()
        var trNumbers = false
        for (r in parse(clean, delim)) {
            rows.add(r.map { f ->
                if (!trNumbers && usesTurkishNumber(f)) trNumbers = true
                toCell(f)
            })
        }
        return SGrid(name, false, rows, false, trNumbers)
    }

    internal fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(8).toList()
        if (sample.isEmpty()) return ','
        var best = ','
        var bestScore = -1
        for (d in charArrayOf(',', ';', '\t', '|')) {
            val counts = sample.map { l -> var q = false; var n = 0; for (ch in l) { if (ch == '"') q = !q else if (ch == d && !q) n++ }; n }
            val min = counts.minOrNull() ?: 0
            if (min > 0 && min * sample.size >= bestScore) { if (counts.sum() > bestScore) { bestScore = counts.sum(); best = d } }
        }
        return best
    }

    fun parse(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        val n = text.length
        fun endField() { row.add(f.toString()); f.setLength(0) }
        fun endRow() { endField(); if (row.any { it.isNotBlank() }) rows.add(row); row = ArrayList() }
        while (i < n) {
            val ch = text[i]
            when {
                q -> if (ch == '"') { if (i + 1 < n && text[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(ch)
                ch == '"' && f.isEmpty() -> q = true
                ch == delim -> endField()
                ch == '\n' -> endRow()
                ch == '\r' -> { if (i + 1 < n && text[i + 1] == '\n') i++; endRow() }
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private val TR_NUM = Regex("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$")
    private val TR_DECIMAL = Regex("^-?\\d+,\\d{1,2}$")

    private fun usesTurkishNumber(raw: String): Boolean {
        val t = raw.trim().trimEnd('%', '₺').trim()
        return TR_NUM.matches(t) || TR_DECIMAL.matches(t)
    }
    private val EN_NUM = Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val PLAIN_NUM = Regex("^-?\\d+([.,]\\d+)?$")

    internal fun toCell(raw: String): SCell? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        var s = t
        var pct = false
        var suffix = ""
        if (s.endsWith("%")) { pct = true; s = s.dropLast(1).trim() } else if (s.startsWith("%")) { pct = true; s = s.drop(1).trim() }
        for (cur in listOf("₺", "TL", "€", "$", "£")) {
            if (s.endsWith(cur)) { suffix = cur; s = s.dropLast(cur.length).trim() }
            else if (s.startsWith(cur)) { suffix = cur; s = s.drop(cur.length).trim() }
        }
        val v: Double?
        var decimals = 0
        var grouping = false
        when {
            TR_NUM.matches(s) -> { grouping = true; val p = s.replace(".", "").split(','); v = p.joinToString(".").toDoubleOrNull(); decimals = p.getOrNull(1)?.length ?: 0 }
            EN_NUM.matches(s) -> { grouping = true; val p = s.replace(",", "").split('.'); v = p.joinToString(".").toDoubleOrNull(); decimals = p.getOrNull(1)?.length ?: 0 }
            PLAIN_NUM.matches(s) -> { val p = s.replace(',', '.').split('.'); v = s.replace(',', '.').toDoubleOrNull(); decimals = p.getOrNull(1)?.length ?: 0 }
            else -> v = null
        }
        if (v == null) return SCell(SCell.Kind.TEXT, text = t)
        return if (pct) SCell(SCell.Kind.PCT, num = v / 100, decimals = decimals, suffix = suffix)
        else SCell(SCell.Kind.NUM, num = v, decimals = decimals, grouping = grouping, suffix = suffix)
    }
}
