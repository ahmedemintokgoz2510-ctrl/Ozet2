# Özet (Android)

Kotlin + Jetpack Compose. Yapay zeka / API anahtarı yok; özetleme cihazda çalışır.

## Açmak için
1. Android Studio Koala (2024.1.1) veya üstü ile klasörü aç (File > Open).
2. Gradle senkronizasyonu bitince telefonu bağla ve Run.

## Sürümler
Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, JDK 17.
minSdk 26, targetSdk 34.

## Reklamlar
Reklamlar Google AdMob ile çalışır. Kimlikler app/src/main/res/values/strings.xml içindedir:
admob_app_id, admob_banner_id (üst ve alt banner, 320x50), admob_working_id (10 sn'lik bekleme ekranı, 300x250), admob_rewarded_id (ödüllü).
Yayın (release) derlemesi gerçek kimlikleri kullanır; debug derlemesi app/src/debug/res/values/strings.xml içindeki Google TEST kimliklerini kullanır.
Release derlemesinde dört kimliğin hepsi gerçektir (uygulama, banner, bekleme ekranı, ödüllü).
Kullanıcı rızası (UMP) uygulama açılışında istenir; rıza yoksa reklam yüklenmez. UMP mesajını AdMob > Gizlilik ve mesajlaşma bölümünden yayınla.

## Diller
Türkçe, İngilizce, Almanca, İspanyolca, Arapça (RTL), Fransızca. Metinler app/src/main/java/com/ozet/app/i18n/Str*.kt dosyalarındadır.
Özet, belge dilinde çıkarılır; farklı bir dil seçiliyse cihaz üzerinde ML Kit ile çevrilir (ilk kez Wi-Fi ve dil paketi gerekir).

## Dosya yapısı
- engine/Extractor.kt            : dosya türüne göre yönlendirme; PDF (sayfa bazlı, sütun boşluğu → tablo)
- engine/OfficeReaders.kt        : DOCX (başlık, liste, tablo, izlenen değişiklik) ve PPTX (not, grafik, tablo) okuyucuları
- engine/MiniXml.kt              : bağımlılıksız XML okuyucu + OOXML zip yardımcısı
- engine/SpreadsheetExtractor.kt : Excel/CSV → tablo modeli (POI bağdaştırıcısı)
- engine/SheetModel.kt           : tablo modeli, toplam satırı algılama, sütun istatistikleri, CSV ayrıştırıcı
- engine/PlainFormats.kt         : RTF ve HTML okuyucuları
- engine/LegacyOffice.kt         : eski .doc / .ppt okuyucuları (poi-scratchpad)
- engine/DeepTextProcessor.kt    : yapıyı koruyan temizlik, PDF üst/alt bilgi ayıklama, e-posta zinciri ayrıştırma
- engine/Summarizer.kt           : özetleme algoritması (TF-IDF + TextRank + özellik puanlama)
- MainViewModel.kt               : 10 sn bekleme + arka planda özetleme
- ui/App.kt                      : ekranlar

## Testler
`gradle testDebugUnitTest` — `app/src/test/resources/fixtures` içindeki gerçek örnek dosyalarla (Word, PowerPoint, PDF sayfa metni,
e-posta, TXT) ve tablo modeliyle uçtan uca doğrulama yapar. Android cihaz gerektirmez.
