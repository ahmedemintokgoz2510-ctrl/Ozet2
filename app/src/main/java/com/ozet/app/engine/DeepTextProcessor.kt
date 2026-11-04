package com.ozet.app.engine

import kotlinx.coroutines.yield
import java.text.Normalizer

/**
 * Özetleyiciden ÖNCE çalışan, yapıyı KORUYAN metin hazırlığı.
 *
 * Önemli ilke: satır sonları, "# başlık", madde ve tablo satırları (`| a | b |`) özetleyicinin en değerli sinyalleridir;
 * burada bunlar asla düzleştirilmez. (Eski sürüm tüm metni tek satıra indirip özetleyiciyi tek "cümle"ye mahkûm ediyordu.)
 *
 * Yapılanlar: karakter normalizasyonu, PDF satır sonu tirelerini birleştirme, emoji/kontrol karakteri temizliği,
 * uzun URL'leri alan adına indirgeme, ayraç satırlarını atma ve e-posta zincirlerini ayrıştırma
 * (başlık / asıl mesaj / önceki yazışma; imza ve yasal uyarılar atılır).
 */
object DeepTextProcessor {

    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{200D}]")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2060\\uFEFF\\uFFFC]")
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{Ll})")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"')\\]]+")
    private val SEPARATOR_LINE = Regex("^[\\s\\-_=*~#.•·]{4,}$")
    private val MANY_NL = Regex("\\n{3,}")
    private val MULTI_SPACE = Regex(" {2,}")

    suspend fun prepare(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = normalize(raw)
        yield()

        val structured = if (EmailThread.looksLikeEmail(normalized)) EmailThread.restructure(normalized) else normalized
        yield()

        return tidy(structured)
    }

    /** Karakter düzeyi temizlik; satır yapısına dokunmaz. */
    internal fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u000C', '\n')                 // sayfa sonu -> boş satır (aşağıda)
            .replace("­", "")                   // yumuşak tire
            .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace('\t', ' ')
        s = CONTROL.replace(s, "")
        s = EMOJI.replace(s, "")
        s = s.lines().joinToString("\n") { it.trim().replace(MULTI_SPACE, " ") }
        s = HYPHEN_BREAK.replace(s, "$1$2")
        return MANY_NL.replace(s, "\n\n").trim()
    }

    /** Son düzenleme: ayraç satırları, URL kısaltma, ardışık boş satırlar. */
    internal fun tidy(text: String): String {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isNotEmpty() && SEPARATOR_LINE.matches(l)) { out.add(""); continue }
            out.add(shortenUrls(l))
        }
        return MANY_NL.replace(out.joinToString("\n"), "\n\n").trim()
    }

    /** "https://kargo.example.com/takip/TR998877" -> "kargo.example.com" (yol/sorgu özette gürültüdür). */
    internal fun shortenUrls(line: String): String =
        URL.replace(line) { m ->
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            val host = u.removePrefix("https://").removePrefix("http://").removePrefix("HTTPS://").removePrefix("HTTP://")
                .substringBefore('/').substringBefore('?').substringBefore('#')
            host
        }
}

/** PDF gibi çok sayfalı kaynaklarda her sayfada tekrar eden üst/alt bilgileri ve sayfa numaralarını ayıklar. */
object PageCleaner {
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")

    private fun key(line: String): String = DIGITS.replace(line.lowercase(), "#").replace(SPACES, " ").trim()

    private val BULLET_START = Regex("^([•●▪◦■*-]|\\d{1,2}[.)])\\s+\\S")

    /**
     * PDF'te satır genişliğinde bölünen madde metinlerini ("• ... Önlem:" + ertesi satırda devamı) tek maddeye birleştirir.
     * Yalnızca önceki satır madde işaretiyle başlıyor, uzun (satır genişliğinde) ve cümle bitirmiyorsa; sonraki satır
     * yeni madde / tablo / başlık / boş satır değilse birleştirir.
     */
    fun joinWrappedBullets(text: String): String {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val prev = out.lastOrNull()
            val cont = prev != null && line.isNotEmpty() && BULLET_START.containsMatchIn(prev) && prev.length >= 50 &&
                prev.last() !in ".!?…" && !BULLET_START.containsMatchIn(line) && !line.startsWith("|") && !line.startsWith("#") &&
                !(line.length < 40 && line.none { it.isLowerCase() })
            if (cont) out[out.size - 1] = prev + " " + line else out.add(line)
        }
        return out.joinToString("\n")
    }

    /** [pages]: her sayfanın metni. Dönüş: temizlenmiş sayfalar, boş satırla birleştirilmiş tek metin. */
    fun strip(pages: List<String>): String {
        val nonEmpty = pages.map { it.trim('\n') }.filter { it.isNotBlank() }
        if (nonEmpty.size < 2) return nonEmpty.joinToString("\n\n")
        val edge = 3
        val lineLists = nonEmpty.map { it.lines() }
        // Her sayfanın ilk/son 'edge' dolu satırının anahtarı
        val counts = HashMap<String, Int>()
        val edges = lineLists.map { lines ->
            val idx = lines.indices.filter { lines[it].isNotBlank() }
            val pick = (idx.take(edge) + idx.takeLast(edge)).toSet()
            pick.filter { lines[it].trim().length in 3..120 }.toSet()
        }
        for ((p, lines) in lineLists.withIndex()) {
            val seen = HashSet<String>()
            for (i in edges[p]) seen.add(key(lines[i]))
            for (k in seen) counts[k] = (counts[k] ?: 0) + 1
        }
        val threshold = maxOf(2, (nonEmpty.size + 1) / 2)
        val cleaned = lineLists.mapIndexed { p, lines ->
            lines.filterIndexed { i, l -> !(i in edges[p] && (counts[key(l)] ?: 0) >= threshold) }
                .joinToString("\n")
        }
        return cleaned.joinToString("\n\n")
    }
}

/**
 * Metin tablolarını (sekme ile ayrılmış sütunlar) başlık eşlemeli `| Sütun: değer | ... |` satırlarına çevirir.
 * Yanlış pozitifleri önlemek için: 3+ sütunlu en az 2 ardışık satır, ya da 2 sütunlu (kısa sol hücreli) en az 3 ardışık satır ister.
 */
object TabularText {
    private fun cells(line: String): List<String> = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }

    fun convert(text: String): String {
        val lines = text.split('\n')
        val cols = lines.map { l -> if ('\t' in l) cells(l).size else 0 }
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (cols[i] >= 2) {
                var j = i
                while (j < lines.size && cols[j] >= 2) j++
                val block = (i until j).map { cells(lines[it]) }
                val minCols = block.minOf { it.size }
                val isTable = (minCols >= 3 && block.size >= 2) || (minCols == 2 && block.size >= 3 && block.all { it[0].length <= 45 })
                if (isTable) {
                    out.addAll(TableFmt.render(block))
                    i = j
                    continue
                }
            }
            out.add(lines[i].replace('\t', ' ').replace(Regex(" {2,}"), " "))
            i++
        }
        return out.joinToString("\n")
    }
}

/** E-posta zinciri ayrıştırma. */
internal object EmailThread {
    private val HEADER = Regex("(?i)^(gönderen|gonderen|kimden|from|kime|to|cc|bcc|konu|subject|tarih|date|gönderilen|sent)\\s*:\\s*(.*)$")
    private val FWD_MARK = Regex("(?i)^[-_=\\s]*(forwarded message|iletilen ileti|iletilen mesaj|orijinal mesaj|original message|ilk mesaj)[-_=\\s]*$")
    private val ATTRIBUTION = Regex("(?i)^(on .{5,120} wrote:|.{5,120}\\byazdı:?|.{5,120}\\bschrieb:|le .{5,120} a écrit\\s*:)$")
    private val SUBJECT_PREFIX = Regex("(?i)^\\s*((re|fw|fwd|yn|ynt|cvp|ilt|aw|wg|tr)\\s*:\\s*)+")
    private val ANGLE_ADDR = Regex("\\s*<[^>]*@[^>]*>")
    private val DISCLAIMER = Regex(
        "(?i)gizlidir|yanlışlıkla aldıysanız|yalnızca (?:alıcı|ilgili)|bu e-?posta ve ekleri|confidential|intended recipient|" +
            "disclaimer|if you (?:have )?received this|privileged|bu mesaj .{0,40}kişiye özel|vertraulich|confidencial"
    )
    private val SIGNAL = Regex("(?i)\\b(tel|gsm|cep|phone|mobile|fax|faks|mob)\\b\\s*[:.]|\\+?\\d[\\d\\s().-]{8,}\\d|@[\\w.-]+\\.|https?://|www\\.")

    fun looksLikeEmail(text: String): Boolean {
        val head = text.lines().asSequence().filter { it.isNotBlank() }.take(14).toList()
        val headers = head.count { HEADER.matches(it.trim()) }
        if (headers >= 2) return true
        if (head.any { FWD_MARK.matches(it.trim()) } && headers >= 1) return true
        val lines = text.lines()
        return lines.count { it.trimStart().startsWith(">") } >= 3 && lines.any { ATTRIBUTION.matches(it.trim().removePrefix(">").trim()) }
    }

    fun restructure(text: String): String {
        val lines = text.lines()
        var i = 0
        var subject = ""
        var from = ""
        var to = ""
        var date = ""
        // 1) üst bilgi bloğu
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty() || FWD_MARK.matches(l)) { i++; continue }
            val m = HEADER.matchEntire(l)
            if (m != null) {
                val key = m.groupValues[1].lowercase()
                val v = m.groupValues[2].trim()
                when (key) {
                    "konu", "subject" -> if (subject.isEmpty()) subject = v
                    "gönderen", "gonderen", "kimden", "from" -> if (from.isEmpty()) from = v
                    "kime", "to" -> if (to.isEmpty()) to = v
                    "tarih", "date", "gönderilen", "sent" -> if (date.isEmpty()) date = v
                }
                i++
            } else break
        }
        // 2) asıl gövde / alıntı ayrımı
        val body = ArrayList<String>()
        val quoted = ArrayList<String>()
        var inQuote = false
        while (i < lines.size) {
            val l = lines[i].trim()
            if (!inQuote && (l.startsWith(">") || ATTRIBUTION.matches(l) || FWD_MARK.matches(l) ||
                    (HEADER.matches(l) && body.isNotEmpty() && body.last().isEmpty()))) inQuote = true
            if (inQuote) quoted.add(l) else body.add(l)
            i++
        }
        val main = cleanBlock(body)
        val prev = cleanBlock(quoted.map { stripQuote(it) }.filterNot { ATTRIBUTION.matches(it) || FWD_MARK.matches(it) || HEADER.matches(it) })
        val tr = isTurkish(text)
        val sb = StringBuilder()
        val subj = ANGLE_ADDR.replace(SUBJECT_PREFIX.replace(subject, ""), "").trim()
        if (subj.isNotEmpty()) sb.append("# ").append(subj).append("\n\n")
        val who = ANGLE_ADDR.replace(from, "").trim().trim('"')
        val whom = ANGLE_ADDR.replace(to, "").trim().trim('"')
        if (who.isNotEmpty()) {
            val line = StringBuilder()
            if (tr) {
                line.append("Gönderen ").append(who)
                if (whom.isNotEmpty()) line.append(", alıcı ").append(whom)
                if (date.isNotEmpty()) line.append(", tarih ").append(date)
            } else {
                line.append("From ").append(who)
                if (whom.isNotEmpty()) line.append(", to ").append(whom)
                if (date.isNotEmpty()) line.append(", date ").append(date)
            }
            sb.append(line).append(".\n\n")
        }
        sb.append(main)
        if (prev.isNotBlank()) {
            sb.append("\n\n# ").append(if (tr) "Önceki yazışma" else "Previous messages").append("\n\n").append(prev)
        }
        return sb.toString().trim()
    }

    private fun stripQuote(l: String): String {
        var s = l.trim()
        while (s.startsWith(">")) s = s.substring(1).trim()
        return s
    }

    private fun isTurkish(t: String): Boolean = t.count { it in "ğĞşŞıİ" } >= 3

    /** Yasal uyarı satırlarını ve en sondaki imza bloğunu atar. */
    private fun cleanBlock(lines: List<String>): String {
        val kept = lines.map { ANGLE_ADDR.replace(it, "") }.filterNot { DISCLAIMER.containsMatchIn(it) }
        // paragraflara böl
        val blocks = ArrayList<MutableList<String>>()
        var cur = ArrayList<String>()
        for (l in kept) {
            if (l.isBlank()) { if (cur.isNotEmpty()) { blocks.add(cur); cur = ArrayList() } } else cur.add(l)
        }
        if (cur.isNotEmpty()) blocks.add(cur)
        if (blocks.isNotEmpty() && isSignature(blocks.last())) blocks.removeAt(blocks.size - 1)
        return blocks.joinToString("\n\n") { it.joinToString("\n") }
    }

    private fun isSignature(b: List<String>): Boolean {
        if (b.size > 6 || b.any { it.length > 80 }) return false
        return b.any { SIGNAL.containsMatchIn(it) || (" | " in it && it.none { c -> c == '?' || c == '!' }) }
    }
}
