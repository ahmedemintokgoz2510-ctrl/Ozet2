package com.ozet.app.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Mode { AUTO, EXAM, STORY }
enum class Length { SHORT, MEDIUM, LONG }
enum class SectionKind { BULLETS, PARAGRAPHS, NUMBERED }

/** Bölüm başlığı: CUSTOM ise `title` metindeki gerçek başlık / konu adıdır, değilse arayüz dilindeki sabit başlık kullanılır. */
enum class SecTitle { CUSTOM, SUMMARY, TURNING, NOTES, GENERAL }

data class Section(
    val title: String?,
    val items: List<String>,
    val kind: SectionKind,
    val key: SecTitle = SecTitle.CUSTOM
)

data class Result(
    val mode: Mode,
    val sections: List<Section>,
    val keywords: List<String>,
    val sentenceCount: Int,
    val wordCount: Int,
    val srcLang: String = "und",
    val translated: Boolean = false,
    val translateFailed: Boolean = false,
    /** Otomatik kalite kontrolün iç notları (arayüzde gösterilmesi zorunlu değil; test/raporlama içindir). */
    val qualityNotes: List<String> = emptyList()
)

private class Sent(
    val idx: Int,
    val text: String,
    val stems: List<String>,
    val stemSet: Set<String>,
    val heading: String?,
    val bullet: Boolean,
    val dialogue: Boolean,
    val paraStart: Boolean,
    val lower: String,
    val para: Int
) {
    var base = 0.0
    var rank = 0.0
    var score = 0.0
    var facts: Set<String> = emptySet()
    var isDef = false
    var isClass = false
    var isCause = false
    var isCompare = false
    var isExample = false
    var isConc = false
    var isExc = false
    var isWarn = false
    var isUrgent = false
    var isDeadline = false
    var hasFormula = false
    var hasDate = false
    var hasPct = false
    var hasNum = false
    var ents = 0
    var member = false
    var head = -1
    var org = false
    var run = -1
    var seg = 0
    // --- hikâye motoru alanları (yalnızca iç kullanım; dış veri modeli değişmedi)
    var cs: IntArray = IntArray(0)   // cümlede geçen karakter kimlikleri
    var subj = -1                    // cümlenin öznesi/konuşanı olan karakter (yoksa -1)
    var introN = 0                   // bu cümlede ilk kez tanıtılan önemli karakter sayısı
    var meet = false                 // iki önemli karakterin ilk karşılaşması
    var fDec = false
    var fConf = false
    var fRev = false
    var fChg = false
    var fRes = false
    var fNew = false
    var newObj = 0
    var fTime = false
    var fPron = false
    var fResStart = false
    var act = false
    var obj = false
    var resNext = false
    var ev = 0
    var evs = 0.0
    var tps = 0.0
    var ok = true        // tek başına özete girebilir mi (yarım/alıntı ortası/aşırı uzun değil)
    var speech = false   // tırnaksız konuşma etiketi ("..., dedi.")
    var cont = false     // konuşmanın devamı ("Çünkü ...")
    val words: Int = text.count { it == ' ' } + 1
}

/** Hikâyedeki bir karakter: tam ad / takma ad kümesi, ilk geçiş ve önem puanı. */
private class Chr(val id: Int, val name: String) {
    var mentions = 0
    var first = -1
    var introIdx = -1
    var last = -1
    var spans = 0
    var acts = 0
    var imp = 0.0
    var major = false
}

private class CharModel(val list: List<Chr>, val nameStems: Set<String>) {
    val byId: Map<Int, Chr> = list.associateBy { it.id }
}

/** Hikâyedeki bir olay (ardışık cümle aralığı). */
private class Ev(val from: Int, val to: Int) {
    var value = 0.0
    val picks = ArrayList<Sent>()
}

private class StoryOut(
    val sections: List<Section>,
    val chosen: List<Sent>,
    val notes: List<String>,
    val chars: List<Chr> = emptyList(),
    val nameStems: Set<String> = emptySet(),
    val spread: Map<String, Int> = emptyMap(),
    val nEv: Int = 1,
    val salient: Set<String> = emptySet(),
    val timeish: Set<String> = emptySet()
)

private class Kw(
    val key: String,
    val surface: String,
    val stems: Set<String>,
    val tf: Int,
    val phrase: Boolean,
    var score: Double
)

private class ExamOut(val sections: List<Section>, val chosen: Set<Sent>, val notes: List<String>)

/**
 * Yerel, yapay zekasız (API'siz) özetleyici. Belgenin dili otomatik algılanır (TR/EN/DE/ES/FR/AR).
 *
 * Akış: temizleme + ayrıştırma (PDF kalıntıları, sayfa sonu bölünmeleri, tablo satırları)
 * -> özellik çıkarma (tarih, sayı, formül, isim, tanım, sınıflandırma, neden-sonuç, karşılaştırma, istisna...)
 * -> puanlama (TF-IDF + TextRank + bu özellikler) -> yapı korumalı seçim (her başlık, her madde, her sınıf üyesi)
 * -> doğrulama/onarım (kaybolan başlık, tarih, sayı, tanım, sınıflandırma, istisna, sonuç)
 * -> son kalite kontrol (tekrar temizliği, kapsam raporu) -> anahtar kelime seçimi.
 * Özet çıkarımsaldır: metne cümle eklemez, yalnızca orijinal cümleleri seçer; bu yüzden tarih, sayı ve isimler değişmez.
 */
object Summarizer {
    private const val SENT_END = ".!?…:\"”؟»;"

    private val WORD = Regex("\\p{L}+")
    private val WS = Regex("\\s+")
    private val PAGE_NUM = Regex("\\d{1,4}")
    private val PAGE_LABEL = Regex(
        "^(?:[-–—]\\s*\\d{1,4}\\s*[-–—]|(?:sayfa|page|seite|página|pagina|p\\.|ص|صفحة)\\s*\\d{1,4}(?:\\s*(?:/|of|von|de)\\s*\\d{1,4})?|\\d{1,4}\\s*/\\s*\\d{1,4})$",
        RegexOption.IGNORE_CASE
    )
    private val JUNK_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFD\\u200B\\uFEFF\\uE000-\\uF8FF]")
    private val LIGATURES = listOf("ﬁ" to "fi", "ﬂ" to "fl", "ﬀ" to "ff", "ﬃ" to "ffi", "ﬄ" to "ffl")
    private val MULTI_SPACE = Regex(" {2,}")
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")
    private val BULLET = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+\\S.*")
    private val BULLET_MARK = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+")
    private val NUMBERED_HEAD = Regex("^\\d+(\\.\\d+)*[.)]?\\s+\\p{Lu}")
    private val NAME = Regex("(?<=[^.!?…\"“”«»—–:\\s]\\s)\\p{Lu}\\p{Ll}{2,}")
    private val NUM = Regex("\\d+(?:[.,]\\d+)*")
    private val YEAR = Regex("(?<!\\d)(1[0-9]{3}|20[0-9]{2})(?!\\d)")
    private val ACRO = Regex("(?<![\\p{L}\\d])\\p{Lu}{2,}[\\p{Lu}\\d]*(?![\\p{L}])")
    private val FORMULA = Regex("[\\p{L}\\d)\\]]\\s?(?:=|≈|≤|≥|≠)\\s?[\\p{L}\\d(\\[√−\\-]|[√∑∫±×÷]")
    private val EXC = Regex(
        "istisna|hariç|dışında|şartıyla|koşuluyla|aksi halde|aksi takdirde|olmadıkça|" +
            "except|unless|provided that|exception|" +
            "ausnahme|außer|sofern|es sei denn|" +
            "excepto|salvo|a menos que|excepción|" +
            "sauf|excepté|à moins que|" +
            "إلا |باستثناء|ما لم|استثناء"
    )
    private val TR_COPULA = Regex("^(?:[^,]{2,45},[^.]{10,170}|\\p{Lu}[^.,]{8,140})\\p{L}+(d[ıiuü]r|t[ıiuü]r)[.!]?$")
    private val TR_COP_END = Regex("^\\p{L}{5,}(d[ıiuü]r|t[ıiuü]r)$")
    private val TR_CASE_END = Regex("^\\p{L}{4,}(de|da|den|dan|nin|nın|nun|nün|yle|yla)$")
    private val FR_PART = Regex("^\\p{L}{4,}(é|ée|és|ées)$")
    private val TR_ORPHAN = Regex("^\\p{L}+\\s+(gibi|ise)\\s|^(birincisi|ikincisi|üçüncüsü|dördüncüsü)\\s")
    private val TR_SUFFIX = Regex("(ndeki|daki|deki|taki|teki|lerin|ların|leri|ları|ler|lar|nin|nın|nun|nün|den|dan|ten|tan|ında|inde|unda|ünde|nda|nde|ın|un|ün|ye|ya)$")
    private val TR_NUM = Regex("^(iki|üç|dört|beş|altı|yedi|sekiz)(ye|ya|den|dan|e|a|li|lı|si|sı)?$")
    private val STRUCT = setOf(
        "giriş", "sonuç", "sonuçlar", "özet", "bölüm", "kısım", "başlık", "tablo", "şekil", "madde", "örnek", "kaynakça", "yöntem", "amaç",
        "introduction", "conclusion", "summary", "chapter", "section", "figure", "table", "example", "abstract", "method", "results",
        "einleitung", "fazit", "zusammenfassung", "kapitel", "abschnitt", "abbildung", "tabelle", "beispiel",
        "introducción", "conclusión", "resumen", "capítulo", "sección", "figura", "tabla", "ejemplo", "método", "resultados",
        "résumé", "chapitre", "tableau", "exemple", "méthode", "résultats",
        "مقدمة", "خاتمة", "ملخص", "فصل", "جدول", "مثال", "نتائج"
    )
    private val UNIT_AFTER = Regex(
        "^\\s?(?:%|°|mm|cm|km|kg|mg|ml|nm|hz|khz|mhz|ghz|mb|gb|tb|ay|yıl|gün|hafta|saat|dakika|dk|saniye|sn|tl|lira|usd|eur|dolar|euro|kişi|adet|metre|litre|gram|" +
            "year|month|week|day|hour|min|sec|jahr|monat|tag|woche|stunde|año|mes|semana|día|hora|an|mois|jour|semaine|heure|سنة|يوم|شهر|ساعة)|^\\s?[mglvwn](?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )
    private val WARN = Regex(
        "uyarı|dikkat|tehlike|asla|kesinlikle|yasaktır|yasak\\b|(?:mayın|meyin|mayınız|meyiniz)(?![\\p{L}])|" +
            "iptal|iade|acil\\b|gecikme|son tarih|cancel|refund|urgent|deadline|overdue|"+
            "warning|caution|danger|never|prohibited|must not|achtung|warnung|gefahr|niemals|verboten|" +
            "advertencia|precaución|peligro|nunca|prohibido|attention|jamais|interdit|تحذير|خطر|ممنوع|أبدا"
    )
    /** Sonuç/yaptırım bildiren cümleler (iptal, iade, ceza, fesih, dava...) mesajın en kritik bilgisidir; özetten düşmemeli. */
    private val URGENT = Regex(
        "iptal|iade|ceza\\b|cezai|fesih|dava\\b|hukuki|derhal|" +
            "cancel|refund|terminat|penalt|legal action|immediately|urgent|"+
            "kündig|erstattung|sofort|anular|reembolso|annul|remboursement"
    )
    /** Son tarih / süre bildiren cümleler ("17:00'ye kadar", "en geç", "deadline", "bis spätestens"). */
    private val DEADLINE = Regex(
        "\\d{1,2}[:.]\\d{2}'?\\p{L}*\\s+(?:kadar|itibaren)|en geç|son tarih|süre(?:si)? (?:dolmadan|sonunda)|" +
            "deadline|no later than|by (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|end of)|bis spätestens|a más tardar|au plus tard"
    )
    private val TR_CLASS2 = Regex(
        "(?<![\\p{L}\\d])(iki|üç|dört|beş|altı|yedi|sekiz|[2-8])\\s+(temel\\s+|ana\\s+|farklı\\s+|ayrı\\s+)?" +
            "(aşama|tür|türlü|grup|sınıf|çeşit|kısım|bölüm|adım|evre|dönem|yöntem|özellik|neden|etken|ilke|unsur|bileşen|katman|safha|başlık|kategori)\\p{L}*|" +
            "(aşama|gruba|gruplara|sınıfa|sınıflara|türe|türlere|kısma|kısımlara|bölüme|bölümlere|evreye|evrelere)\\p{L}*\\s+(ayrılır|incelenir|toplanır|bölünür|ayrılmaktadır|incelenebilir)"
    )
    private val TR_GEN = setOf(
        "nedenle", "nedeniyle", "nedenlerle", "sonucunda", "dolayı", "dolayısıyla", "böylece", "ayrıca", "ancak", "fakat", "çünkü", "olarak",
        "olan", "olması", "olur", "olmaktadır", "bulunmaktadır", "sağlar", "gerekir", "gerekmektedir", "edin", "ediniz", "yapın", "yapınız",
        "edilir", "yapılır", "bulunur", "görülür", "oluşur", "denir", "kullanılır", "yapılmalıdır", "edilmelidir"
    )
    private val PCT = Regex("%|٪|yüzde|percent|prozent|por ciento|pour cent|بالمئة|في المائة|بالمائة")

    // ---- hikâye motoru kalıpları (küçük harfli cümle üzerinde aranır; TR/EN/DE/ES/FR/AR)
    private val SPEECH = Regex(
        """(?<![\p{L}])(?:dedi|diye\s+\p{L}+|söyledi|fısıldadı|bağırdı|sordu|yanıtladı|said|asked|replied|answered|shouted|whispered|cried|""" +
            """sagte|fragte|antwortete|rief|flüsterte|dijo|preguntó|respondió|gritó|susurró|dit|demanda|répondit|s'écria|murmura|قال|سأل|أجاب|صرخ|همس)(?![\p{L}])"""
    )
    private val DECISION = Regex(
        """karar ver|karar al|kararını|razı ol|söz verdi|yemin et|seçti|decided|resolved to|made up (?:his|her|their) mind|promised|vowed|chose to|so (?:he|she|they|i|we) |""" +
            """beschloss|entschied|versprach|decidió|resolvió|prometió|décida|promit|قرر|وعد"""
    )
    private val CONFLICT = Regex(
        """öfkelen|kızdı|bağır|kavga|saldır|çatış|tehdit|suçla|kaçtı|kovala|peşinden|yakalan|savaş|korktu|dövüş|öldü|ölüm|yaralan|yığıldı|kaybol|yok oldu|çalın|çaldı|zehir|""" +
            """died|killed|wounded|vanished|disappeared|stolen|stole|lost his|lost her|broke his|broke her|""" +
            """attack|fight|fought|angry|shout|threat|accus|chase|escaped|fled|afraid|angriff|kampf|wütend|schrie|drohte|verfolg|floh|""" +
            """atac|pelea|enojad|gritó|amenaz|persigu|huyó|combat|colère|menaç|poursui|fuit|هاجم|صرخ|هرب|غضب"""
    )
    private val REVEAL = Regex(
        """fark et|anladı|öğrendi(?!k)|hatırladı|remembered|recalled|keşfet|ortaya çık|itiraf|meğer|anlaşıldı|""" +
            """discover|realiz|learned|found out|revealed|confess|entdeck|erkannte|erfuhr|""" +
            """descubr|se dio cuenta|découvr|réalisa|apprit|اكتشف|أدرك"""
    )
    private val NEWEV = Regex(
        """birden|ansızın|belirdi|ortaya çıktı|karşısına çıktı|suddenly|appeared|arrived|came upon|plötzlich|erschien|de repente|apareció|soudain|apparut|فجأة"""
    )
    private val CHANGE = Regex(
        """artık|bir daha|ilk kez|değişti|dönüştü|cesaretlen|güçlen|olgunlaş|alıştı|never again|no longer|for the first time|from then on|changed|became|nie wieder|nicht mehr|""" +
            """zum ersten|verändert|ya no|por primera vez|cambió|ne plus|pour la première|changea|لم يعد|لأول مرة|تغير"""
    )
    private val RESOLUTION = Regex(
        """sonunda|nihayet|kavuştu|kurtul|başardı|yenildi|kazandı|huzur|finally|at last|in the end|succeeded|defeated|peace|""" +
            """endlich|schließlich|besiegt|gelang|por fin|finalmente|logró|derrot|enfin|finalement|réussit|vaincu|أخيرا|نجح|هزم"""
    )
    private val TIME_SHIFT = Regex(
        """ertesi (?:gün|sabah|akşam|hafta)|^(?:bir )?(?:sabah|akşam|gün)\b|o gece|birkaç gün sonra|bir hafta sonra|aylar sonra|yıllar sonra|bir gün|günler sonra|""" +
            """next (?:day|morning|week)|days later|weeks later|years later|that night|one day|am nächsten|tage später|jahre später|eines tages|""" +
            """al día siguiente|días después|años después|un día|esa noche|le lendemain|jours plus tard|années plus tard|un jour|""" +
            """في اليوم التالي|بعد أيام|ذات يوم"""
    )
    private val RESULT_START = Regex(
        """^(?:böylece|bu yüzden|bu nedenle|bunun üzerine|bu sayede|so|thus|therefore|as a result|deshalb|daher|dadurch|así|por eso|por lo tanto|""" +
            """ainsi|donc|alors|لذلك|وهكذا)(?![\p{L}])"""
    )
    private val PRON_START = Regex(
        """^(?:o(?!\s+(?:gece|gün|akşam|sabah|zaman|anda))|onlar|ona|onu|onun|onlara|onları|bunu|buna|bunun|bunlar|bunlara|şunu|orada|oraya|burada|""" +
            """he|she|it|they|him|her|them|this|that(?!\s+(?:night|day|morning))|these|those|there|er|sie|es|dies|das|diese|dort|""" +
            """él|ella|ellos|ellas|esto|eso|esta|ese|allí|il|elle|ils|elles|ce|cela|ça|ceci|là|هو|هي|هم|هذا|هذه|ذلك)\s"""
    )
    private val EARLY_DEMO = Regex("""(?:^| )(?:bunu|buna|bunun|bundan|bunlar|bunları|bunlara|şunu|şuna|this|that|these|those|dies|dieses|diesen|esto|eso|ceci|cela)(?![\p{L}])""")
    private val DEMO_START = Regex("""^(?:bu|şu|this|that|these|those|dieser|diese|dieses|este|esta|ese|esa|ce|cet|cette|ces|هذا|هذه)\s""")

    /** Konuşmanın devamı olabilecek bağlaç başlangıçları ("..., dedi. Çünkü ..."). */
    private val CONT_START = Regex("""^(?:çünkü|ama|fakat|ve|yani|oysa|ancak|ki|hem|belki|şimdi|because|but|and|so|yet|denn|aber|und|pero|porque|mais|car)(?![\p{L}])""")

    /** Anahtar kelime olamayacak ünlem/zarf/zamir/dolgu sözcükleri (bağlamdan bağımsız). */
    private val KW_JUNK = setOf(
        "öyle", "biraz", "yine", "kendime", "kendine", "kendini", "kendimi", "kendisi", "kendi", "günaydın", "merhaba", "evet", "hayır",
        "belki", "şimdi", "sonra", "önce", "hep", "hiç", "çok", "daha", "artık", "bile", "sadece", "zaten", "galiba", "tabii", "tamam",
        "nasıl", "neden", "niçin", "böyle", "şöyle", "öylece", "herkes", "hiçbir", "bazen", "hemen", "tekrar", "yalnız", "gerçekten",
        "aslında", "yani", "şey", "falan", "işte", "pekala", "peki", "öyleyse", "belki", "henüz", "hala", "hâlâ", "sanki", "bence",
        "well", "yes", "very", "again", "hello", "goodbye", "maybe", "perhaps", "always", "never", "still", "even", "just", "only",
        "really", "quite", "myself", "himself", "herself", "yourself", "itself", "themselves", "okay", "something", "nothing", "everything"
    )

    // ------------------------------------------------------------------ giris noktasi

    /** Dış giriş: beklenmeyen bir hata olursa uygulama çökmez; en azından metnin önemli cümlelerini döndüren güvenli yola düşer. */
    fun summarize(raw: String, requested: Mode, length: Length): Result {
        return try {
            summarizeCore(raw, requested, length)
        } catch (e: Exception) {
            fallback(raw, length, e)
        }
    }

    private fun fallback(raw: String, length: Length, e: Exception): Result {
        val wc = raw.split(WS).count { it.isNotEmpty() }
        val parts = raw.replace('\n', ' ').split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.length >= 25 }
        val k = when (length) { Length.SHORT -> 4; Length.MEDIUM -> 8; Length.LONG -> 14 }.coerceAtMost(parts.size)
        val step = if (k == 0) 1 else max(1, parts.size / k)
        val pick = parts.filterIndexed { i, _ -> i % step == 0 }.take(k)
        val sec = if (pick.isEmpty()) emptyList() else listOf(Section(null, pick, SectionKind.BULLETS, SecTitle.NOTES))
        return Result(Mode.EXAM, sec, emptyList(), parts.size, wc, "und", qualityNotes = listOf("fallback:" + (e.message ?: e.javaClass.simpleName)))
    }

    private fun summarizeCore(raw: String, requested: Mode, length: Length): Result {
        val wordCount = raw.split(WS).count { it.isNotEmpty() }
        val detected = Langs.detect(raw)
        val pack = detected ?: Langs.TR
        val srcLang = detected?.code ?: "und"
        val sents = parse(raw, pack)
        if (sents.isEmpty()) return Result(Mode.EXAM, emptyList(), emptyList(), 0, wordCount, srcLang)
        for (s in sents) analyze(s, pack)
        markMembers(sents, pack)
        val weights = computeBase(sents)
        val mode = if (requested == Mode.AUTO) detect(sents, pack) else requested
        val cands = keywordCandidates(sents, weights, pack)
        return if (mode == Mode.STORY) {
            val sec = storySections(sents, length, pack, weights)
            val chosen = HashSet<Sent>(sec.chosen)
            Result(mode, sec.sections, finalKeywords(storyCands(cands, sec, pack), chosen, sents, pack), sents.size, wordCount, srcLang, qualityNotes = sec.notes)
        } else {
            val ex = examSections(sents, length, pack, cands, weights)
            Result(
                mode, ex.sections, finalKeywords(cands, ex.chosen, sents, pack), sents.size, wordCount, srcLang,
                qualityNotes = ex.notes
            )
        }
    }

    // ------------------------------------------------------------------ ayristirma

    private fun parse(raw: String, pack: LangPack): List<Sent> {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace("\u00AD", "").replace('\u00A0', ' ').replace('\t', ' ')
        text = text.replace(JUNK_CHARS, "")
        // Ayrışık aksan/nokta artıkları (g+breve, s+cedilla, i+nokta...) tek karaktere birleştirilir
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replace("i\u0307", "i")
        for ((k, v) in LIGATURES) text = text.replace(k, v)
        text = text.replace(HYPHEN_BREAK, "\$1\$2")
        val rawLines = joinDropCaps(text.split('\n').map { fixLine(it.trim().replace(MULTI_SPACE, " ")) })

        val repThr = if (rawLines.size > 200) 3 else 4
        val freq = HashMap<String, Int>()
        for (l in rawLines) if (l.length in 6..80) freq[l] = (freq[l] ?: 0) + 1
        val lines = rawLines.filter { l ->
            val pageNum = PAGE_NUM.matches(l) || PAGE_LABEL.matches(l)
            val repeated = l.length in 6..80 && (freq[l] ?: 0) >= repThr && !isDialogueStart(l) && !isBullet(l) && !isTableRow(l)
            !(pageNum || repeated)
        }

        val out = ArrayList<Sent>()
        var heading: String? = null
        var para = 0
        val buf = StringBuilder()

        fun addUnit(unit: String, bullet: Boolean, dialogue: Boolean) {
            val parts = if (bullet) listOf(unit) else splitSentences(unit, pack)
            var first = true
            for (p in parts) {
                val t = p.trim()
                val words = t.count { it == ' ' } + 1
                val minLen = if (bullet) 12 else if (t.any { it.isDigit() }) 14 else 25
                if (t.length < minLen || words < (if (bullet) 2 else 3)) continue
                val st = stems(t, pack)
                if (st.size < 2) continue
                out.add(Sent(out.size, t, st, st.toHashSet(), heading, bullet, dialogue, first, pack.lower(t), para))
                first = false
            }
        }

        fun flush() {
            if (buf.isNotEmpty()) {
                addUnit(buf.toString(), false, false)
                buf.setLength(0)
            }
        }

        var prevBlank = true
        var softBreak = false
        for (line in lines) {
            if (line.isEmpty()) {
                // Cümle bitmeden gelen tek boş satır (PDF sayfa sonu) hemen paragraf bitirmez
                if (buf.isNotEmpty() && !softBreak && buf.last() !in SENT_END) {
                    softBreak = true
                } else {
                    flush(); softBreak = false; para++
                }
                prevBlank = true
                continue
            }
            if (softBreak) {
                softBreak = false
                val cont = line[0].isLowerCase() && !isBullet(line) && !isDialogueStart(line) && !isTableRow(line)
                if (cont) {
                    prevBlank = false
                } else {
                    flush(); para++
                }
            }
            if (line.startsWith("# ")) {
                flush(); heading = cleanHeading(line.substring(2), pack); para++; prevBlank = false; continue
            }
            val canStart = prevBlank || buf.isEmpty() || buf.last() in SENT_END
            when {
                isDialogueStart(line) -> { flush(); addUnit(line, false, true) }
                isTableRow(line) -> { flush(); addUnit(tableRowText(line), true, false) }
                isBullet(line) -> { flush(); addUnit(line.replaceFirst(BULLET_MARK, ""), true, false) }
                isHeading(line, canStart, pack, heading != null) -> { flush(); heading = cleanHeading(line, pack); para++ }
                else -> {
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(line)
                }
            }
            prevBlank = false
        }
        flush()
        return out
    }

    private val SPACE_BEFORE_PUNCT = Regex("(?<=\\p{L}) +([,.])(?=\\s|$)")
    private val MISSING_SPACE_COMMA = Regex("(?<=\\p{L}{2})([,;!])(?=\\p{L})")
    private val MISSING_SPACE_DOT = Regex("(?<=\\p{Ll}{2})(?<!www)\\.(?=\\p{Lu}\\p{Ll})")

    /** PDF kaynaklı noktalama hataları: "sonra ," -> "sonra,"; "dedi.Sonra" -> "dedi. Sonra"; "Ünlü,Ahmet" -> "Ünlü, Ahmet". */
    private fun fixLine(l: String): String {
        if (l.length < 4) return l
        var t = SPACE_BEFORE_PUNCT.replace(l, "\$1")
        t = MISSING_SPACE_COMMA.replace(t, "\$1 ")
        t = MISSING_SPACE_DOT.replace(t, ". ")
        return t
    }

    /** Büyük harfli ilk harfin (drop cap) ayrı satıra düşmesi: "B" + "ir zamanlar" -> "Bir zamanlar". */
    private fun joinDropCaps(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.length == 1 && l[0].isUpperCase() && i + 1 < lines.size && lines[i + 1].isNotEmpty() && lines[i + 1][0].isLowerCase()) {
                out.add(l + lines[i + 1]); i += 2
            } else { out.add(l); i++ }
        }
        return out
    }

    private fun isTableRow(line: String): Boolean = line.count { it == '|' } >= 2

    private fun tableRowText(line: String): String =
        line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")

    private fun isDialogueStart(line: String): Boolean {
        if (line.startsWith("—") || line.startsWith("–") || line.startsWith("“") ||
            line.startsWith("\"") || line.startsWith("«")
        ) return true
        if (line.startsWith("- ") && (line.contains('?') || line.contains('!'))) return true
        return false
    }

    private fun isBullet(line: String): Boolean {
        if (!BULLET.matches(line)) return false
        val rest = line.replaceFirst(BULLET_MARK, "")
        val marker = line.substring(0, line.length - rest.length).trim()
        val enumerator = marker.isNotEmpty() && (marker[0].isLetterOrDigit())
        if (enumerator) {
            return rest.length >= 30 || (rest.isNotEmpty() && rest.last() in ".!?;")
        }
        return true
    }

    private fun isHeading(line: String, canStart: Boolean, pack: LangPack, inSection: Boolean = false): Boolean {
        if (line.length > 80) return false
        val words = line.split(' ').size
        val last = line.last()
        if (NUMBERED_HEAD.containsMatchIn(line) && words <= 10 && last !in ".!?") return true
        val letters = line.filter { it.isLetter() }
        if (letters.length >= 4 && words <= 10 && letters.all { it.isUpperCase() }) return true
        if (last == ':' && words <= (if (inSection) 5 else 8)) return true
        val startsOk = line[0].isUpperCase() || (!pack.hasCase && line[0].isLetter())
        if (canStart && words <= 6 && startsOk && last !in ".!?,;:؟،" && !line.contains(',') && !line.contains('،')) return true
        return false
    }

    private fun cleanHeading(h: String, pack: LangPack): String {
        var t = h.trim().trimEnd(':').trim()
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            t = t.lowercase(pack.locale).split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(pack.locale) } }
        }
        return if (t.length > 80) t.substring(0, 80) else t
    }

    private fun splitSentences(p: String, pack: LangPack): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = p.length
        while (i < n) {
            val c = p[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '؟') {
                var j = i + 1
                while (j < n && (p[j] == '.' || p[j] == '!' || p[j] == '?' || p[j] == '”' || p[j] == '"' ||
                        p[j] == '\'' || p[j] == ')' || p[j] == '»' || p[j] == '’' || p[j] == '؟')
                ) j++
                var ok = false
                if (j >= n) {
                    ok = true
                } else if (p[j].isWhitespace()) {
                    var k = j
                    while (k < n && p[k].isWhitespace()) k++
                    if (k >= n) {
                        ok = true
                    } else {
                        val nx = p[k]
                        ok = nx.isUpperCase() || nx.isDigit() || nx == '"' || nx == '“' || nx == '«' ||
                            nx == '(' || nx == '—' || nx == '–' || nx == '-' || (!pack.hasCase && nx.isLetter())
                    }
                }
                if (ok && c == '.') {
                    if (i > 0 && p[i - 1].isDigit()) {
                        var d = i - 1
                        while (d >= 0 && p[d].isDigit()) d--
                        if (i - 1 - d <= 2 && (d < 0 || p[d].isWhitespace())) ok = false
                    } else {
                        var w = i - 1
                        while (w >= 0 && (p[w].isLetter() || p[w] == '.')) w--
                        val word = p.substring(w + 1, i).lowercase(pack.locale)
                        if ((word.length == 1 && pack.hasCase) || word in pack.abbr) ok = false
                    }
                }
                if (ok) {
                    val s = p.substring(start, j).trim()
                    if (s.isNotEmpty()) out.add(s)
                    start = j
                    i = j
                    continue
                }
            }
            i++
        }
        if (start < n) {
            val s = p.substring(start).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun stems(text: String, pack: LangPack): List<String> {
        val res = ArrayList<String>()
        for (m in WORD.findAll(pack.lower(text))) {
            val w = m.value
            if (w.length < 3 || w in pack.stop) continue
            res.add(pack.stem(w))
        }
        return res
    }

    // ------------------------------------------------------------------ ozellik cikarma

    /** Cümledeki tarih, sayı, yüzde, formül, kurum/kişi adı, kısaltma ve tanım/sınıflandırma/neden-sonuç/karşılaştırma/istisna ipuçları. */
    private fun analyze(s: Sent, pack: LangPack) {
        val f = HashSet<String>()
        val pct = PCT.containsMatchIn(s.lower)
        var anyNum = false
        for (m in NUM.findAll(s.text)) {
            val d = m.value.replace(".", "").replace(",", "")
            val unit = d.length == 1 && UNIT_AFTER.containsMatchIn(s.text.substring(m.range.last + 1, min(s.text.length, m.range.last + 12)))
            if (d.length >= 2 || pct || unit) {
                f.add("n:$d")
                anyNum = true
            }
        }
        val year = YEAR.containsMatchIn(s.text)
        s.hasNum = anyNum
        s.hasPct = pct && anyNum
        s.hasDate = year || (anyNum && pack.months.containsMatchIn(s.lower))
        val fm = FORMULA.find(s.text)
        s.hasFormula = fm != null
        if (fm != null) f.add("f:" + fm.value.replace(" ", ""))
        var acr = 0
        for (m in ACRO.findAll(s.text)) {
            if (m.value.length <= 8) { f.add("a:" + m.value); acr++ }
        }
        var ents = 0
        if (pack.hasCase && pack.code != "de") {
            val toks = s.text.split(' ')
            var prevEnd = true
            for ((i, t0) in toks.withIndex()) {
                val t = t0.trim { !it.isLetterOrDigit() }
                if (i > 0 && !prevEnd && t.length >= 3 && t[0].isUpperCase() && t.drop(1).any { it.isLowerCase() } &&
                    pack.lower(t) !in pack.stop
                ) {
                    f.add("e:" + pack.lower(t))
                    ents++
                }
                prevEnd = t0.isNotEmpty() && t0.last() in ".!?…:\"”»"
            }
        }
        s.ents = ents + acr
        s.org = ents > 0 && pack.inst.containsMatchIn(s.lower)
        s.facts = f
        s.isDef = pack.def.containsMatchIn(s.lower) || (pack.code == "tr" && TR_COPULA.matches(s.text))
        s.isClass = pack.classCue.containsMatchIn(s.lower)
        s.isCause = pack.cause.containsMatchIn(s.lower)
        s.isCompare = pack.compare.containsMatchIn(s.lower)
        s.isExample = pack.example.containsMatchIn(s.lower)
        s.isConc = pack.conclusion.containsMatchIn(s.lower)
        s.isExc = EXC.containsMatchIn(s.lower)
        s.isWarn = WARN.containsMatchIn(s.lower)
        s.isUrgent = URGENT.containsMatchIn(s.lower)
        s.isDeadline = DEADLINE.containsMatchIn(s.lower)
        if (!s.isClass && pack.code == "tr" && TR_CLASS2.containsMatchIn(s.lower)) s.isClass = true
    }

    private fun announced(s: Sent, pack: LangPack): Int {
        for (m in WORD.findAll(s.lower)) {
            pack.numWords[m.value]?.let { return it }
            if (pack.code == "tr") {
                val g = TR_NUM.find(m.value)?.groupValues?.get(1)
                if (g != null) pack.numWords[g]?.let { return it }
            }
        }
        val d = Regex("(?<![\\d.,])([2-8])(?![\\d.,%])").find(s.text)?.value?.toIntOrNull()
        return d ?: 0
    }

    /** "Profazda ...", "Metafazda ..." gibi aynı ekle başlayan paralel cümlelerin ortak eki (yoksa null). */
    private fun parTail(s: Sent): String? {
        val w = s.text.substringBefore(' ').trim { !it.isLetter() }.lowercase(Locale.ROOT)
        if (w.length < 5 || !w.all { it.isLetter() }) return null
        val t = w.takeLast(2)
        return if (t == "da" || t == "de" || t == "ta" || t == "te") t else null
    }

    /** Sınıflandırma duyuran cümlelerin (ör. "üç türü vardır") üyelerini ve madde listelerini işaretler. */
    private fun markMembers(sents: List<Sent>, pack: LangPack) {
        val n = sents.size
        var runId = -1
        var prev: Sent? = null
        for (s in sents) {
            if (s.bullet) {
                if (prev == null || !prev.bullet || prev.heading != s.heading) runId++
                s.run = runId
                s.member = true
            }
            prev = s
        }
        if (pack.code == "tr") {
            var i0 = 0
            while (i0 < n) {
                val a = sents[i0]
                val tail = if (!a.bullet && !a.dialogue && !a.isClass) parTail(a) else null
                if (tail == null) { i0++; continue }
                var j0 = i0 + 1
                while (j0 < n && sents[j0].para == a.para && sents[j0].heading == a.heading &&
                    !sents[j0].bullet && !sents[j0].dialogue && parTail(sents[j0]) == tail
                ) j0++
                if (j0 - i0 >= 3) {
                    runId++
                    for (k in i0 until j0) { sents[k].run = runId; sents[k].member = true }
                }
                i0 = j0
            }
        }
        for (i in 0 until n) {
            val h = sents[i]
            if (!h.isClass || h.bullet) continue
            val ann = announced(h, pack)
            val cap = if (ann in 2..8) ann + 1 else 8
            var found = 0
            var j = i + 1
            while (j < n && j - i <= 14 && found < cap && sents[j].heading == h.heading) {
                val t = sents[j]
                if (t.bullet) {
                    t.member = true; found++
                } else if (!pack.anaphora.containsMatchIn(t.lower)) {
                    val first = t.stems.firstOrNull()
                    var shared = 0
                    for (x in t.stemSet) if (x in h.stemSet) shared++
                    val near = j - i <= 4 && t.para == h.para
                    if (!t.isClass && ((first != null && first in h.stemSet) || (near && shared >= 2) || (ann in 2..8 && near))) {
                        t.member = true; t.head = h.idx; found++
                    }
                }
                j++
            }
        }
    }

    // ------------------------------------------------------------------ puanlama

    private fun computeBase(sents: List<Sent>): Map<String, Double> {
        val n = sents.size
        val tf = HashMap<String, Int>()
        val df = HashMap<String, Int>()
        for (s in sents) {
            for (t in s.stems) tf[t] = (tf[t] ?: 0) + 1
            for (t in s.stemSet) df[t] = (df[t] ?: 0) + 1
        }
        val w = HashMap<String, Double>()
        for ((t, c) in tf) {
            val d = df[t] ?: 1
            w[t] = ln(1.0 + c) * ln(1.0 + n.toDouble() / d) * (min(c, 3) / 3.0)
        }
        var mx = 1e-9
        for (s in sents) {
            var sum = 0.0
            for (t in s.stemSet) sum += w[t] ?: 0.0
            val size = max(1, s.stemSet.size)
            var v = sum / size.toDouble().pow(0.75)
            if (s.words > 40) v *= 0.7
            if (s.words < 6) v *= 0.5
            s.base = v
            if (v > mx) mx = v
        }
        for (s in sents) s.base /= mx
        textRank(sents)
        return w
    }

    private fun textRank(sents: List<Sent>) {
        val cand = sents.sortedByDescending { it.base }.take(400)
        val m = cand.size
        if (m < 3) {
            for (s in cand) s.rank = s.base
            return
        }
        val sim = Array(m) { DoubleArray(m) }
        for (i in 0 until m) {
            for (j in i + 1 until m) {
                val a = cand[i].stemSet
                val b = cand[j].stemSet
                if (a.isEmpty() || b.isEmpty()) continue
                val small = if (a.size <= b.size) a else b
                val large = if (a.size <= b.size) b else a
                var common = 0
                for (t in small) if (t in large) common++
                if (common == 0) continue
                val v = common / (ln(a.size + 1.0) + ln(b.size + 1.0))
                sim[i][j] = v
                sim[j][i] = v
            }
        }
        val rowSum = DoubleArray(m) { i -> sim[i].sum() }
        var r = DoubleArray(m) { 1.0 / m }
        repeat(30) {
            val nr = DoubleArray(m)
            for (i in 0 until m) {
                var acc = 0.0
                for (j in 0 until m) {
                    if (sim[j][i] > 0 && rowSum[j] > 0) acc += sim[j][i] / rowSum[j] * r[j]
                }
                nr[i] = 0.15 / m + 0.85 * acc
            }
            r = nr
        }
        val mx = max(r.maxOrNull() ?: 1e-9, 1e-9)
        for (i in 0 until m) cand[i].rank = r[i] / mx
    }

    /** Çok uzun belgelerde yalnızca baştan değil, belgenin tamamından eşit aralıklı örnekleme yapar. */
    private fun <T> evenly(list: List<T>, k: Int): List<T> {
        if (list.size <= k) return list
        val step = list.size.toDouble() / k
        return List(k) { list[(it * step).toInt()] }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var c = 0
        for (t in a) if (t in b) c++
        return c.toDouble() / (a.size + b.size - c)
    }

    /** Tekrar: çok benzer cümleyi eler; ama farklı bir tarih/sayı/isim/madde taşıyan cümleyi ASLA silmez. */
    private fun redundant(s: Sent, chosen: Collection<Sent>): Boolean {
        for (c in chosen) {
            val j = jaccard(c.stemSet, s.stemSet)
            if (j > 0.85) return true
            if (j > 0.6 && !s.member && s.facts.all { it in c.facts }) return true
        }
        return false
    }

    private fun cutPoint(t: String, limit: Int): Int {
        if (t.length <= limit) return t.length
        var cut = max(t.lastIndexOf("; ", limit), t.lastIndexOf(", ", limit))
        if (cut < limit * 0.6) cut = t.lastIndexOf(' ', limit - 3)
        if (cut < limit * 0.5) cut = limit - 3
        return cut
    }

    /** Uzun cümleyi kısaltır; ama kısaltılan kuyrukta sayı/tarih/formül varsa kesmez (bilgi kaybı önlenir). */
    private fun display(s: Sent): String {
        val t = s.text.replace(WS, " ").trim()
        val limit = if (s.isClass || s.text.contains(':')) 800 else if (s.member || s.facts.size >= 3) 560 else 400
        if (t.length <= limit) return t
        var cut = cutPoint(t, limit)
        if (t.substring(cut).any { it.isDigit() } || FORMULA.containsMatchIn(t.substring(cut))) {
            cut = cutPoint(t, 900)
        }
        if (cut >= t.length) return t
        return t.substring(0, cut).trimEnd(',', ';', ' ') + "…"
    }

    // ------------------------------------------------------------------ tur algilama

    private fun detect(sents: List<Sent>, pack: LangPack): Mode {
        val n = sents.size.toDouble()
        val bullet = sents.count { it.bullet } / n
        val dialog = sents.count { it.dialogue } / n
        val head = sents.mapNotNull { it.heading }.toSet().size / n
        val def = sents.count { it.isDef } / n
        val digit = sents.count { s -> s.text.any { ch -> ch.isDigit() } } / n
        var words = 0
        var past = 0
        for (s in evenly(sents, 3000)) {
            for (m in WORD.findAll(s.lower)) {
                words++
                if (m.value.length >= 3 && pack.isPast(m.value)) past++
            }
        }
        val pastRate = if (words == 0) 0.0 else past.toDouble() / words
        // Ek kanıtlar: konuşma fiili ("dedi", "said"...), tekrar eden özel adlar (karakter), formül ve tarih yoğunluğu.
        val speech = sents.count { SPEECH.containsMatchIn(it.lower) } / n
        val formula = sents.count { it.hasFormula } / n
        val dated = sents.count { it.hasDate } / n
        val nf = HashMap<String, Int>()
        for (s in sents) for (m in NAME.findAll(s.text)) { val k = m.value.lowercase(pack.locale); nf[k] = (nf[k] ?: 0) + 1 }
        val recurring = nf.filter { it.value >= 4 }.keys
        val named = if (recurring.isEmpty()) 0.0
        else sents.count { s -> NAME.findAll(s.text).any { it.value.lowercase(pack.locale) in recurring } } / n
        val exam = 5 * def + 2.5 * bullet + 3 * head + 1.5 * digit + 2.5 * formula + 2 * dated
        val story = 4 * dialog + 7 * pastRate + 4 * speech + 1.5 * named
        // Diyalog içeren, tanım/madde/formül içermeyen metin ders notu sayılmaz.
        if (dialog >= 0.2 && speech >= 0.05 && def <= 0.1 && bullet <= 0.05 && formula <= 0.02) return Mode.STORY
        return if (story >= exam && story > 0.6) Mode.STORY else Mode.EXAM
    }

    // ------------------------------------------------------------------ hedef uzunluk

    /** Metnin uzunluğuna ve bilgi yoğunluğuna göre özet cümle sayısı: kısa metni uzatmaz, uzun metni aşırı kısaltmaz. */
    private fun targetCount(sents: List<Sent>, length: Length): Int {
        val n = sents.size
        val dense = sents.count { it.facts.isNotEmpty() || it.isDef || it.isClass || it.isCause || it.isCompare }.toDouble() / n
        val cfg = when (length) {
            Length.SHORT -> doubleArrayOf(0.07, 4.0, 18.0, 0.25)
            Length.MEDIUM -> doubleArrayOf(0.13, 6.0, 36.0, 0.40)
            Length.LONG -> doubleArrayOf(0.22, 12.0, 70.0, 0.55)
        }
        // Kısa/orta notlarda (öğrenci notu) oran yüksek, çok uzun metinlerde düşük; bilgi yoğunsa daha fazla cümle korunur.
        val small = 1.0 + 1.2 * max(0.0, (60.0 - n) / 60.0)
        var t = n * cfg[0] * small * (0.7 + 1.6 * dense)
        // Tarih ağırlıklı (zaman çizelgesi gibi) metinlerde olayların önemli kısmı korunur.
        val dated = sents.count { it.hasDate }
        if (n >= 8 && dated.toDouble() / n >= 0.35) {
            val f = when (length) { Length.SHORT -> 0.30; Length.MEDIUM -> 0.45; Length.LONG -> 0.65 }
            t = max(t, dated * f)
        }
        val lo = min(cfg[1].toInt(), ceil(n * cfg[3]).toInt()).coerceAtLeast(1)
        var capBase = (cfg[2] * (1 + 0.5 * max(0.0, ln(n / 300.0)))).roundToInt()
        // Kitap boyutundaki metinlerde (binlerce cümle) Orta/Uzun özet de buna göre uzar.
        if (n >= 1500) {
            when (length) {
                Length.LONG -> capBase = max(capBase, min(600, (n * 0.035).roundToInt()))
                Length.MEDIUM -> capBase = max(capBase, min(300, (n * 0.015).roundToInt()))
                Length.SHORT -> {}
            }
        }
        val hi = max(lo, min(capBase, ceil(n * 0.7).toInt()))
        return t.roundToInt().coerceIn(lo, hi).coerceAtMost(n)
    }

    private fun wordRatio(length: Length): Double = when (length) {
        Length.SHORT -> 0.12
        Length.MEDIUM -> 0.22
        Length.LONG -> 0.35
    }

    // ------------------------------------------------------------------ bolum / konu gruplari

    private class Grp(val title: String?, val skey: SecTitle) {
        val members = ArrayList<Sent>()
    }

    private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
        var dot = 0.0
        for ((k, v) in a) { val o = b[k]; if (o != null) dot += v.toDouble() * o }
        var na = 0.0
        for (v in a.values) na += v.toDouble() * v
        var nb = 0.0
        for (v in b.values) nb += v.toDouble() * v
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /** Başlığı olmayan uzun metinlerde konu geçişlerini (kelime örtüşmesinin düştüğü, paragraf başı olan yerleri) bulur. */
    private fun segmentStarts(sents: List<Sent>): List<Int> {
        val n = sents.size
        val w = (n / 20).coerceIn(4, 10)
        if (n < 2 * w + 2) return emptyList()
        val sims = DoubleArray(n) { Double.NaN }
        fun block(from: Int, to: Int): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (i in from until to) for (t in sents[i].stemSet) m[t] = (m[t] ?: 0) + 1
            return m
        }
        for (b in w..(n - w)) sims[b] = cosine(block(b - w, b), block(b, b + w))
        val valid = (w..(n - w)).map { sims[it] }
        val mean = valid.average()
        val sd = sqrt(valid.sumOf { (it - mean) * (it - mean) } / valid.size)
        val cands = ArrayList<Int>()
        for (b in w..(n - w)) {
            val v = sims[b]
            val l = if (b - 1 >= w) sims[b - 1] else Double.MAX_VALUE
            val r = if (b + 1 <= n - w) sims[b + 1] else Double.MAX_VALUE
            if (v < mean - 0.5 * sd && v <= l && v <= r && sents[b].paraStart && sents[b].para != sents[b - 1].para) cands.add(b)
        }
        val maxSeg = (n / 25).coerceIn(2, 6)
        val picked = ArrayList<Int>()
        for (b in cands.sortedBy { sims[it] }) {
            if (picked.size >= maxSeg - 1) break
            if (picked.any { abs(it - b) < w * 1.5 }) continue
            picked.add(b)
        }
        picked.sort()
        return picked
    }

    private fun buildGroups(sents: List<Sent>, cands: List<Kw>): List<Grp> {
        val heads = sents.mapNotNull { it.heading }.toSet()
        val groups = ArrayList<Grp>()
        if (heads.size >= 2) {
            val byHead = LinkedHashMap<String?, Grp>()
            for (s in sents) {
                val g = byHead.getOrPut(s.heading) {
                    if (s.heading == null) Grp(null, SecTitle.GENERAL) else Grp(s.heading, SecTitle.CUSTOM)
                }
                g.members.add(s)
            }
            groups.addAll(byHead.values)
        } else {
            val starts = if (sents.size >= 24) segmentStarts(sents) else emptyList()
            if (starts.isEmpty()) {
                val g = Grp(null, SecTitle.NOTES)
                g.members.addAll(sents)
                groups.add(g)
            } else {
                val bounds = listOf(0) + starts + listOf(sents.size)
                val used = HashSet<String>()
                for (k in 0 until bounds.size - 1) {
                    val part = sents.subList(bounds[k], bounds[k + 1])
                    var bestKw: Kw? = null
                    var bestV = 0.0
                    for (kw in cands) {
                        if (kw.surface in used) continue
                        val cnt = part.count { s -> kw.stems.all { it in s.stemSet } }
                        val v = cnt * kw.score * (if (kw.phrase) 1.3 else 1.0)
                        if (cnt >= 2 && v > bestV) { bestV = v; bestKw = kw }
                    }
                    val g = if (bestKw != null) { used.add(bestKw.surface); Grp(bestKw.surface, SecTitle.CUSTOM) } else Grp(null, SecTitle.NOTES)
                    g.members.addAll(part)
                    groups.add(g)
                }
            }
        }
        return groups
    }

    // ------------------------------------------------------------------ sinav / ders notu

    private fun examSections(sents: List<Sent>, length: Length, pack: LangPack, cands: List<Kw>, weights: Map<String, Double>): ExamOut {
        val n = sents.size
        val timeline = sents.size >= 8 && sents.count { it.hasDate }.toDouble() / sents.size >= 0.35
        for (s in sents) {
            var v = 0.45 * s.base + 0.45 * s.rank
            if (s.isDef) v += 0.28
            if (s.isClass) v += 0.22
            if (s.member) v += 0.12
            if (s.isCause) v += 0.12
            if (s.isCompare) v += 0.12
            if (s.isConc) v += 0.22
            if (s.isExc) v += 0.10
            if (s.isWarn) v += 0.12
            if (s.isUrgent) v += 0.30
            if (s.isDeadline) v += 0.22
            if (s.hasFormula) v += 0.14
            if (s.isExample && s.facts.isNotEmpty()) v += 0.05 else if (s.isExample) v -= 0.05
            v += 0.05 * min(s.facts.size, 4)
            if (s.hasDate) v += if (timeline) 0.24 else 0.08
            if (timeline && s.hasDate && s.ents > 0) v += 0.06
            if (s.hasPct) v += 0.08
            if (s.org) v += 0.05
            if (s.bullet) v += 0.12
            if (s.paraStart) v += 0.06
            if (s.text.contains(':')) v += 0.04
            if (s.idx == 0) v += 0.16
            if (s.heading != null && s.paraStart) v += 0.16
            if (s.idx >= n - 3 && (s.isConc || s.hasFormula || s.hasDate)) v += 0.16
            if (s.dialogue) v -= 0.1
            s.score = v
        }
        val target = targetCount(sents, length)
        val avgWords = sents.sumOf { it.words }.toDouble() / n
        val wordBudget = max(80, max((sents.sumOf { it.words } * wordRatio(length)).toInt(), (target * avgWords * 1.2).toInt()))
        val groups = buildGroups(sents, cands)
        for ((gi, g) in groups.withIndex()) for (s in g.members) s.seg = gi
        val chosen = LinkedHashSet<Sent>()
        val notes = ArrayList<String>()
        var words = 0
        fun add(s: Sent): Boolean {
            if (chosen.add(s)) { words += s.words; return true }
            return false
        }

        // Ön hesap: sınıflandırma üyeleri ve madde listeleri (büyük belgelerde hızlı erişim için)
        val byHead = HashMap<Int, MutableList<Sent>>()
        val byRun = HashMap<Int, MutableList<Sent>>()
        for (s in sents) {
            if (s.head >= 0) byHead.getOrPut(s.head) { ArrayList() }.add(s)
            if (s.run >= 0) byRun.getOrPut(s.run) { ArrayList() }.add(s)
        }

        val headStemSets = HashMap<String, Set<String>>()
        fun headBonus(g: Grp, s: Sent): Double {
            val t = g.title ?: return 0.0
            val hs = headStemSets.getOrPut(t) { stems(t, pack).toHashSet() }
            if (hs.isEmpty()) return 0.0
            val share = hs.count { it in s.stemSet }.toDouble() / hs.size
            return 0.15 * share + (if (s.isDef && share > 0.0) 0.12 else 0.0)
        }

        // 1) Her başlığın / konunun altında en az bir açıklayıcı cümle
        val maxGroups = max(2, (target * 1.3).toInt())
        val orderedGroups = groups.sortedByDescending { g -> g.members.sumOf { it.score } }.take(maxGroups)
        if (groups.size > 1) {
            for (g in orderedGroups) {
                val best = g.members.maxByOrNull { it.score + (if (it.paraStart) 0.03 else 0.0) + headBonus(g, it) } ?: continue
                if (!redundant(best, chosen)) add(best)
            }
        }

        // 2) Ana kavramların tanımları
        var defAdded = 0
        for (kw in cands.take(6)) {
            if (defAdded >= max(2, target / 4)) break
            val d = sents.filter { it.isDef && !it.dialogue && kw.stems.all { st -> st in it.stemSet } }.maxByOrNull { it.score } ?: continue
            if (d !in chosen && !redundant(d, chosen)) { add(d); defAdded++ }
        }

        // 3) Kapsama tabanlı doldurma (tembel açgözlü seçim): her adımda, henüz özette olmayan ÖNEMLİ kavramları
        //    en çok kapsayan ve yapısal puanı yüksek cümle seçilir; böylece aynı bilgi tekrarlanmaz, farklı bilgiler kaybolmaz.
        run {
            val covered = HashSet<String>()
            for (c in chosen) covered.addAll(c.stemSet)
            fun gain(x: Sent): Double {
                var g = 0.0
                for (t in x.stemSet) if (t !in covered) g += weights[t] ?: 0.0
                return g / sqrt(max(1.0, x.stemSet.size.toDouble()))
            }
            var gmax = 1e-9
            for (x in sents) gmax = max(gmax, gain(x))
            val pq = java.util.PriorityQueue<Pair<Sent, Double>>(compareByDescending { it.second })
            for (x in sents) if (x !in chosen) pq.add(Pair(x, x.score + 0.8 * gain(x) / gmax))
            while (chosen.size < target && words < wordBudget && pq.isNotEmpty()) {
                val (x, old) = pq.poll()
                if (x in chosen) continue
                val v = x.score + 0.8 * gain(x) / gmax
                if (v < old - 1e-9) { pq.add(Pair(x, v)); continue }
                if (redundant(x, chosen)) continue
                add(x)
                covered.addAll(x.stemSet)
            }
        }

        // 3b) Sınıflandırmayı duyuran cümleler ("... iki gruba ayrılır") mutlaka özette olsun
        var headAdded = 0
        for (h in sents.filter { it.isClass && !it.bullet && !it.dialogue }.sortedByDescending { it.score }) {
            if (headAdded >= max(3, target / 3)) break
            if (h !in chosen && (announced(h, pack) > 0 || h.text.contains(':'))) { add(h); headAdded++ }
        }

        // 4) Bütünlük: sınıflandırma üyeleri ve madde listeleri eksik kalmasın
        val runCap = when (length) { Length.SHORT -> 4; Length.MEDIUM -> 6; Length.LONG -> 10 }
        for (round in 0 until 3) {
            var changed = false
            val snapshot = chosen.toList()
            for (s in snapshot) {
                if (s.isClass && !s.bullet) {
                    val lst = byHead[s.idx]
                    if (lst != null) for (t in lst.take(8)) if (add(t)) changed = true
                }
                if (s.head >= 0 && add(sents[s.head])) changed = true
                if (s.run >= 0) {
                    val mates: List<Sent> = byRun[s.run] ?: emptyList()
                    val keep = if (mates.size <= max(runCap, 8)) mates else (mates.filter { it in chosen } + mates.sortedByDescending { it.score }).distinct().take(runCap)
                    for (t in keep) if (add(t)) changed = true
                }
            }
            if (!changed) break
        }
        // 4b) Üyenin hemen ardından gelen "Bu aşama ..." gibi açıklama cümleleri
        run {
            var cap = max(2, target / 4)
            for (s in chosen.toList().sortedBy { it.idx }) {
                if (cap <= 0) break
                if ((s.head < 0 && !s.isDef) || s.idx + 1 >= n) continue
                val nx = sents[s.idx + 1]
                if (nx !in chosen && nx.para == s.para && nx.heading == s.heading && !nx.dialogue &&
                    (pack.anaphora.containsMatchIn(nx.lower) || DEMO_START.containsMatchIn(lead(nx))) && !nx.isClass
                ) { add(nx); cap-- }
            }
        }

        // 4c) Seçilen üye/sınıflandırma cümlesinde adı geçen kavramın kendi açıklama cümlesi (ör. "osmoz", "aktif taşıma")
        run {
            var cap = max(2, target / 3)
            val anchors = chosen.filter { it.member || it.isClass }
            if (anchors.isNotEmpty()) {
                for (u in sents) {
                    if (cap <= 0) break
                    if (u in chosen || u.dialogue || u.member || u.words < 6) continue
                    if (pack.anaphora.containsMatchIn(u.lower)) continue
                    val f = u.stems.firstOrNull() ?: continue
                    if (f.length < 4) continue
                    if (anchors.any { a -> a.heading == u.heading && a.idx != u.idx && f in a.stemSet } && !redundant(u, chosen)) {
                        add(u); cap--
                    }
                }
            }
        }

        // 5) Doğrulama: önemli tarih / sayı / yüzde / formül / isim kaybolmasın
        val sorted = sents.map { it.score }.sorted()
        val thr = sorted[(sorted.size * 0.6).toInt().coerceIn(0, sorted.size - 1)]
        val keyFacts = HashSet<String>()
        for (s in sents) if (s.hasDate || s.hasPct || s.hasFormula || s.hasNum || s.score >= thr) keyFacts.addAll(s.facts)
        if (keyFacts.isNotEmpty()) {
            val covered = HashSet<String>()
            for (s in chosen) covered.addAll(s.facts)
            val extraCap = max(2, target / 3)
            var extra = 0
            while (extra < extraCap) {
                var best: Sent? = null
                var bestV = 0.0
                for (s in sents) {
                    if (s in chosen || s.dialogue) continue
                    val gain = s.facts.count { it in keyFacts && it !in covered }
                    if (gain == 0) continue
                    val v = gain + s.score
                    if (v > bestV) { bestV = v; best = s }
                }
                if (best == null) break
                add(best); covered.addAll(best.facts); extra++
                if (keyFacts.count { it in covered }.toDouble() / keyFacts.size >= 0.85) break
            }
        }

        // 6) Kendi başına anlaşılabilirlik: "Bu / Ayrıca / However" ile başlayan cümlenin önceki cümlesi
        val ctxCap = max(3, target / 2)
        var ctx = 0
        for (s in chosen.toList().sortedBy { it.idx }) {
            if (ctx >= ctxCap) break
            val orphan = pack.code == "tr" && TR_ORPHAN.containsMatchIn(s.lower)
            val linkedStart = pack.anaphora.containsMatchIn(s.lower) || orphan || pack.contrastStart.containsMatchIn(lead(s)) ||
                RESULT_START.containsMatchIn(lead(s)) || DEMO_START.containsMatchIn(lead(s))
            if (s.idx > 0 && linkedStart) {
                val p = sents[s.idx - 1]
                if (p !in chosen && p.para == s.para && p.heading == s.heading && !p.dialogue) { add(p); ctx++ }
                if (orphan) {
                    // "Mitokondri gibi ...", "Telofazda ise ..." gibi cümleler: önceki paralel cümleler de gelsin
                    val tail = s.text.substringBefore(' ').takeLast(2).lowercase(pack.locale)
                    var k = s.idx - 2
                    var extra = 0
                    while (k >= 0 && extra < 3) {
                        val q = sents[k]
                        if (q.para != s.para || q.heading != s.heading || q.dialogue) break
                        val parallel = q.text.substringBefore(' ').takeLast(2).lowercase(pack.locale) == tail
                        if (!parallel && extra >= 1) break
                        if (q !in chosen) { add(q); ctx++ }
                        extra++; k--
                    }
                }
            }
        }

        // 6b) Koşul / istisna: seçilen cümlenin hemen ardından gelen istisna cümlesi ("... hariç", "unless ...") kaybolmasın
        run {
            var cap = max(2, target / 5)
            for (s in chosen.toList().sortedBy { it.idx }) {
                if (cap <= 0) break
                if (s.idx + 1 >= n) continue
                val nx = sents[s.idx + 1]
                if ((nx.isExc || nx.isWarn) && nx !in chosen && nx.para == s.para && nx.heading == s.heading && !nx.dialogue) {
                    add(nx); cap--
                    notes.add("istisna-eklendi:" + nx.idx)
                }
            }
        }

        // 7) Hiçbir başlık / konu içeriksiz kalmasın
        var emptyRepaired = 0
        if (groups.size > 1) {
            for (g in orderedGroups) {
                if (g.members.none { it in chosen }) {
                    val best = g.members.maxByOrNull { it.score } ?: continue
                    add(best)
                    emptyRepaired++
                }
            }
        }

        if (emptyRepaired > 0) notes.add("bos-baslik-onarildi=" + emptyRepaired)
        // 7b) Büyük bölümler orantısız şekilde boş kalmasın
        if (groups.size > 1) {
            val pool = if (groups.size <= max(8, target * 2)) groups else orderedGroups
            val share = target.toDouble() / n
            for (g in pool) {
                if (g.members.size < 3) continue
                val sz = g.members.size
                var floor = when {
                    sz >= 14 -> 5
                    sz >= 10 -> 4
                    sz >= 6 -> 3
                    sz >= 4 -> 2
                    else -> 1
                }
                if (length == Length.SHORT) floor = max(1, floor - 1)
                if (length == Length.LONG) floor += 1
                val want = min(8, max(floor, (sz * share * 0.9).roundToInt()))
                var have = g.members.count { it in chosen }
                if (have >= want) continue
                for (s in g.members.sortedByDescending { it.score }) {
                    if (have >= want) break
                    if (s in chosen || s.dialogue || redundant(s, chosen)) continue
                    add(s); have++
                }
            }
        }

        // 8) Sonuç cümlesi: metnin son bölümünde açık bir sonuç varsa özet onu taşısın
        if (n >= 6 && chosen.none { it.isConc }) {
            val tailStart = (n * 0.75).toInt()
            val concl = sents.drop(tailStart).filter { it.isConc && !it.dialogue }.maxByOrNull { it.score }
            if (concl != null && !redundant(concl, chosen)) {
                add(concl)
                notes.add("sonuc-eklendi:" + concl.idx)
            }
        }

        // 9) Uzun belgelerde ilk / orta / son bölüm dengeli temsil edilsin
        if (n >= 20) {
            val minThird = max(1, (target * 0.18).toInt())
            for (part in 0 until 3) {
                val from = part * n / 3
                val to = (part + 1) * n / 3
                var have = (from until to).count { sents[it] in chosen }
                if (have >= minThird) continue
                for (s in sents.subList(from, to).sortedByDescending { it.score }) {
                    if (have >= minThird) break
                    if (s in chosen || s.dialogue || redundant(s, chosen)) continue
                    add(s); have++
                }
                notes.add("denge-onarildi:bolum" + (part + 1))
            }
        }

        // 10) Son kalite kontrol: aynı bölümdeki neredeyse aynı cümleleri temizle, kapsam raporu çıkar
        run {
            val kept = ArrayList<Sent>()
            for (s in chosen.sortedBy { it.idx }) {
                val dup = !s.member && s.head < 0 && !s.isClass && kept.any { k ->
                    k.seg == s.seg && jaccard(k.stemSet, s.stemSet) > 0.9 && s.facts.all { it in k.facts }
                }
                if (dup) { chosen.remove(s); notes.add("tekrar-temizlendi:" + s.idx) } else kept.add(s)
            }
            val cov = HashSet<String>()
            for (s in chosen) cov.addAll(s.facts)
            if (keyFacts.isNotEmpty()) {
                notes.add("olgu-kapsami=%" + (100 * keyFacts.count { it in cov } / keyFacts.size))
            }
            for (h in chosen) {
                if (!h.isClass || h.bullet) continue
                val ann = announced(h, pack)
                if (ann !in 2..8) continue
                val have = (byHead[h.idx]?.count { it in chosen } ?: 0) + sents.count { it.idx > h.idx && it.idx <= h.idx + 14 && it.bullet && it.heading == h.heading && it in chosen }
                if (have in 1 until ann) notes.add("liste-eksik-olabilir:" + h.idx + "(" + have + "/" + ann + ")")
            }
            notes.add("cumle=" + chosen.size + "/" + n)
        }

        val byGroup = LinkedHashMap<Int, MutableList<Sent>>()
        for (s in chosen.sortedBy { it.idx }) byGroup.getOrPut(s.seg) { ArrayList() }.add(s)
        val sections = byGroup.map { (gi, list) ->
            val g = groups[gi]
            Section(g.title, mergeRuns(list, pack), SectionKind.BULLETS, g.skey)
        }
        return ExamOut(sections, chosen, notes)
    }

    // ------------------------------------------------------------------ hikaye / kitap

    /** Aynı paragraftan ardışık seçilen (en çok 3) düz cümleleri tek maddede birleştirir: not kopuk değil, akıcı okunur. Sınıf/üye/madde cümlelerine dokunmaz. */
    private fun mergeRuns(list: List<Sent>, pack: LangPack): List<String> {
        val out = ArrayList<String>()
        fun plain(x: Sent) = !x.bullet && !x.member && !x.isClass && !x.dialogue && x.head < 0
        var i = 0
        while (i < list.size) {
            val a = list[i]
            val sb = StringBuilder(display(a))
            var j = i
            if (plain(a)) {
                while (j + 1 < list.size && j - i < 2) {
                    val p = list[j]
                    val b = list[j + 1]
                    if (b.idx == p.idx + 1 && b.para == p.para && b.heading == p.heading && plain(b) && sb.length + b.text.length < 700 &&
                        !isFragment(p, pack) && !isFragment(b, pack)) {
                        sb.append(' ').append(display(b)); j++
                    } else break
                }
            }
            out.add(sb.toString())
            i = j + 1
        }
        return out
    }


    private fun bump(pos: Double, center: Double, width: Double): Double {
        val x = (pos - center) / width
        return exp(-x * x)
    }

    private fun isFuncWord(t: String, pack: LangPack): Boolean {
        val x = "$t "
        return pack.contrastStart.containsMatchIn(x) || pack.anaphora.containsMatchIn(x) || PRON_START.containsMatchIn(x) ||
            RESULT_START.containsMatchIn(x) || pack.numWords.containsKey(t)
    }

    /**
     * Karakter izleme: büyük harfli özel adları (cümle başı ve ek parçaları ayıklanarak) bulur, "Usta Cemal" gibi çok sözcüklü adları
     * birleştirir, "Cemal" / "Usta Cemal" gibi farklı hitapları aynı karaktere bağlar, her cümlede kimin geçtiğini ve özneyi işaretler,
     * ilk görünüş (tanıtım) cümlesini ve iki önemli karakterin ilk karşılaşmasını bulur. Dil büyük/küçük harf ayrımı yapmıyorsa (AR) ya da
     * tüm isimler büyük harfliyse (DE) devre dışıdır (null döner); hikâye motoru bu durumda karaktersiz çalışır.
     */
    private fun buildChars(sents: List<Sent>, pack: LangPack): CharModel? {
        if (!pack.hasCase || pack.code == "de") return null
        val n = sents.size
        class Tok(val lw: String, val cap: Boolean, val start: Int, val end: Int)
        val capMid = HashMap<String, Int>()
        val capInit = HashMap<String, Int>()
        val lowCnt = HashMap<String, Int>()
        val surf = HashMap<String, HashMap<String, Int>>()
        val toks = ArrayList<List<Tok>>(n)
        for (s in sents) {
            val list = ArrayList<Tok>()
            var prevEnd = -1
            for (m in WORD.findAll(s.text)) {
                val st = m.range.first
                val e = m.range.last
                // "Elif'in" -> "in": kesme işaretinden sonraki ek parçası ad değildir
                if (st > 0 && (s.text[st - 1] == '\'' || s.text[st - 1] == '’')) { prevEnd = e; continue }
                val w = m.value
                val lw = pack.lower(w)
                val cap = w.length >= 2 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }
                var init = list.isEmpty()
                if (!init && prevEnd >= 0 && s.text.substring(prevEnd + 1, st).any { it in ":\"“”«»—–(!?.…;" }) init = true
                list.add(Tok(lw, cap, st, e))
                prevEnd = e
                if (cap) {
                    if (init) capInit[lw] = (capInit[lw] ?: 0) + 1 else capMid[lw] = (capMid[lw] ?: 0) + 1
                    val sm = surf.getOrPut(lw) { HashMap() }
                    sm[w] = (sm[w] ?: 0) + 1
                } else {
                    lowCnt[lw] = (lowCnt[lw] ?: 0) + 1
                }
            }
            toks.add(list)
        }
        fun okTok(t: String) = t.length >= 3 && t !in pack.stop && t !in pack.generic && t !in STRUCT &&
            !pack.months.matches(t) && !isFuncWord(t, pack) && !(pack.code == "tr" && t in TR_GEN)
        // aday: cümle ortasında büyük harfle geçen ve küçük harfli biçimi seyrek olan sözcük
        val cand = HashSet<String>()
        for ((t, cm0) in capMid) {
            val ci = capInit[t] ?: 0
            if (okTok(t) && (lowCnt[t] ?: 0) <= max(1, ((cm0 + ci) * 0.25).toInt())) cand.add(t)
        }
        // yalnızca cümle başında görünen ama hiç küçük harfle yazılmayan sık adlar
        for ((t, ci) in capInit) {
            if (t !in cand && ci >= 5 && (lowCnt[t] ?: 0) == 0 && (capMid[t] ?: 0) == 0 && okTok(t)) cand.add(t)
        }
        if (cand.isEmpty()) return null

        // çok sözcüklü adlar: "Usta Cemal", "Nine Fatma". Bitişik iki aday yalnızca çoğunlukla BİRLİKTE geçiyorsa bağlanır;
        // "Cemal Elif'in" gibi tesadüfen yan yana gelen farklı karakterler birleştirilmez.
        val totalCap = HashMap<String, Int>()
        val pairCnt = HashMap<String, Int>()
        for (list in toks) {
            for (i in list.indices) {
                val t = list[i]
                if (!t.cap || t.lw !in cand) continue
                totalCap[t.lw] = (totalCap[t.lw] ?: 0) + 1
                if (i + 1 < list.size) {
                    val u = list[i + 1]
                    if (u.cap && u.lw in cand && u.start - t.end == 2) {
                        val k = t.lw + " " + u.lw
                        pairCnt[k] = (pairCnt[k] ?: 0) + 1
                    }
                }
            }
        }
        fun linked(a: String, b: String): Boolean {
            val pc = pairCnt["$a $b"] ?: 0
            return pc >= 2 && pc.toDouble() >= 0.6 * min(totalCap[a] ?: 1, totalCap[b] ?: 1)
        }
        val fullCnt = HashMap<String, Int>()
        val fullToks = HashMap<String, List<String>>()
        for (list in toks) {
            var i = 0
            while (i < list.size) {
                if (!list[i].cap || list[i].lw !in cand) { i++; continue }
                var j = i
                while (j + 1 < list.size && j - i < 2 && list[j + 1].cap && list[j + 1].lw in cand &&
                    list[j + 1].start - list[j].end == 2 && linked(list[j].lw, list[j + 1].lw)) j++
                if (j > i) {
                    val key = (i..j).joinToString(" ") { list[it].lw }
                    fullCnt[key] = (fullCnt[key] ?: 0) + 1
                    fullToks[key] = (i..j).map { list[it].lw }
                }
                i = j + 1
            }
        }
        fun surfOf(t: String): String =
            surf[t]?.maxByOrNull { it.value }?.key ?: t.replaceFirstChar { it.titlecase(pack.locale) }

        val alias = HashMap<String, Int>()
        val ambiguous = HashSet<String>()
        val chars = ArrayList<Chr>()
        fun addAlias(a: String, id: Int) {
            val o = alias[a]
            if (o == null) alias[a] = id else if (o != id) ambiguous.add(a)
        }
        for ((key, c) in fullCnt.entries.sortedByDescending { it.value }) {
            if (c < 2) continue
            val tk = fullToks[key] ?: continue
            val ch = Chr(chars.size, tk.joinToString(" ") { surfOf(it) })
            chars.add(ch)
            for (t in tk) addAlias(t, ch.id)
        }
        for (t in cand.sortedByDescending { (capMid[it] ?: 0) + (capInit[it] ?: 0) }) {
            if (t in alias || (capMid[t] ?: 0) + (capInit[t] ?: 0) < 2) continue
            val ch = Chr(chars.size, surfOf(t))
            chars.add(ch)
            alias[t] = ch.id
        }
        for (a in ambiguous) alias.remove(a)
        if (chars.isEmpty()) return null
        val nameStems = HashSet<String>()
        for (a in alias.keys) nameStems.add(pack.stem(a))

        val spanSeen = Array(chars.size) { BooleanArray(8) }
        for ((i, s) in sents.withIndex()) {
            val list = toks[i]
            val ids = ArrayList<Int>()
            var firstId = -1
            var firstPos = -1
            var lastId = -1
            for ((ti, t) in list.withIndex()) {
                if (!t.cap) continue
                val id = alias[t.lw] ?: continue
                if (id !in ids) ids.add(id)
                if (firstId < 0) { firstId = id; firstPos = ti }
                lastId = id
            }
            s.cs = ids.toIntArray()
            if (ids.isEmpty()) continue
            // konuşma satırında özne genelde "dedi X" ile sonda; anlatımda ilk ad (en fazla 5. sözcüğe kadar)
            s.subj = if (s.dialogue && SPEECH.containsMatchIn(s.lower)) lastId else if (firstPos <= 4) firstId else -1
            for (id in ids) {
                val c = chars[id]
                c.mentions++
                if (c.first < 0) c.first = i
                c.last = i
                spanSeen[id][min(7, i * 8 / n)] = true
                if (!s.dialogue && c.introIdx < 0 && s.words >= 4) c.introIdx = i
            }
            if (s.subj >= 0) chars[s.subj].acts++
        }
        val kept = chars.filter { it.mentions > 0 }
        if (kept.isEmpty()) return null
        val minM = if (n < 20) 2 else 3
        for (c in kept) {
            c.spans = spanSeen[c.id].count { it }
            c.imp = c.mentions * (0.5 + c.spans / 8.0) + 0.5 * c.acts
            c.major = c.mentions >= minM
            if (c.introIdx < 0) c.introIdx = c.first
        }
        val major = kept.filter { it.major }.map { it.id }.toHashSet()
        for (c in kept) if (c.major) sents[c.introIdx].introN++
        val seenPair = HashSet<Long>()
        for (s in sents) {
            val ms = s.cs.filter { it in major }
            if (ms.size < 2) continue
            for (a in ms.indices) for (b in a + 1 until ms.size) {
                if (seenPair.add(min(ms[a], ms[b]).toLong() * 100000L + max(ms[a], ms[b]))) s.meet = true
            }
        }
        return CharModel(kept.sortedByDescending { it.imp }, nameStems)
    }

    private fun lead(s: Sent): String = s.lower.trimStart { !it.isLetter() }

    /** Yarım / kesik cümle: bitiş noktalaması yok, küçük harfle başlıyor ya da tırnak/parantez dengesiz. */
    private fun isFragment(s: Sent, pack: LangPack): Boolean {
        val t = s.text.trim()
        if (t.length < 12) return true
        if (t.last() !in ".!?…\"”»’)") return true
        val f = t.first()
        if (pack.hasCase && f.isLetter() && f.isLowerCase()) return true
        if (t.count { it == '(' } != t.count { it == ')' }) return true
        if (t.count { it == '«' } != t.count { it == '»' }) return true
        if (t.count { it == '“' } != t.count { it == '”' }) return true
        if (t.count { it == '"' } % 2 != 0) return true
        return false
    }

    /** Cümle düzeyi hikâye özellikleri: karar, çatışma, açığa çıkış, değişim, sonuç, zaman atlaması, zamir başlangıcı; karakter-eylem-nesne-sonuç bayrakları. */
    private fun storyFeatures(sents: List<Sent>, cm: CharModel?, pack: LangPack) {
        val nameStems = cm?.nameStems ?: emptySet()
        for (s in sents) {
            val l = s.lower
            val ls = lead(s)
            s.fDec = DECISION.containsMatchIn(l)
            s.fConf = CONFLICT.containsMatchIn(l)
            s.fRev = REVEAL.containsMatchIn(l)
            s.fChg = CHANGE.containsMatchIn(l)
            s.fRes = RESOLUTION.containsMatchIn(l)
            s.fNew = NEWEV.containsMatchIn(l)
            s.fTime = TIME_SHIFT.containsMatchIn(l)
            s.fResStart = RESULT_START.containsMatchIn(ls)
            s.fPron = PRON_START.containsMatchIn(ls) || DEMO_START.containsMatchIn(ls)
            val past = WORD.findAll(l).any { it.value.length >= 4 && pack.isPast(it.value) }
            s.act = s.subj >= 0 && (past || pack.storyCue.containsMatchIn(l))
            s.obj = s.stemSet.count { it !in nameStems } >= 3
        }
        // tırnaksız konuşma etiketi ("..., dedi.") ve onun devamı; yarım/alıntı ortası cümleler özete tek başına girmez
        for (s in sents) {
            s.speech = !s.dialogue && s.words <= 30 && s.text.contains(',') && SPEECH.containsMatchIn(s.lower)
        }
        for (s in sents) {
            val pv = if (s.idx > 0) sents[s.idx - 1] else null
            s.cont = pv != null && pv.para == s.para && pv.speech && !s.dialogue && s.subj < 0 && CONT_START.containsMatchIn(lead(s))
            s.ok = !isFragment(s, pack) && s.text.length <= 700 && !s.cont && !(s.speech && !(s.fDec || s.fRev))
        }
        for (i in 0 until sents.size - 1) {
            val nx = sents[i + 1]
            sents[i].resNext = (nx.fResStart || nx.fRes) && nx.para == sents[i].para
        }
    }

    /**
     * Dinamik olay bölütleme: sabit eşit dilimler yerine, konu geçişi (kelime örtüşmesi düşüşü), başlık/paragraf sınırı, zaman atlaması
     * ("ertesi sabah") ve sahnedeki karakter kümesinin değişmesi birlikte bir "sınır gücü" verir; en güçlü sınırlar olay başlangıcı olur.
     * Zamirle ya da sonuç bağlacıyla başlayan cümlenin önüne sınır konmaz (önceki cümlenin devamıdır). Çok uzun olaylar iç sınırdan bölünür.
     */
    private fun storyEvents(sents: List<Sent>, hasChars: Boolean, target: Int): List<Ev> {
        val n = sents.size
        if (target <= 1 || n < 6) return listOf(Ev(0, n))
        val w = (n / 30).coerceIn(3, 8)
        fun block(from: Int, to: Int): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (i in from until to) for (t in sents[i].stemSet) m[t] = (m[t] ?: 0) + 1
            return m
        }
        fun chars(from: Int, to: Int): Set<Int> {
            val r = HashSet<Int>()
            for (i in from until to) for (c in sents[i].cs) r.add(c)
            return r
        }
        val str = DoubleArray(n)
        for (b in 2 until n - 1) {
            val lo = max(0, b - w)
            val hi = min(n, b + w)
            val s = sents[b]
            var v = 0.30 * (1.0 - cosine(block(lo, b), block(b, hi)))
            if (s.heading != sents[b - 1].heading) v += 0.5
            if (s.para != sents[b - 1].para) v += 0.12
            if (s.fTime) v += 0.35
            if (hasChars) {
                val a = chars(max(lo, b - 3), b)
                val c = chars(b, min(hi, b + 3))
                if (a.isNotEmpty() && c.isNotEmpty()) {
                    var inter = 0
                    for (x in a) if (x in c) inter++
                    v += 0.25 * (1.0 - inter.toDouble() / (a.size + c.size - inter))
                }
            }
            if (s.fPron || s.fResStart) v -= 0.3
            str[b] = max(0.0, v)
        }
        val gap = max(3, n / (target * 3))
        val cuts = ArrayList<Int>()
        for (b in (2 until n - 1).sortedByDescending { str[it] }) {
            if (cuts.size >= target - 1) break
            if (str[b] < 0.2) break
            if (cuts.any { abs(it - b) < gap }) continue
            cuts.add(b)
        }
        cuts.sort()
        // çok uzun olayları en güçlü iç sınırdan (yoksa ortadan) böl
        val maxLen = max(10, 2 * n / target)
        var changed = true
        while (changed) {
            changed = false
            val bounds = listOf(0) + cuts + listOf(n)
            for (k in 0 until bounds.size - 1) {
                val a = bounds[k]
                val z = bounds[k + 1]
                if (z - a <= maxLen) continue
                val inner = (a + 3 until z - 2)
                val best = inner.maxByOrNull { str[it] } ?: continue
                cuts.add(if (str[best] > 0.1) best else (a + z) / 2)
                cuts.sort()
                changed = true
                break
            }
        }
        val bounds = listOf(0) + cuts + listOf(n)
        return (0 until bounds.size - 1).map { Ev(bounds[it], bounds[it + 1]) }
    }

    /** Öznesi/göndermesi için bağlam gereken cümle: 0 = yok, 1 = açık zamir/gösterme/sonuç bağlacı, 2 = adsız eylem cümlesi (tahmin). */
    private fun actorGap(s: Sent, pack: LangPack): Int {
        if (s.bullet || s.isDef || s.isClass || s.dialogue) return 0
        val ls = lead(s)
        val named = s.cs.isNotEmpty()
        if (s.fResStart) return 1
        if (DEMO_START.containsMatchIn(ls)) return 1
        if (PRON_START.containsMatchIn(ls)) return 1
        if (pack.anaphora.containsMatchIn(ls)) return 1
        if (EARLY_DEMO.containsMatchIn(ls.split(' ').take(3).joinToString(" "))) return 1
        if (!named && (s.fDec || s.fConf || s.fRev || pack.storyCue.containsMatchIn(s.lower)) && s.words <= 30) return 2
        return 0
    }

    private fun needsActor(s: Sent, pack: LangPack): Boolean = actorGap(s, pack) > 0

    /** Diyalogda cevap cümlesi, hemen önceki soru olmadan anlaşılmaz. */
    private fun needsQuestion(s: Sent, sents: List<Sent>): Boolean =
        s.dialogue && s.idx > 0 && sents[s.idx - 1].dialogue && sents[s.idx - 1].text.trimEnd('"', '”', '»', ' ').endsWith("?") && !s.text.contains('?')

    /** Konuşan kişi: yalnızca diyalog öznesi/konuşma fiili için güvenilir; "diye bağırdı X'e" gibi hitap edilen kişiyi konuşan sayma. */
    private val SUBJ_PRON = Regex("""^(?:o|onlar|he|she|they|er|sie|él|ella|ellos|il|elle|ils|هو|هي|هم)\s""")

    private fun storySections(sents: List<Sent>, length: Length, pack: LangPack, weights: Map<String, Double>): StoryOut {
        val n = sents.size
        val notes = ArrayList<String>()
        val cm = try { buildChars(sents, pack) } catch (e: Exception) { null }

        // Eski yöntem (karakter modeli kurulamayan diller için yedek): sık geçen büyük harfli adın ilk görünüşü
        val nameFreq = HashMap<String, Int>()
        val namesOf = ArrayList<List<String>>(n)
        for (s in sents) {
            val ns = NAME.findAll(s.text).map { it.value.lowercase(pack.locale) }.toList()
            namesOf.add(ns)
            for (x in ns) nameFreq[x] = (nameFreq[x] ?: 0) + 1
        }
        val seen = HashSet<String>()
        val newChar = BooleanArray(n)
        for (i in 0 until n) {
            for (x in namesOf[i]) {
                if ((nameFreq[x] ?: 0) >= 4 && seen.add(x)) newChar[i] = true
            }
        }
        storyFeatures(sents, cm, pack)
        val salientSet = HashSet<String>()
        // Sözlükten bağımsız sinyal: hikâyede ilk kez geçen ve sonra yeniden anılan belirgin kavram (yeni nesne/varlık/mekân tanıtımı)
        run {
            val df = HashMap<String, Int>()
            val firstAt = HashMap<String, Int>()
            for (s in sents) for (t in s.stemSet) { df[t] = (df[t] ?: 0) + 1; if (t !in firstAt) firstAt[t] = s.idx }
            val nm = cm?.nameStems ?: emptySet<String>()
            val pool = df.filter { it.value >= 2 && it.value <= max(3, n / 6) && it.key !in nm }.keys.sortedByDescending { weights[it] ?: 0.0 }
            val salient = pool.take(max(5, pool.size * 15 / 100)).toHashSet()
            salientSet.addAll(salient)
            val opening = max(3, n / 12)   // açılışta her şey ilk kez geçer; yeni öğe sayılması için açılıştan sonra gelmeli
            for (t in salient) { val f = firstAt[t] ?: continue; if (f >= opening && !sents[f].dialogue) sents[f].newObj = min(2, sents[f].newObj + 1) }
        }

        for (i in 0 until n) {
            val s = sents[i]
            var v = 0.35 * s.base + 0.35 * s.rank
            // önceki cümleyle konu sürekliliği (olay örgüsü akışı)
            if (i > 0) v += 0.10 * min(1.0, jaccard(s.stemSet, sents[i - 1].stemSet) / 0.25)
            v += 0.08 * min(max(namesOf[i].size, s.cs.size), 3)
            if (pack.storyCue.containsMatchIn(s.lower)) v += 0.15
            if (s.dialogue) v *= 0.6
            if (s.text.length > 300) v *= 0.8
            s.score = v
        }

        val m = when (length) { Length.SHORT -> 4; Length.MEDIUM -> 7; Length.LONG -> 12 }
        // Kitap boyutundaki metinlerde sahne sayısı ve özet uzunluğu logaritmik büyür
        val bookF = 1.0 + 0.5 * max(0.0, ln(n / 300.0))
        val sceneBase = when (length) { Length.SHORT -> 4; Length.MEDIUM -> 6; Length.LONG -> 9 }
        val target = max(1, min((sceneBase * bookF).roundToInt(), n / 5))
        // Özet uzunluğu artık cümle sayısıyla değil KELİME bütçesiyle belirlenir: bağlam zincirleri sığsın diye
        val totalWords = sents.sumOf { it.words }
        val wRatio = when (length) { Length.SHORT -> 0.05; Length.MEDIUM -> 0.10; Length.LONG -> 0.18 }
        val wFloor = when (length) { Length.SHORT -> 60; Length.MEDIUM -> 120; Length.LONG -> 200 }
        val wCapBase = when (length) { Length.SHORT -> 200; Length.MEDIUM -> 420; Length.LONG -> 800 }
        val wCap = (wCapBase * bookF).roundToInt()
        val wordBudget = max(wFloor, min(wCap, (totalWords * wRatio).roundToInt())).coerceAtMost(max(40, (totalWords * 0.45).toInt()))
        val events = storyEvents(sents, cm != null, target)
        for ((ei, e) in events.withIndex()) for (i in e.from until e.to) sents[i].ev = ei

        // ---- olay içi önem (karakter + eylem + nesne + sonuç + dönüm özellikleri)
        val hasCm = cm != null
        val nmSt = cm?.nameStems ?: emptySet<String>()
        for (s in sents) {
            var v = s.score
            if (s.introN > 0) v += 0.25
            if (s.meet) v += 0.3
            if (!hasCm && newChar[s.idx]) v += 0.3
            if (s.fDec) v += 0.22
            if (s.fConf) v += 0.18
            if (s.fRev) v += 0.2
            if (s.fChg) v += 0.2
            if (s.fRes) v += 0.2
            if (s.fNew) v += 0.12
            if (s.subj >= 0 && cm?.byId?.get(s.subj)?.major == true) v += 0.08
            if (s.act) v += 0.08
            if (s.obj) v += 0.04
            if (s.resNext) v += 0.12
            v += 0.08 * s.newObj
            if (needsActor(s, pack)) v -= 0.08
            if (s.dialogue && !(s.fDec || s.fRev || s.introN > 0 || s.meet)) v *= 0.6
            // yüksek TF-IDF/TextRank tek başına yetmez: olay/karakter katkısı olmayan ve içeriği zayıf cümleler geriye düşer
            val signal = s.fDec || s.fConf || s.fRev || s.fChg || s.fRes || s.fNew || s.introN > 0 || s.meet || s.newObj > 0 || s.act
            if (!signal) v -= 0.06
            // Hikâye bütünlüğü: başlangıç, ayrılmaz karakter tanıtımı ve kapanış sonuç cümlesi
            // rastgele yüksek puanlı sahnenin gölgesinde kalmamalı.
            if (s.idx == 0) v += 0.32
            if (s.idx >= n - 2) v += if (s.fRes || s.isConc) 0.38 else 0.18
            if (s.heading != null && s.paraStart) v += 0.12
            if (s.fTime) v += 0.10
            if (s.stemSet.count { it !in nmSt } < 3) v -= 0.08
            if (s.subj >= 0 && cm?.byId?.get(s.subj)?.major == true && (s.fChg || s.fRev || s.fDec)) v += 0.10
            if (s.speech) v *= 0.5
            if (!s.ok) v -= 1.0
            s.evs = v
        }
        for (e in events) {
            val top = (e.from until e.to).map { sents[it].evs }.sortedDescending().take(3)
            e.value = top.average() + 0.1 * ln(1.0 + (e.to - e.from))
        }
        val maxV = max(1e-9, events.maxOf { it.value })

        fun role(s: Sent): Int {
            val e = events[s.ev]
            val len = e.to - e.from
            val p = if (len <= 1) 0.5 else (s.idx - e.from).toDouble() / (len - 1)
            return if (p < 0.34) 0 else if (p > 0.66) 2 else 1
        }
        fun roleScore(s: Sent): Double {
            var v = s.evs
            when (role(s)) {
                0 -> if (s.introN > 0 || s.fTime || s.cs.isNotEmpty()) v += 0.12
                2 -> if (s.fRes || s.resNext || s.fRev) v += 0.12
                else -> if (s.fConf || s.fDec || s.fRev) v += 0.08
            }
            return v
        }
        fun pool(ei: Int, r: Int): List<Sent> {
            val e = events[ei]
            val all = (e.from until e.to).map { sents[it] }
            val f = if (r < 0) all else all.filter { role(it) == r }
            return (if (f.isEmpty()) all else f).sortedByDescending { roleScore(it) }
        }

        // Zamirle başlayan cümle için etiket mümkün mü ("[Prens] ..."): önceki cümlede tek ve belirgin özne var
        fun labelable(s: Sent): Boolean {
            if (cm == null || s.idx == 0 || actorGap(s, pack) != 1 || !SUBJ_PRON.containsMatchIn(lead(s))) return false
            val pv = sents[s.idx - 1]
            return pv.cs.size == 1 && pv.subj == pv.cs[0]
        }
        // Tek başına anlaşılır mı: bağımsız cümle, ya da kullanılabilir kısa önceki cümle / etiket ile
        fun selfContained(s: Sent): Boolean {
            if (actorGap(s, pack) == 0 && !s.cont) return true
            if (s.idx == 0) return false
            val pv = sents[s.idx - 1]
            if (pv.ok && pv.para == s.para && !pv.dialogue && pv.words <= 30) return true
            return labelable(s)
        }
        fun contentSet(s: Sent): Set<String> = s.stemSet.filter { it !in nmSt }.toSet()
        // Anlamca tekrar: içerik sözcükleri büyük oranda aynı olan cümle özete ikinci kez girmez
        fun simToAny(c: Sent, pool: Collection<Sent>): Boolean {
            val cc = contentSet(c)
            for (q in pool) if (q !== c && jaccard(cc, contentSet(q)) > 0.55) return true
            return false
        }
        fun storyText(s: Sent): String = s.text.replace(WS, " ").trim()

        // ---- SAHNE TABANLI BAĞLANTILI ÖZET
        // Eski yöntem özeti tüm hikâyeye dağılmış tek tek cümlelerden kuruyordu; cümleler birbirini açıklamıyordu.
        // Yeni yöntem: her olay (sahne) için KESİNTİSİZ pasajlar seçilir; her sahne tek paragraf olur.
        //  - sahne girişi (zaman/yer/tanıtım) ve ana karakterlerin ilk tanıtımı mutlaka alınır,
        //  - seçilen her cümleye, anlaşılması için gereken önceki/sonraki komşular (zamir, bağlaç, soru-cevap, neden-sonuç) eklenir,
        //  - sahne içinde seçim, daha önce seçilen cümlelere bitişik olanları tercih eder (akıcılık),
        //  - son sahne hikâyenin sonucunu mutlaka taşır.
        var labelAdded = 0
        val majorIntro = HashSet<Int>()
        if (cm != null) for (c in cm.list.filter { it.major }.sortedByDescending { it.imp }.take(3)) majorIntro.add(c.introIdx)
        fun sceneWeight(ei: Int): Double {
            val edge = if (events.size > 1 && (ei == 0 || ei == events.size - 1)) 1.15 else 1.0
            return (0.5 + events[ei].value / maxV) * edge
        }
        val weightSum = events.indices.sumOf { sceneWeight(it) }
        val sceneParas = ArrayList<List<Sent>>()
        val summaryAll = ArrayList<Sent>()
        for (ei in events.indices) {
            val e = events[ei]
            val sceneWords = (e.from until e.to).sumOf { sents[it].words }
            var budgetW = (wordBudget * sceneWeight(ei) / weightSum).roundToInt()
            budgetW = min(budgetW, (sceneWords * 0.6).roundToInt())
            budgetW = max(budgetW, min(25, sceneWords))
            val chosenS = LinkedHashSet<Sent>()
            val blocked = HashSet<Sent>()
            var w = 0

            // bir cümle + onu anlaşılır kılan bağlam zinciri. Bağlamı eksik kalacak, yarım ya da konuşma ortası cümle için BOŞ liste döner (cümle atlanır).
            fun windowFor(x: Sent): List<Sent> {
                if (!x.ok) return emptyList()
                val win = ArrayList<Sent>()
                win.add(x)
                var cur = x
                var steps = 0
                while (true) {
                    val pIdx = cur.idx - 1
                    val prevDlg = pIdx >= 0 && cur.dialogue && sents[pIdx].dialogue && !(cur.fDec || cur.fRev || cur.fConf)
                    val needs = actorGap(cur, pack) > 0 || needsQuestion(cur, sents) || pack.contrastStart.containsMatchIn(lead(cur)) || prevDlg || cur.cont
                    if (!needs) break
                    val p = if (pIdx >= e.from) sents[pIdx] else null
                    if (p != null && p in chosenS) break
                    if (p == null || !p.ok || p.words > 45 || steps >= 2) {
                        if (labelable(cur)) break
                        return emptyList()
                    }
                    win.add(0, p)
                    cur = p
                    steps++
                }
                if (x.idx + 1 < e.to) {
                    val nx = sents[x.idx + 1]
                    if (nx !in chosenS && nx.ok && nx.words <= 40) {
                        val ans = x.dialogue && nx.dialogue && x.text.trimEnd('"', '”', '»', ' ').endsWith("?") && !nx.text.contains('?')
                        if (x.resNext || ans) win.add(nx)
                    }
                }
                return win
            }
            fun costOf(win: List<Sent>): Int = win.filter { it !in chosenS }.sumOf { it.words }
            fun addWin(win: List<Sent>) { for (x in win) if (chosenS.add(x)) w += x.words }

            // zorunlu cümleler: sahne girişi, ana karakter tanıtımları, son sahnede sonuç + kapanış
            val must = ArrayList<Sent>()
            val opener = sents[e.from]
            if (opener.ok && !opener.dialogue && opener.words <= 40 && (ei == 0 || opener.fTime || opener.introN > 0)) must.add(opener)
            for (i in e.from until e.to) {
                val c = sents[i]
                if (i in majorIntro && c.ok && !c.dialogue && c.words <= 40 && c !in must) must.add(c)
            }
            if (events.size > 1 && ei == events.size - 1) {
                val endS = (e.from until e.to).map { sents[it] }.filter { it.ok && !it.dialogue && (it.fRes || it.resNext) }.maxByOrNull { it.evs }
                if (endS != null && endS !in must) must.add(endS)
                val closing = sents[e.to - 1]
                if (closing.ok && !closing.dialogue && closing.words <= 35 && closing !in must) must.add(closing)
            }
            for (x in must) {
                val win = windowFor(x)
                if (win.isNotEmpty() && (chosenS.isEmpty() || w + costOf(win) <= budgetW * 1.25)) addWin(win)
            }

            // kalan bütçe: olay içi önem + bitişiklik bonusu (kopuk cümle yerine pasaj oluşsun)
            var guard = 0
            while (w < budgetW && guard++ < 400) {
                var best: Sent? = null
                var bestV = -1e9
                for (i in e.from until e.to) {
                    val c = sents[i]
                    if (c in chosenS || c in blocked || !c.ok) continue
                    var v = roleScore(c)
                    if (chosenS.any { abs(it.idx - c.idx) == 1 }) v += 0.10
                    else if (chosenS.any { abs(it.idx - c.idx) == 2 }) v += 0.05
                    if (v > bestV) { bestV = v; best = c }
                }
                val c = best ?: break
                if (redundant(c, chosenS) || simToAny(c, chosenS) || simToAny(c, summaryAll)) { blocked.add(c); continue }
                val win = windowFor(c)
                if (win.isEmpty() || (chosenS.isNotEmpty() && w + costOf(win) > budgetW * 1.25)) { blocked.add(c); continue }
                addWin(win)
            }
            if (chosenS.isEmpty()) chosenS.add((e.from until e.to).map { sents[it] }.firstOrNull { it.ok } ?: sents[e.from])
            val list = chosenS.sortedBy { it.idx }
            summaryAll.addAll(list)
            sceneParas.add(list)
        }
        val summaryList: List<Sent> = summaryAll.sortedBy { it.idx }
        // Sahne içinde birbirinden çok uzak (>3 cümle) pasajlar aynı paragrafa yapıştırılmaz; ayrı madde olur.
        val summaryItems = ArrayList<String>()
        for (list in sceneParas) {
            val groups = ArrayList<MutableList<Sent>>()
            for (s in list) {
                val g = groups.lastOrNull()
                if (g == null || s.idx - g.last().idx > 3) groups.add(arrayListOf(s)) else g.add(s)
            }
            for (g in groups) {
                val inG = g.map { it.idx }.toHashSet()
                val body = g.joinToString(" ") { s ->
                    val t = storyText(s)
                    val nm = if (s.idx - 1 !in inG && labelable(s)) (cm?.byId?.get(sents[s.idx - 1].subj)?.name ?: "") else ""
                    if (nm.isNotEmpty()) { labelAdded++; "[" + nm + "] " + t } else t
                }
                if (body.isNotBlank()) summaryItems.add(body)
            }
        }

        // ---- dönüm noktaları
        val summarySet = HashSet<Sent>(summaryList)
        val cntMajor = cm?.list?.count { it.major } ?: 0
        for (s in sents) {
            var nf = 0
            var v = 0.0
            if (s.fDec) { v += 0.35; nf++ }
            if (s.fRev) { v += 0.32; nf++ }
            if (s.fChg) { v += 0.30; nf++ }
            if (s.fRes) { v += 0.30; nf++ }
            if (s.fConf) { v += 0.28; nf++ }
            if (s.newObj > 0) { v += 0.16 * s.newObj; nf++ }
            if (s.fNew) { v += 0.22; nf++ }
            val core = nf
            if (s.introN > 0) { v += 0.10; nf++ }
            if (s.meet && s.idx > n / 10) { v += 0.10; nf++ }
            if (s.idx == events[s.ev].from && (s.fTime || (s.idx > 0 && s.heading != sents[s.idx - 1].heading))) { v += 0.22; nf++ }
            if (s.act && s.resNext) { v += 0.25; nf++ } else if (s.resNext) v += 0.10
            if (!hasCm && newChar[s.idx]) { v += 0.25; nf++ }
            if (core == 0 || (s.dialogue && !(s.fDec || s.fRev))) { s.tps = 0.0; continue }
            if (nf >= 2) v += 0.08 * min(2, nf - 1)
            if (s.subj >= 0 && cm?.byId?.get(s.subj)?.major == true) v += 0.08
            if (s.obj) v += 0.04
            v += 0.15 * (events[s.ev].value / maxV)
            val pos = s.idx.toDouble() / max(1, n - 1)
            v += 0.05 * bump(pos, 0.12, 0.07) + 0.05 * bump(pos, 0.5, 0.08) + 0.08 * bump(pos, 0.78, 0.1) + 0.04 * bump(pos, 0.96, 0.04)
            if (s.dialogue && !(s.fDec || s.fRev)) v *= 0.55
            if (s.text.length > 300) v *= 0.8
            if (s in summarySet) v *= 0.6
            if (needsActor(s, pack)) v *= 0.85
            s.tps = v
        }
        val nameStems = cm?.nameStems ?: emptySet<String>()
        fun content(s: Sent): Set<String> = s.stemSet.filter { it !in nameStems }.toSet()
        fun sameAction(a: Sent, b: Sent): Boolean {
            val ca = content(a)
            val cb = content(b)
            if (jaccard(ca, cb) > 0.45) return true
            if (a.subj >= 0 && a.subj == b.subj && ca.count { it in cb } >= 2) return true
            return false
        }
        val tpMax = min(m, max(3, n / 6))
        val tpGap = max(1, n / (m * 3))
        val tp = ArrayList<Sent>()
        val perEvTp = IntArray(events.size)
        val ranked = sents.filter { it.tps > 0.0 && it.ok && selfContained(it) }.sortedByDescending { it.tps }
        for (pass in 0 until 2) {
            for (s in ranked) {
                if (tp.size >= tpMax) break
                if (s in tp) continue
                if (s in summarySet) continue
                if (pass == 0 && perEvTp[s.ev] >= 1) continue
                if (pass == 1 && perEvTp[s.ev] >= 2) continue
                if (tp.any { abs(it.idx - s.idx) < tpGap || sameAction(it, s) }) continue
                tp.add(s); perEvTp[s.ev]++
            }
        }
        // çok az gerçek dönüm noktası bulunduysa (olaysız/yalın metin): en yüksek olay önemli cümlelerle tamamla
        if (tp.size < min(3, tpMax)) {
            for (s in sents.sortedByDescending { it.evs }) {
                if (tp.size >= min(3, tpMax)) break
                if (!s.ok || !selfContained(s) || s in tp || s in summarySet || tp.any { abs(it.idx - s.idx) < tpGap || sameAction(it, s) }) continue
                tp.add(s)
            }
        }
        tp.sortBy { it.idx }
        // Her dönüm noktası: (gerekirse) bağlam cümlesi + olay cümlesi + (varsa) sonucu; yarım cümle / kesik alıntı yok.
        val tpItems = tp.map { s ->
            val pv = if (s.idx > 0) sents[s.idx - 1] else null
            val nx = if (s.idx + 1 < n) sents[s.idx + 1] else null
            val needCtx = actorGap(s, pack) > 0 || s.cont
            val headPart = if (pv != null && needCtx && pv.ok && pv.para == s.para && !pv.dialogue && pv.words <= 30 && pv !in tp) storyText(pv) + " "
                else if (pv != null && labelable(s)) "[" + (cm?.byId?.get(pv.subj)?.name ?: "") + "] "
                else ""
            val tailPart = if (nx != null && s.resNext && nx.ok && nx.para == s.para && nx.words <= 30 && nx !in tp) " " + storyText(nx) else ""
            headPart + storyText(s) + tailPart
        }

        notes.add("olay=" + events.size + "(" + events.joinToString(",") { (it.to - it.from).toString() } + ")")
        if (cm != null) {
            notes.add("karakter=" + cm.list.filter { it.major }.take(8).joinToString(",") { it.name + "(" + it.mentions + ")" })
            val pairs = HashMap<String, Int>()
            for (s in sents) {
                val ids = s.cs.filter { cm.byId[it]?.major == true }.sorted()
                for (a in ids.indices) for (b in a + 1 until ids.size) {
                    val k = cm.byId[ids[a]]!!.name + "-" + cm.byId[ids[b]]!!.name
                    pairs[k] = (pairs[k] ?: 0) + 1
                }
            }
            notes.add("iliski=" + pairs.entries.sortedByDescending { it.value }.take(5).joinToString(",") { it.key + "(" + it.value + ")" })
        } else notes.add("karakter=model-yok")
        notes.add("sahne=" + events.size + " etiket=$labelAdded kelime=" + summaryList.sumOf { it.words } + "/" + wordBudget + " ozet=" + summaryList.size + " donum=" + tp.size)
        if (cntMajor == 0 && cm != null) notes.add("buyuk-karakter-yok")

        val sections = listOf(
            Section(null, summaryItems, SectionKind.PARAGRAPHS, SecTitle.SUMMARY),
            Section(null, tpItems, SectionKind.NUMBERED, SecTitle.TURNING)
        )
        val spreadMap = HashMap<String, Int>()
        for (e in events) {
            val u = HashSet<String>()
            for (i in e.from until e.to) u.addAll(sents[i].stemSet)
            for (t in u) spreadMap[t] = (spreadMap[t] ?: 0) + 1
        }
        // zaman geçişi cümlelerinde ağırlıklı geçen kavramlar ("sabah", "gece", "ertesi"...) tema/nesne değil, anlatı iskeletidir
        val inTime = HashMap<String, Int>()
        val inAll = HashMap<String, Int>()
        for (sx in sents) for (t in sx.stemSet) { inAll[t] = (inAll[t] ?: 0) + 1; if (sx.fTime) inTime[t] = (inTime[t] ?: 0) + 1 }
        val timeish = inTime.filter { (inAll[it.key] ?: 0) >= 2 && it.value * 10 >= (inAll[it.key] ?: 1) * 4 }.keys.toHashSet()
        return StoryOut(sections, summaryList + tp, notes, cm?.list?.filter { it.major } ?: emptyList(), cm?.nameStems ?: emptySet(), spreadMap, events.size, salientSet, timeish)
    }

    // ------------------------------------------------------------------ anahtar kelimeler

    private fun pickSurface(m: Map<String, Int>): String {
        val mx = m.values.maxOrNull() ?: 1
        return m.filter { it.value * 2 >= mx }.keys.minByOrNull { it.length } ?: m.keys.first()
    }

    /** Türkçe çekim eklerini yalnızca GÖRÜNTÜDE kaldırır ("kiralarda" -> "kira", "kullanımını" -> "kullanım"); kalan kısım en az 4 harf olmalı. */
    /** Sonu çekim eki gibi görünen ama özel ad olan sözcükler: "Türkiye" -> "Türki" olmasın. */
    private val TR_PROPER = setOf(
        "türkiye", "avrupa", "amerika", "almanya", "fransa", "ispanya", "italya", "rusya", "ukrayna", "suriye", "gürcistan", "kıbrıs",
        "anadolu", "trakya", "ankara", "izmir", "istanbul", "bursa", "antalya", "adana", "konya", "kayseri", "mersin", "gaziantep"
    )

    private val TR_SUFFIX2 = Regex(
        "(larından|lerinden|larında|lerinde|ların|lerin|larda|lerde|lardan|lerden|lara|lere|ları|leri|" +
            "sından|sinden|sundan|sünden|ından|inden|undan|ünden|sında|sinde|sunda|sünde|ında|inde|unda|ünde|" +
            "sını|sini|sunu|sünü|ını|ini|unu|ünü|nın|nin|nun|nün|ndan|nden|nda|nde|" +
            "dan|den|tan|ten|ler|lar|yla|yle)$"
    )

    private fun trBase(w: String): String {
        var cur = w
        for (pass in 0 until 2) {
            val m = TR_SUFFIX2.find(cur) ?: TR_SUFFIX.find(cur) ?: break
            val base = cur.substring(0, m.range.first)
            if (base.length < 4) break
            val suf = m.value
            cur = if ((suf == "ye" || suf == "ya") && (base.endsWith("me") || base.endsWith("ma"))) {
                base + (if (base.endsWith("me")) "si" else "sı")
            } else base
        }
        return cur
    }

    private fun titleWord(low: String, acro: Boolean, pack: LangPack, keepForm: Boolean = false): String {
        if (acro) return low.uppercase(pack.locale)
        val w = if (pack.code == "tr" && !keepForm) trBase(low) else low
        return w.replaceFirstChar { c -> c.titlecase(pack.locale) }
    }

    /**
     * Aday anahtar kelimeler: tek kelime ve iki kelimelik kavramlar ("Makine Öğrenmesi").
     * Puan: TF-IDF + başlıkta geçme + tanımın öznesi olma + kısaltma / özel isim + tekrar eden kavram bonusu.
     * Genel kelimeler ("yeni", "çalışan", "belirli"...), fiiller ve yalnızca diyaloglarda geçenler elenir.
     */
    private fun keywordCandidates(sents: List<Sent>, weights: Map<String, Double>, pack: LangPack): List<Kw> {
        val maxW = max(weights.values.maxOrNull() ?: 1.0, 1e-9)
        val headSets = ArrayList<Set<String>>()
        val headStems = HashSet<String>()
        val seenHeads = HashSet<String>()
        for (s in sents) {
            val h = s.heading ?: continue
            if (seenHeads.add(h)) {
                val st = stems(h, pack).toHashSet()
                headSets.add(st); headStems.addAll(st)
            }
        }
        val defStems = HashSet<String>()
        val defPhrases = HashSet<String>()
        for (s in sents) {
            if (!s.isDef || s.bullet || s.words > 40) continue
            var prev: String? = null
            var cnt = 0
            for (m in WORD.findAll(s.text)) {
                if (cnt >= 4) break
                cnt++
                val low = pack.lower(m.value)
                if (low.length < 3 || low in pack.stop || low in pack.generic) { prev = null; continue }
                val st = pack.stem(low)
                defStems.add(st)
                if (prev != null) defPhrases.add("$prev $st")
                prev = st
            }
        }

        for (s in sents) {
            if (!s.member || s.dialogue) continue
            val m = WORD.find(s.text) ?: continue
            val low = pack.lower(m.value)
            if (low.length < 5 || low in pack.stop || low in pack.generic) continue
            defStems.add(pack.stem(low))
        }

        val uTf = HashMap<String, Int>()
        val uSurf = HashMap<String, HashMap<String, Int>>()
        val uAcro = HashMap<String, Int>()
        val uCap = HashMap<String, Int>()
        val uDlg = HashMap<String, Int>()
        val bTf = HashMap<String, Int>()
        val bSurf = HashMap<String, HashMap<String, Int>>()
        val bProper = HashMap<String, Int>()
        val bAcro = HashMap<String, Int>()
        val uLast = HashMap<String, Int>()
        val uInit = HashMap<String, Int>()
        class Txt(val text: String, val dialogue: Boolean, val head: Boolean = false)
        val texts = ArrayList<Txt>()
        for (s in evenly(sents, 6000)) texts.add(Txt(s.text, s.dialogue))
        for (h in seenHeads) texts.add(Txt(h, false, true))
        for (s in texts) {
            var prevStem: String? = null
            var prevLow = ""
            var prevEnd = -5
            var prevCap = false
            var prevAcro = false
            var wi = 0
            val lastStart = if (s.head) -1 else (WORD.findAll(s.text).lastOrNull()?.range?.first ?: -1)
            for (m in WORD.findAll(s.text)) {
                val orig = m.value
                val low = pack.lower(orig)
                // "Nine Fatma'nın" -> "nın": kesme işaretinden sonraki çekim eki anahtar kelime adayı değildir
                if (m.range.first > 0 && (s.text[m.range.first - 1] == '\'' || s.text[m.range.first - 1] == '’')) { prevStem = null; continue }
                val first = wi == 0
                wi++
                val cand = low.length >= 3 && low !in pack.stop && low !in pack.generic && low !in STRUCT && !(pack.code == "tr" && low in TR_GEN) && !pack.months.matches(low) &&
                    low !in KW_JUNK && !(pack.code == "tr" && low.startsWith("kendi"))
                if (!cand) { prevStem = null; continue }
                val st = pack.stem(low)
                val acro = orig.length >= 2 && orig.all { it.isUpperCase() }
                val cap = pack.hasCase && pack.code != "de" && !first && orig[0].isUpperCase() && orig.drop(1).any { it.isLowerCase() }
                uTf[st] = (uTf[st] ?: 0) + 1
                if (m.range.first == lastStart) uLast[st] = (uLast[st] ?: 0) + 1
                val sm = uSurf.getOrPut(st) { HashMap() }
                sm[low] = (sm[low] ?: 0) + 1
                if (acro) uAcro[st] = (uAcro[st] ?: 0) + 1
                if (cap) uCap[st] = (uCap[st] ?: 0) + 1
                if (first && !s.head && pack.hasCase) uInit[st] = (uInit[st] ?: 0) + 1
                if (s.dialogue) uDlg[st] = (uDlg[st] ?: 0) + 1
                if (prevStem != null && m.range.first == prevEnd + 2 && s.text[prevEnd + 1] == ' ') {
                    val key = "$prevStem $st"
                    bTf[key] = (bTf[key] ?: 0) + 1
                    val bm = bSurf.getOrPut(key) { HashMap() }
                    val sf = "$prevLow $low"
                    bm[sf] = (bm[sf] ?: 0) + 1
                    if ((cap || acro) && (prevCap || prevAcro)) bProper[key] = (bProper[key] ?: 0) + 1
                    if (acro || prevAcro) bAcro[key] = (bAcro[key] ?: 0) + 1
                }
                prevStem = st; prevLow = low; prevEnd = m.range.last; prevCap = cap; prevAcro = acro
            }
        }

        val out = ArrayList<Kw>()
        for ((st, tf) in uTf) {
            val inHead = st in headStems
            if (tf < 2 && !inHead) continue
            val acro = (uAcro[st] ?: 0) * 2 >= tf
            val proper = tf >= 2 && (uCap[st] ?: 0).toDouble() / tf >= 0.5
            val inDef = st in defStems
            val best = pickSurface(uSurf[st]!!)
            var sc = 0.55 * ((weights[st] ?: 0.0) / maxW) + 0.3 * min(1.0, ln(1.0 + tf) / ln(21.0))
            if (inHead) sc += 0.35
            if (acro) sc += 0.6
            if (proper) sc += 0.45
            if (inDef) sc += 0.5
            if (best.length >= 8) sc += 0.12
            if (pack.isPast(best)) sc *= 0.3
            if (pack.code == "tr" && (best.endsWith("mak") || best.endsWith("mek"))) sc *= 0.3
            if (pack.code == "tr" && best.length >= 8 && TR_COP_END.matches(best)) sc *= 0.2
            if (pack.code == "fr" && FR_PART.matches(best) && !proper) sc *= 0.3
            if ((uDlg[st] ?: 0) * 2 > tf) sc *= 0.5
            if (pack.code == "tr" && tf >= 2 && (uLast[st] ?: 0) * 2 >= tf && !proper && !acro) sc *= 0.25
            // cümle başında büyük harfle görünen ve özel ad olmayan sözcükler ("Öyle", "Yine", "Günaydın"): söylem sözcüğü, kavram değil
            if (tf >= 2 && (uInit[st] ?: 0) * 2 >= tf && !proper && !acro) sc *= 0.3
            if (sc < 0.3) continue
            out.add(Kw(st, titleWord(best, acro, pack, keepForm = proper || best in TR_PROPER), setOf(st), tf, false, sc))
        }
        for ((key, c) in bTf) {
            if (c < 2) continue
            val parts = key.split(' ')
            val t1 = uTf[parts[0]] ?: continue
            val t2 = uTf[parts[1]] ?: continue
            if (c.toDouble() / min(t1, t2) < 0.3) continue
            if (pack.code == "tr" && t2 >= 2 && (uLast[parts[1]] ?: 0) * 10 >= t2 * 6 && (bProper[key] ?: 0) == 0 && (bAcro[key] ?: 0) == 0) continue
            val w1 = (weights[parts[0]] ?: 0.0) / maxW
            val w2 = (weights[parts[1]] ?: 0.0) / maxW
            var sc = 0.15 + 0.45 * sqrt(w1 * w2) + 0.25 * min(1.0, ln(1.0 + c) / ln(6.0))
            if (headSets.any { parts[0] in it && parts[1] in it }) sc += 0.5
            if ((bProper[key] ?: 0) * 2 >= c) sc += 0.35
            if ((bAcro[key] ?: 0) > 0) sc += 0.3
            if (key in defPhrases) sc += 0.5
            val surfMap = bSurf[key]!!
            val okMap = if (pack.code == "tr") surfMap.filter { !TR_CASE_END.matches(it.key.substringAfter(' ')) } else surfMap
            if (okMap.isEmpty()) continue
            val sf = pickSurface(okMap).split(' ')
            val disp = sf.joinToString(" ") { w ->
                titleWord(w, (uAcro[pack.stem(w)] ?: 0) * 2 >= (uTf[pack.stem(w)] ?: 1), pack, keepForm = w in TR_PROPER || (bProper[key] ?: 0) * 2 >= c)
            }
            out.add(Kw(key, disp, parts.toSet(), c, true, sc))
        }
        out.sortByDescending { it.score }
        return out.take(40)
    }

    /**
     * Hikâye modu için aday yeniden sıralama (yalnızca Mode.STORY; ders notu anahtar kelimeleri etkilenmez):
     * - Ana karakterlerin adları aday olarak eklenir ("Usta Cemal"); ad + sıradan sözcük bitişikliği ("Elif Harita") elenir.
     * - Hikâyenin birçok olayına yayılan (tema/nesne) ve yeni öğe olarak tanıtılan kavramlar ağırlıklanır; tek sahneye sıkışanlar azaltılır.
     * Son süzgeç (özette geçme, tekrar birleştirme, üst sınır) yine finalKeywords tarafından uygulanır.
     */
    private fun storyCands(cands: List<Kw>, so: StoryOut, pack: LangPack): List<Kw> {
        val top = cands.maxOfOrNull { it.score } ?: 1.0
        val out = ArrayList<Kw>()
        val charStems = HashSet<String>()
        val nameKeys = HashSet<String>()
        var rank = 0
        for (c in so.chars.sortedByDescending { it.imp }.take(6)) {
            val toks = c.name.split(' ').map { pack.stem(pack.lower(it)) }
            charStems.addAll(toks)
            nameKeys.add(toks.joinToString(" "))
            out.add(Kw("char:" + c.name, c.name, toks.toSet(), c.mentions, toks.size > 1, top * (1.15 - 0.05 * rank)))
            rank++
        }
        for (k in cands) {
            // ad + sıradan sözcük birleşimi (ör. "Elif Harita"): ad çiftlerini koru, diğerlerini ele
            if (k.phrase && k.stems.any { it in so.nameStems } && k.key !in nameKeys) continue
            if (!k.phrase && k.stems.all { it in charStems }) continue   // ad zaten karakter olarak var
            val sp = so.spread[k.stems.first()] ?: 1
            val spreadF = if (so.nEv >= 3 && !k.phrase) 0.6 + 0.8 * min(1.0, sp.toDouble() / so.nEv) else 1.0
            val salF = if (k.stems.any { it in so.salient }) 1.12 else 1.0
            val timeF = if (!k.phrase && k.stems.all { it in so.timeish }) 0.4 else 1.0
            out.add(Kw(k.key, k.surface, k.stems, k.tf, k.phrase, k.score * spreadF * salF * timeF))
        }
        out.sortByDescending { it.score }
        return out
    }

    /** Özet seçildikten sonra: özette / başlıkta geçmeyen ve nadir kelimeleri ele, yinelenen kavramları birleştir. */
    private fun finalKeywords(cands: List<Kw>, chosen: Collection<Sent>, sents: List<Sent>, pack: LangPack): List<String> {
        val chosenStems = HashSet<String>()
        for (s in chosen) chosenStems.addAll(s.stemSet)
        val headStems = HashSet<String>()
        for (h in sents.mapNotNull { it.heading }.toSet()) headStems.addAll(stems(h, pack))
        val n = sents.size
        val limit = when {
            n < 10 -> 5
            n < 30 -> 7
            n < 80 -> 10
            n < 300 -> 11
            else -> 12
        }
        val ranked = ArrayList<Kw>()
        for (k in cands) {
            val inSum = k.stems.all { it in chosenStems }
            val inHead = k.stems.all { it in headStems }
            if (!(inSum || inHead || k.tf >= 3)) continue
            ranked.add(Kw(k.key, k.surface, k.stems, k.tf, k.phrase, k.score * (if (inSum || inHead) 1.2 else 0.8)))
        }
        ranked.sortByDescending { it.score }
        val floor = if (ranked.isEmpty()) 0.0 else ranked[0].score * 0.3
        val cap = max(2, (limit + 2) / 3)
        val perHead = HashMap<String, Int>()
        fun headOf(k: Kw): String? {
            val cnt = HashMap<String, Int>()
            for (s in sents) {
                val h = s.heading ?: continue
                if (k.stems.all { it in s.stemSet }) cnt[h] = (cnt[h] ?: 0) + 1
            }
            return cnt.maxByOrNull { it.value }?.key
        }
        val picked = ArrayList<Kw>()
        val skipped = ArrayList<Kw>()
        for (k in ranked) {
            if (picked.size >= limit) break
            if (k.score < floor) break
            if (picked.any { it.stems.containsAll(k.stems) }) continue
            val h = headOf(k)
            if (h != null && (perHead[h] ?: 0) >= cap) { skipped.add(k); continue }
            if (k.phrase) picked.removeAll { !it.phrase && k.stems.containsAll(it.stems) }
            picked.add(k)
            if (h != null) perHead[h] = (perHead[h] ?: 0) + 1
        }
        for (k in skipped) {
            if (picked.size >= limit) break
            if (picked.any { it.stems.containsAll(k.stems) }) continue
            picked.add(k)
        }
        return picked.map { it.surface }.distinctBy { it.lowercase(pack.locale) }
    }
}
