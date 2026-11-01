package com.ozet.app.engine

import java.util.Locale

/** Bir dilin özetleme ipuçları. Özetleyici belgenin dilini otomatik algılar ve ilgili paketi kullanır. */
class LangPack(
    val code: String,
    val locale: Locale,
    val stemLen: Int,
    val stop: Set<String>,
    val generic: Set<String>,
    val abbr: Set<String>,
    val markers: Set<String>,
    val def: Regex,
    val classCue: Regex,
    val cause: Regex,
    val compare: Regex,
    val example: Regex,
    val conclusion: Regex,
    val storyCue: Regex,
    val contrastStart: Regex,
    val anaphora: Regex,
    val months: Regex,
    val inst: Regex,
    val numWords: Map<String, Int>,
    val isPast: (String) -> Boolean
) {
    val hasCase: Boolean = code != "ar"

    fun lower(s: String): String {
        val l = s.lowercase(locale)
        return if (code == "ar") AR_MARKS.replace(l, "") else l
    }

    fun stem(w: String): String {
        var x = w
        when (code) {
            "ar" -> {
                for (pre in AR_PRE) if (x.length > pre.length + 2 && x.startsWith(pre)) { x = x.substring(pre.length); break }
            }
            "en" -> for (suf in EN_SUF) {
                if (x.length > suf.length + 3 && x.endsWith(suf)) { x = x.dropLast(suf.length); break }
            }
            "es", "fr" -> if (x.length > 5 && x.endsWith("s")) x = x.dropLast(1)
        }
        return if (x.length > stemLen) x.substring(0, stemLen) else x
    }

    companion object {
        val AR_MARKS = Regex("[\u064B-\u065F\u0670\u0640]")
        private val AR_PRE = listOf("وال", "بال", "لل", "ال", "و")
        private val EN_SUF = listOf("ations", "ation", "ings", "ing", "ies", "ed", "es", "s", "ly")
    }
}

private fun rx(s: String) = Regex(s)
private fun words(s: String): Set<String> = s.split(' ', '\n').filter { it.isNotBlank() }.toHashSet()
private fun start(s: String) = Regex("^(?:$s)(?=[\\s,،]|$)")
private fun month(s: String) = Regex("(?<![\\p{L}])(?:$s)(?![\\p{L}])")

object Langs {
    private val TR_LOC = Locale("tr", "TR")

    val TR = LangPack(
        code = "tr", locale = TR_LOC, stemLen = 5,
        stop = words(
            """ve veya ile için ama fakat ancak gibi kadar daha çok bir bu şu da de ki mi mı mu mü ne ya ise her hem hiç bütün tüm
            olarak olan oldu olur olması olduğu olduğunu var yok ben sen biz siz onlar bunu buna bunun bunlar şunu onu ona onun
            kendi kendini sonra önce göre karşı dolayı üzere başka diye değil bile artık yani çünkü eğer hatta ayrıca oysa sadece
            yalnız iken idi imiş edilir edilen edilmiş yapılan yapılır yapar eder etti etmek yapmak olmak bulunan bulunur içinde
            üzerinde arasında tarafından sırasında nedeniyle ilgili bazı birçok herhangi hangi nasıl neden nerede kim şey zaman
            dedi diyor bana sana bize size beni seni bizi sizi onları benim senin bizim sizin onların""" ),
        generic = words(
            """yeni çalışan belirli farklı önemli kullanılan kullanım günümüzde örnek genel çeşitli bazen gerekir mümkün
            sürekli özellikle gerçek doğru yüksek düşük ilk son iki üç dört beş durum konu hale yer yıl gün kişi şekilde şekil
            yoluyla sayesinde yaklaşık civarı genel giriş sonuç özet türleri türü olarak ortaya çıkan yapılması edilmesi kullanılır kullanılarak sağlar sağlayan gösterir bulunmaktadır
            bulunuyor içerisinde birlikte tarafı halde açıdan yönde
            hemen genellikle yalnızca neredeyse mevcut meydana gelir gelen oluşur oluşan oluşturan oluşmaktadır gerçekleşir gerçekleşen
            görülür görülen denir verilir ayrılır ayrılan şunlardır belirtmiştir sürmüş yeryüzü"""),
        abbr = words(
            """dr prof doç yrd av sn no bkz örn st mah cad sok apt tel müh uzm öğr gör alb gen bşk hz m.ö m.s a.ş ltd şti org çev ed
            yay krş mö ms"""),
        markers = words("ve bir bu için ile olarak olan daha çok gibi ancak veya ise kadar her ki bunun sonra"),
        def = rx(
            "denir|denilir|adı verilir|adlandırılır|tanımlanır|tanımıdır|tanımı|demektir|anlamına gelir|anlamındadır|" +
                "olarak bilinir|ifade eder|özelliği|özellikleri|önemlidir|önem taşır|unutulmamalıdır|dikkat|" +
                "sonuç olarak|kısaca|özetle|başlıca|en önemli|amacı|görevi|nedeni|sebebi|formül|kural|ilke"
        ),
        classCue = rx("(iki|üç|dört|beş|altı)\\s+(alt\\s+)?(aşama|evre|bölüm|grup|tür|çeşit|kısım|basamak|ana|yöntem|yol)|(aşama|evre|bölüm|grup|tür|çeşit|kısım)(dan|den)\\s+oluş|şunlardır|şöyledir|türleri|türü|türe ayrılır|ayrılır|ayrılmaktadır|sınıflandırılır|sınıflandırılabilir|sınıflandırma|çeşit|kategori|gruba|gruplara|başlıca|temel olarak|üç ana|dört ana|iki ana|şu şekilde|aşağıdaki"),
        cause = rx("çünkü|bu nedenle|bu yüzden|bu sebeple|sonucunda|neden olur|yol açar|dolayısıyla|nedeniyle|sayesinde|bu sayede"),
        compare = rx("oysa|buna karşılık|aksine|farklı olarak|benzer şekilde|kıyasla|karşılaştırıldığında|tersine|öte yandan|diğer yandan"),
        example = rx("örneğin|mesela|örnek olarak|bir örnek"),
        conclusion = rx("sonuç olarak|sonuçta|özetle|kısacası|kısaca|görüldüğü gibi|bu bağlamda|çıkarım"),
        storyCue = rx(
            "birdenbire|aniden|bir anda|o anda|tam o sırada|o sırada|sonunda|nihayet|meğer|işte o zaman|ilk kez|son kez|" +
                "kaçtı|kaçmak|öldü|öldür|ölüm|cinayet|kavga|savaş|saldır|yakalandı|yakaladı|itiraf|gerçek|sır|ihanet|" +
                "karar verdi|ayrıldı|evlen|doğdu|kayboldu|kayıp|buldu|bulundu|ortaya çık|anladı|fark etti|hapse|firar|" +
                "kurtul|vurdu|vuruldu|bağırdı|çığlık|yemin|veda|intikam"
        ),
        contrastStart = rx("^(ancak|fakat|ama|birden|aniden|sonunda|nihayet|meğer|oysa|artık|ne var ki|tam o sırada)[\\s,]"),
        anaphora = start("bu|bunlar|bunun|buna|şu|onlar|bunu|böylece|ayrıca|ancak|fakat|ama|ise|dolayısıyla|bu nedenle|bu yüzden"),
        months = month("ocak|şubat|mart|nisan|mayıs|haziran|temmuz|ağustos|eylül|ekim|kasım|aralık"),
        inst = rx("üniversites|bakanlığ|bakanlık|kurumu|şirketi|enstitü|belediye|birliği|derneği|vakfı|bankası|akademi|başkanlığı|müdürlüğü|komisyon|konsey|teşkilat"),
        numWords = mapOf("iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5, "altı" to 6, "yedi" to 7, "sekiz" to 8),
        isPast = { w -> PAST_TR.matches(w) }
    )
    private val PAST_TR = Regex("^\\p{L}{2,}([dt][ıiuü]|m[ıiuü]ş)(m|n|k|nız|niz|nuz|nüz|lar|ler)?$")

    val EN = LangPack(
        code = "en", locale = Locale.ENGLISH, stemLen = 6,
        stop = words(
            """the an and or but if of to in on at by for with from as is are was were be been being it its this that these those he she
            they we you his her their our your my not no so than then there here which who whom whose what when where why how can could
            will would shall should may might must do does did have has had also such into over under about between through during
            more most other some any each both all very just only one two however thus while after before because since until per via
            upon within without among""" ),
        generic = words(
            """new work working works use used using different important various many several general common example way ways make made
            makes number part case large small high low first second third last thing things time like well based include includes
            including called known often usually certain specific given ability able help helps allow allows provide provides approximately about around nearly overview introduction conclusion summary types type kinds"""),
        abbr = words("mr mrs ms dr prof sr jr st vs etc e.g i.e fig no inc ltd co corp dept approx est vol u.s u.k a.m p.m"),
        markers = words("the and of to is that for with as are was this by on from be which have has it"),
        def = rx(
            "(is|are) (a|an|the) [^.]{0,60}(that|which|used|of)|is defined as|are defined as|refers to|is known as|are known as|" +
                "is called|are called|is the (process|study|science)|stands for|definition|is characterized by|are characterized by|" +
                "is essential|is important|are important|main purpose|goal of|formula|principle"
        ),
        classCue = rx("types of|kinds of|categories|classified|classification|divided into|can be (grouped|categorized)|falls into|(two|three|four|five|six) (main |major |different |basic )?(types|kinds|categories|forms|classes|groups|methods|approaches)|the following"),
        cause = rx("because|therefore|thus|hence|as a result|consequently|due to|leads to|led to|results in|caused by|causes|so that|owing to"),
        compare = rx("whereas|in contrast|on the other hand|compared to|compared with|unlike|similarly|versus|differs from|different from"),
        example = rx("for example|for instance|e\\.g\\.|such as|an example|to illustrate"),
        conclusion = rx("in conclusion|to conclude|in summary|to summarize|overall|in short|in essence|to sum up|conclusion"),
        storyCue = rx(
            "suddenly|at that moment|finally|at last|died|killed|murder|fight|war|attack|escaped|caught|confess|secret|betray|decided|" +
                "left|born|disappeared|found|realized|noticed|prison|shot|screamed|oath|farewell|revenge|first time|last time"
        ),
        contrastStart = rx("^(but|however|suddenly|finally|at last|yet|until|then)[\\s,]"),
        anaphora = start("this|these|that|those|it|they|he|she|however|also|therefore|thus|moreover|in addition|additionally|furthermore"),
        months = month("january|february|march|april|june|july|august|september|october|november|december"),
        inst = rx("university|institute|ministry|company|corporation|agency|organization|organisation|association|bank|council|commission|foundation|academy|department"),
        numWords = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8),
        isPast = { w -> (w.length >= 4 && w.endsWith("ed")) || w in PAST_EN }
    )
    private val PAST_EN = words("was were had said went came saw took made knew thought felt told found gave left began ran stood heard")

    val DE = LangPack(
        code = "de", locale = Locale.GERMAN, stemLen = 6,
        stop = words(
            """der die das den dem des ein eine einen einem einer eines und oder aber wenn als wie auch nicht kein keine ist sind war waren
            wird werden wurde wurden hat haben hatte hatten sein seine ihre ihr er sie es wir ich du von zu zum zur mit bei nach aus auf an
            in im um für über unter vor durch gegen ohne bis dass daß so nur noch schon sehr mehr dieser diese dieses diesem diesen dabei
            dann denn doch weil kann können muss müssen soll sollen man sich bzw"""),
        generic = words(
            """neue neuen neues arbeit verwendet verwenden unterschiedliche verschiedene wichtig wichtige viele mehrere allgemein beispiel
            beispiele weise zahl teil fall große groß kleine klein hoch erste ersten zweite letzte dinge zeit genannt bekannt oft meist
            bestimmte gibt geben gemacht machen möglich etwa ungefähr circa überblick einführung fazit zusammenfassung arten typen"""),
        abbr = words("z.b d.h u.a usw bzw ca nr dr prof vgl ggf inkl evtl str abb"),
        markers = words("der die das und ist nicht ein eine mit von zu den auf für sich auch wird sind dass dem des"),
        def = rx("ist ein |ist eine |wird als .{0,40}bezeichnet|bezeichnet|versteht man|definiert|definition|bedeutet|heißt|heißen|genannt|ist wichtig|sind wichtig|hauptsächlich|zweck|ziel|formel|regel|prinzip"),
        classCue = rx("arten|typen|kategorien|klassifiziert|klassifikation|unterteilt|eingeteilt|gliedert|(zwei|drei|vier|fünf|sechs) (haupt)?(arten|typen|formen|kategorien|klassen|gruppen)|folgende"),
        cause = rx("weil|deshalb|daher|folglich|dadurch|aufgrund|führt zu|verursacht|infolge|somit|deswegen"),
        compare = rx("während|im gegensatz|dagegen|hingegen|verglichen mit|im vergleich|ähnlich|unterscheidet sich|im unterschied|andererseits"),
        example = rx("zum beispiel|z\\. ?b\\.|beispielsweise|ein beispiel"),
        conclusion = rx("zusammenfassend|abschließend|insgesamt|fazit|kurz gesagt|schlussfolgerung|zum schluss"),
        storyCue = rx(
            "plötzlich|in diesem moment|schließlich|endlich|starb|tötete|mord|kampf|krieg|angriff|floh|gefangen|gestand|geheimnis|verrat|" +
                "beschloss|verließ|geboren|verschwand|fand|erkannte|bemerkte|gefängnis|erschoss|schrie|schwur|abschied|rache"
        ),
        contrastStart = rx("^(aber|doch|plötzlich|schließlich|endlich|jedoch|dann)[\\s,]"),
        anaphora = start("dies|diese|dieser|dieses|das|es|sie|er|jedoch|auch|daher|deshalb|außerdem|zudem"),
        months = month("januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember"),
        inst = rx("universität|institut|ministerium|unternehmen|gesellschaft|organisation|verband|bank|kommission|stiftung|akademie|behörde"),
        numWords = mapOf("zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8),
        isPast = { w -> w in PAST_DE || (w.length >= 5 && (w.endsWith("te") || w.endsWith("ten"))) }
    )
    private val PAST_DE = words("war waren hatte hatten sagte ging kam sah nahm wurde wurden dachte fand gab stand lief")

    val ES = LangPack(
        code = "es", locale = Locale("es"), stemLen = 6,
        stop = words(
            """el la los las un una unos unas y o pero si de del al a en con por para sin sobre entre hasta desde como que es son fue
            fueron era eran ser estar está están ha han había se su sus lo le les me te nos mi tu este esta esto estos estas ese esa eso
            muy más menos también cuando donde porque cada otro otra otros otras todo todos toda todas puede pueden hay ya no sí así
            aunque sino entonces"""),
        generic = words(
            """nuevo nueva nuevos trabajo uso usado usan usar diferentes importante importantes varios varias muchos muchas general común
            ejemplo forma manera número parte caso grande grandes pequeño alto bajo primero primera segundo último cosa cosas tiempo
            llamado conocido permite través ciertos específico hacer hace aproximadamente alrededor introducción conclusión resumen tipos descripción"""),
        abbr = words("sr sra dr dra prof etc pág vs ud uds núm av aprox ej"),
        markers = words("el los las que y una por con para es del se su como más al lo"),
        def = rx("se define como|se denomina|se llama|se conoce como|es un |es una |significa|consiste en|se refiere a|definición|es importante|son importantes|principal|objetivo|propósito|fórmula|regla|principio"),
        classCue = rx("tipos de|clases de|categorías|clasifica|clasificación|se divide|dividen en|se agrupa|(dos|tres|cuatro|cinco|seis) (tipos|clases|categorías|formas|grupos|métodos)|siguientes"),
        cause = rx("porque|por lo tanto|por eso|debido a|como resultado|en consecuencia|provoca|causa|conduce a|gracias a|así que"),
        compare = rx("mientras que|en cambio|por el contrario|en comparación|a diferencia|de manera similar|por otro lado|se diferencia|versus"),
        example = rx("por ejemplo|p\\. ?ej\\.|como ejemplo|un ejemplo|tal como"),
        conclusion = rx("en conclusión|en resumen|para concluir|en definitiva|en suma|por último"),
        storyCue = rx(
            "de repente|en ese momento|finalmente|por fin|murió|mató|asesinato|pelea|guerra|ataque|escapó|capturado|confesó|secreto|traición|" +
                "decidió|dejó|nació|desapareció|encontró|se dio cuenta|notó|prisión|disparó|gritó|juró|despedida|venganza"
        ),
        contrastStart = rx("^(pero|sin embargo|de repente|finalmente|por fin|entonces)[\\s,]"),
        anaphora = start("esto|esta|estos|estas|eso|ello|ellos|él|ella|sin embargo|también|por lo tanto|además"),
        months = month("enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre"),
        inst = rx("universidad|instituto|ministerio|empresa|compañía|organización|asociación|banco|consejo|comisión|fundación|academia"),
        numWords = mapOf("dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8),
        isPast = { w ->
            w in PAST_ES || (w.length >= 4 && (w.endsWith("ó") || w.endsWith("aron") || w.endsWith("ieron") ||
                w.endsWith("aba") || w.endsWith("ía") || w.endsWith("aban") || w.endsWith("ían")))
        }
    )
    private val PAST_ES = words("fue fueron era eran tuvo dijo hizo")

    val FR = LangPack(
        code = "fr", locale = Locale.FRENCH, stemLen = 6,
        stop = words(
            """le la les un une des du de d l et ou mais si que qui quoi dont où ce cet cette ces est sont était étaient été être a ont avait
            avaient avoir il elle ils elles on nous vous je tu son sa ses leur leurs mon ma mes dans en sur sous avec sans pour par au aux à
            plus moins très aussi comme ne pas ni se y cela ça ceci afin lors ainsi donc car alors entre vers chez tout tous toute toutes
            autre autres peut peuvent fait faire"""),
        generic = words(
            """nouveau nouvelle nouveaux travail utilisé utilisée utilisés utiliser différents différentes important importante importants
            divers diverses nombreux plusieurs général commun exemple façon manière nombre partie cas grand grande grands petit petite
            haut bas premier première second dernier chose choses temps appelé connu souvent certains certaines spécifique permet environ approximativement presque introduction conclusion résumé types aperçu"""),
        abbr = words("m mme mlle dr pr prof etc cf p vol fig env av no ex st ste bd"),
        markers = words("le les des et du une est qui dans pour pas sur au avec sont ne ce cette"),
        def = rx("est défini|est un |est une |on appelle|appelé|désigne|signifie|consiste en|se définit|définition|est important|principal|objectif|formule|règle|principe"),
        classCue = rx("types de|sortes de|catégories|classé|classification|se divise|divisé en|répartis|(deux|trois|quatre|cinq|six) (types|catégories|formes|classes|groupes|méthodes)|suivants"),
        cause = rx("parce que|donc|par conséquent|en raison de|à cause de|entraîne|provoque|résulte|grâce à|c'est pourquoi"),
        compare = rx("tandis que|alors que|en revanche|par contre|contrairement|comparé|par rapport|de même|d'autre part|se distingue"),
        example = rx("par exemple|à titre d'exemple|un exemple|tel que|notamment"),
        conclusion = rx("en conclusion|en résumé|pour conclure|en somme|bref|en définitive"),
        storyCue = rx(
            "soudain|à ce moment|enfin|finalement|mourut|tua|meurtre|combat|guerre|attaque|s'enfuit|arrêté|avoua|secret|trahison|décida|" +
                "quitta|naquit|disparut|trouva|réalisa|remarqua|prison|tira|cria|jura|adieu|vengeance"
        ),
        contrastStart = rx("^(mais|pourtant|soudain|enfin|finalement|alors)[\\s,]"),
        anaphora = start("cela|ceci|ces|cette|ce|ils|elle|il|cependant|aussi|donc|de plus|en outre"),
        months = month("janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre"),
        inst = rx("université|institut|ministère|entreprise|société|organisation|association|banque|conseil|commission|fondation|académie"),
        numWords = mapOf("deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8),
        isPast = { w ->
            w in PAST_FR || (w.length >= 5 && (w.endsWith("ait") || w.endsWith("aient") || w.endsWith("èrent") ||
                w.endsWith("irent") || w.endsWith("urent")))
        }
    )
    private val PAST_FR = words("fut était avait dit fit vit prit alla vint")

    val AR = LangPack(
        code = "ar", locale = Locale("ar"), stemLen = 5,
        stop = words(
            """في من على إلى الى عن مع هذا هذه ذلك تلك هو هي هم هن أن إن ان كان كانت يكون تكون التي الذي الذين اللذين ما لا لم لن قد ثم أو او و
            بين عند كل بعض أي أيضا أيضًا كما حيث بعد قبل خلال حتى لكن غير هناك هنا إذا اذا عندما بل نحو مثل فقط جدا عليه عليها منه منها به
            بها له لها فيه فيها ذات أنه أنها إنه لأن لان حول ضد دون منذ يمكن يتم تم وقد وهو وهي وفي ومن وعلى وإلى وكان"""),
        generic = words(
            """جديد جديدة عمل استخدام مختلفة مختلف مهم مهمة عديد كثير عدة عام عامة مثال طريقة عدد جزء حالة كبير كبيرة صغير أول أولى ثاني آخر
            شيء وقت يسمى معروف غالبا معين تقريبا مقدمة خاتمة ملخص أنواع"""),
        abbr = words("د أ"),
        markers = emptySet(),
        def = rx("هو |هي |يعرف|تعرف|يسمى|تسمى|يعني|تعني|يشير إلى|عبارة عن|تعريف|مهم|الهدف|القاعدة|المبدأ|الرئيسي"),
        classCue = rx("أنواع|تصنيف|يصنف|تنقسم|ينقسم|يقسم|فئات|أقسام|ثلاثة|أربعة|خمسة|نوعان|التالية"),
        cause = rx("لأن|لذلك|بسبب|نتيجة|مما يؤدي|يؤدي إلى|وبالتالي|بالتالي|ولهذا|نظرا"),
        compare = rx("بينما|في المقابل|على عكس|مقارنة|بالمقارنة|من ناحية أخرى|على النقيض|يختلف عن"),
        example = rx("مثلا|على سبيل المثال|كمثال|مثال"),
        conclusion = rx("في الختام|خلاصة|باختصار|في النهاية|نستنتج|الخلاصة|ختاما"),
        storyCue = rx(
            "فجأة|في تلك اللحظة|أخيرا|مات|قتل|جريمة|قتال|حرب|هجوم|هرب|قبض|اعترف|خيانة|قرر|غادر|ولد|اختفى|وجد|أدرك|لاحظ|سجن|صرخ|أقسم|وداع|انتقام"
        ),
        contrastStart = rx("^(لكن|ولكن|فجأة|أخيرا|بيد أن|غير أن)[\\s،]"),
        anaphora = start("هذا|هذه|هؤلاء|ذلك|تلك|هو|هي|لكن|أيضا|لذلك|كما"),
        months = month("يناير|فبراير|مارس|أبريل|مايو|يونيو|يوليو|أغسطس|سبتمبر|أكتوبر|نوفمبر|ديسمبر|كانون|شباط|آذار|نيسان|أيار|حزيران|تموز|آب|أيلول|تشرين"),
        inst = rx("جامعة|معهد|وزارة|شركة|منظمة|هيئة|جمعية|بنك|مجلس|لجنة|مؤسسة|أكاديمية"),
        numWords = mapOf("ثلاثة" to 3, "أربعة" to 4, "خمسة" to 5, "ستة" to 6, "سبعة" to 7, "ثمانية" to 8, "نوعان" to 2, "اثنان" to 2, "ثلاث" to 3, "أربع" to 4),
        isPast = { w -> w in PAST_AR }
    )
    private val PAST_AR = words("كان قال ذهب جاء رأى كانت قالت ذهبت جاءت")

    val all: List<LangPack> = listOf(TR, EN, DE, ES, FR, AR)

    fun byCode(c: String): LangPack = all.firstOrNull { it.code == c } ?: TR

    /** Belgenin dilini algılar; emin değilse null döner (varsayılan olarak Türkçe paketi kullanılır). */
    fun detect(raw: String): LangPack? {
        val sample = if (raw.length > 30000) raw.substring(0, 30000) else raw
        var letters = 0
        var arabic = 0
        for (ch in sample) {
            if (ch.isLetter()) {
                letters++
                if (ch.code in 0x0600..0x06FF) arabic++
            }
        }
        if (letters == 0) return null
        if (arabic.toDouble() / letters > 0.4) return AR
        val toks = Regex("\\p{L}+").findAll(sample.lowercase(Locale.ROOT)).map { it.value }.take(3000).toList()
        if (toks.size < 8) return null
        var best: LangPack? = null
        var bestScore = 0.0
        for (p in all) {
            if (p.markers.isEmpty()) continue
            var hit = 0
            for (t in toks) if (t in p.markers) hit++
            var sc = hit.toDouble() / toks.size
            val lowSample = sample.lowercase(Locale.ROOT)
            if (p.code == "tr") sc += 0.02 * (lowSample.count { it == 'ı' || it == 'ğ' || it == 'ş' }.toDouble() / sample.length * 20).coerceAtMost(1.0)
            if (p.code == "de") sc += 0.02 * (lowSample.count { it == 'ß' }.toDouble() / sample.length * 100).coerceAtMost(1.0)
            if (p.code == "es") sc += 0.02 * (lowSample.count { it == 'ñ' || it == '¿' || it == '¡' }.toDouble() / sample.length * 100).coerceAtMost(1.0)
            if (sc > bestScore) { bestScore = sc; best = p }
        }
        return if (bestScore >= 0.05) best else null
    }
}
