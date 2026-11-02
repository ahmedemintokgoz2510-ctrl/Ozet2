package com.ozet.app.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * Gerçek örnek dosyalarla (src/test/resources/fixtures) uçtan uca doğrulama.
 * Android gerektirmez: `gradle testDebugUnitTest` ile JVM'de çalışır.
 */
class EngineTest {
    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private fun resText(name: String) = String(res(name), Charsets.UTF_8)

    private fun zipParts(name: String, accept: (String) -> Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        ZipInputStream(res(name).inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (accept(e.name)) m[e.name] = String(z.readBytes(), Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return m
    }

    private fun summarize(raw: String): Result {
        val prepared = runBlocking { DeepTextProcessor.prepare(raw) }
        return Summarizer.summarize(prepared, Mode.AUTO, Length.MEDIUM)
    }

    private fun Result.allText() = sections.joinToString("\n") { (it.title ?: "") + "\n" + it.items.joinToString("\n") }

    // ---------------------------------------------------------------- TXT

    @Test fun txt_keepsStructureAndAllSections() {
        val r = summarize(resText("enerji.txt"))
        val t = r.allText()
        assertTrue("birden çok cümle ayrıştırılmalı (eski hata: tek cümle)", r.sentenceCount >= 15)
        for (h in listOf("Giriş", "Mevcut Durum", "Zorluklar", "Fırsatlar")) assertTrue("bölüm eksik: $h", t.contains(h))
        assertTrue(t.contains("2030 hedefi: 150 GW"))
        assertTrue(t.contains("%65"))
        assertTrue("Türkçe karakterler", t.contains("Rüzgâr") && t.contains("Şebeke".lowercase()) || t.contains("şebeke"))
    }

    @Test fun txt_keywordsAreMeaningfulAndTurkishAware() {
        val k = summarize(resText("enerji.txt")).keywords
        assertFalse("Türkiye kelimesi kesilmemeli", k.any { it == "Türki" })
        assertFalse("bölüm başlığı anahtar kelime olmamalı", k.any { it.equals("FIRSATLAR", true) })
    }

    // ---------------------------------------------------------------- DOCX

    @Test fun docx_trackedChangeCommentListsAndTable() {
        val text = DocxReader.read(zipParts("test5_politika.docx") { it in DocxReader.PARTS })
        assertTrue("silinen '4' değil eklenen '3' gelmeli", text.contains("haftada 3 gün"))
        assertFalse("yorum metni içeriğe karışmamalı", text.contains("kesinleşmedi"))
        assertTrue("numaralı liste korunmalı", text.contains("1. Çalışan, İK portalından") && text.contains("5. Uzaktan çalışma"))
        assertTrue("madde işaretleri", text.contains("• VPN bağlantısı"))
        assertTrue("başlıklı tablo satırı", text.contains("Kalem: Harici monitör | Destek Türü: Tek seferlik katkı | Tutar: 3.500 ₺"))
        assertFalse("üst/alt bilgi dahil edilmemeli", text.contains("Dahili – İnsan Kaynakları"))
    }

    @Test fun docx_summaryContainsKeyRules() {
        val raw = DocxReader.read(zipParts("test5_politika.docx") { it in DocxReader.PARTS })
        val t = summarize(raw).allText()
        for (needle in listOf("3 gün", "6 ay", "5 iş günü", "VPN", "10.800")) assertTrue("özette yok: $needle", t.contains(needle))
    }

    // ---------------------------------------------------------------- PPTX

    @Test fun pptx_readsNotesChartTableAndOrder() {
        val text = PptxReader.read(zipParts("test4_lansman.pptx") { PptxReader.accepts(it) })
        assertTrue("konuşmacı notu", text.contains("ilk 2.000 siparişe hediye kayış"))
        assertTrue("sadece notta geçen etkinlik", text.contains("CES Türkiye"))
        assertTrue("grafik verisi", text.contains("1. Çeyrek 12.000") && text.contains("4. Çeyrek 41.000"))
        assertTrue("tablo", text.contains("Kanal: Dijital reklam | Bütçe (₺): 450.000 | Pay: %39"))
        assertTrue("değer/etiket eşleşmesi", text.contains("%27: Giyilebilir cihaz pazarının yıllık büyümesi"))
        assertTrue("slayt sırası", text.indexOf("Neden Şimdi?") < text.indexOf("Zaman Çizelgesi"))
        assertTrue("sonda kesilmemeli", text.trimEnd().endsWith("sürüyor."))
    }

    @Test fun pptx_summaryKeepsDecision() {
        val raw = PptxReader.read(zipParts("test4_lansman.pptx") { PptxReader.accepts(it) })
        val t = summarize(raw).allText()
        assertTrue(t.contains("1.150.000"))
        assertTrue(t.contains("15 Ocak 2027"))
    }

    // ---------------------------------------------------------------- PDF (sayfa metni üzerinden)

    @Test fun pdf_removesRepeatingHeaderFooterAndBuildsTables() {
        val pages = resText("rapor_pdf_sayfalar.txt").split("\n=====PAGE=====\n")
        val cleaned = TabularText.convert(PageCleaner.joinWrappedBullets(PageCleaner.strip(pages)))
        assertFalse(cleaned.contains("GİZLİ – Dahili Kullanım"))
        assertFalse(cleaned.contains("Sayfa 2"))
        assertTrue(cleaned.contains("Modül: Raporlama Paneli | Sorumlu: Can Öztürk | Plan: %50 | Gerçekleşen: %30 | Durum: Kırmızı"))
        assertTrue("sarkan madde devamı birleşmeli", cleaned.contains("Önlem: Tedarikçi ile haftalık görüşme"))
        val t = summarize(cleaned).allText()
        assertTrue(t.contains("250.000 ₺"))
        assertTrue(t.contains("Yönetim kurulundan üç karar"))
    }

    // ---------------------------------------------------------------- E-posta

    @Test fun email_structureSignatureDisclaimerAndQuote() {
        val prepared = runBlocking { DeepTextProcessor.prepare(resText("email.txt")) }
        assertTrue(prepared.startsWith("# ACİL - Sipariş #48213 gecikmesi"))
        assertFalse("imza", prepared.contains("0212 555 01 23"))
        assertFalse("yasal uyarı", prepared.contains("gizlidir"))
        assertFalse("e-posta adresi", prepared.contains("@"))
        assertFalse("emoji", prepared.contains("😡"))
        assertTrue("URL alan adına iner", prepared.contains("kargo.example.com →") && !prepared.contains("https://"))
        assertTrue("önceki yazışma ayrı bölüm", prepared.contains("# Önceki yazışma") && prepared.contains("depodaki sayım"))
    }

    @Test fun email_summaryKeepsCriticalFacts() {
        val r = summarize(resText("email.txt"))
        val t = r.allText()
        for (needle in listOf("#48213", "12.480 ₺", "14 Kasım", "refund", "2 Kasım", "17:00", "depodaki sayım")) assertTrue("eksik: $needle", t.contains(needle))
        assertTrue("ACİL Türkçe büyük harfle", r.keywords.contains("ACİL"))
    }

    // ---------------------------------------------------------------- Excel modeli

    private fun t(s: String) = SCell(SCell.Kind.TEXT, text = s)
    private fun n(v: Double, f: String? = null) = SCell(SCell.Kind.NUM, num = v, grouping = true, formula = f)

    @Test fun sheet_totalRowIsNotCountedAsData() {
        val data = listOf(Triple("İstanbul", 100.0, 10.0), Triple("Ankara", 200.0, 20.0), Triple("İzmir", 300.0, 30.0))
        val rows = ArrayList<List<SCell?>>()
        rows.add(listOf(t("Bölge"), t("Satış"), t("Adet")))
        data.forEachIndexed { i, d -> rows.add(listOf(t(d.first), n(d.second), n(d.third))) }
        rows.add(listOf(t("GENEL TOPLAM"), n(600.0, "SUM(B2:B4)"), n(60.0, "SUM(C2:C4)")))
        val text = SheetRenderer.render(listOf(SGrid("Veri", false, rows)))
        assertTrue("toplam 600 (1200 değil)", text.contains("Satış sütunu: toplam 600"))
        assertFalse(text.contains("1.200"))
        assertTrue("toplam satırı ayrı cümle", text.contains("GENEL TOPLAM: Satış 600; Adet 60."))
        assertTrue("en yüksek satır etiketiyle", text.contains("en yüksek 300 (İzmir)"))
        assertFalse("toplam satırı veri satırı olmamalı", text.contains("| Bölge: GENEL TOPLAM"))
    }

    @Test fun sheet_totalRowDetectedByFormulaEvenWithoutLabel() {
        val rows = listOf(
            listOf(t("Kalem"), t("Tutar")), listOf(t("A"), n(10.0)), listOf(t("B"), n(20.0)),
            listOf(t("Toplamlar"), n(30.0, "SUM(B2:B3)"))
        )
        val text = SheetRenderer.render(listOf(SGrid("S", false, rows)))
        assertTrue(text.contains("toplam 30"))
    }

    @Test fun sheet_percentAndHiddenSheets() {
        val pct = SCell(SCell.Kind.PCT, num = 0.35365782, decimals = 1)
        val rows = listOf(listOf(t("Bölge"), t("Pay")), listOf(t("İstanbul"), pct), listOf(t("Ankara"), SCell(SCell.Kind.PCT, num = 0.25, decimals = 1)))
        val text = SheetRenderer.render(listOf(SGrid("Özet", false, rows), SGrid("Gizli", true, rows)))
        assertTrue(text.contains("Pay: %35,4"))
        assertFalse("gizli sayfa atlanmalı", text.contains("# Gizli"))
    }

    @Test fun csv_delimiterQuotesAndNumbers() {
        val csv = "Ürün;Fiyat;Not\nKalem A;1.250,50;\"Tırnak; içinde\"\nKalem B;980,00;düz\n"
        val g = CsvParser.toGrid(csv)
        val text = SheetRenderer.render(listOf(g))
        assertTrue(text.contains("Fiyat: 1.250,50"))
        assertTrue(text.contains("Not: Tırnak; içinde"))
        assertTrue(text.contains("toplam 2.230,5"))
    }

    // ---------------------------------------------------------------- RTF / HTML / kodlama

    @Test fun rtf_keepsTurkishLetters() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1254{\\fonttbl{\\f0 Arial;}}\\f0 \\'dd\\'fe\\'e7\\'f0\\'fc\\'f6 ve \\u350?irket\\par Yeni sat\\'fdr}"
        val out = RtfReader.read(rtf)
        assertTrue(out, out.contains("İşçğüö ve Şirket"))
        assertTrue(out.contains("\nYeni satır"))
        assertFalse(out.contains("Arial"))
    }

    @Test fun html_entitiesAndBlocks() {
        val out = HtmlReader.read("<html><head><title>x</title></head><body><h1>Başlık</h1><p>A &amp; B &#252; &ouml;</p><script>bad()</script></body></html>")
        assertEquals("Başlık\nA & B ü ö", out)
    }

    @Test fun decode_fallsBackToWindows1254() {
        val ansi = byteArrayOf(0xDD.toByte(), 0xFE.toByte(), 0xE7.toByte())   // İşç
        assertEquals("İşç", TextDecode.decode(ansi))
        assertEquals("İşç", TextDecode.decode("İşç".toByteArray(Charsets.UTF_8)))
    }
}
