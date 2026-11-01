# Özet (Android)

Kotlin + Jetpack Compose. Yapay zeka / API anahtarı yok; özetleme cihazda çalışır.

## Açmak için
1. Android Studio Koala (2024.1.1) veya üstü ile klasörü aç (File > Open).
2. Gradle senkronizasyonu bitince telefonu bağla ve Run.

## Sürümler
Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, JDK 17.
minSdk 26, targetSdk 34.

## Reklamlar
Reklamlar Google AdMob ile çalışır ve şu an Google'ın TEST kimliklerini kullanır (app/src/main/res/values/strings.xml):
admob_app_id, admob_banner_id (alt banner), admob_working_id (10 sn'lik bekleme ekranı reklamı, 300x250).
Kullanıcı rızası (UMP) uygulama açılışında istenir; rıza yoksa reklam yüklenmez.
Gerçek kimlikler için: AdMob'da uygulamayı ekle, iki banner reklam birimi oluştur, kimlikleri strings.xml'e yaz,
UMP mesajını AdMob > Gizlilik ve mesajlaşma bölümünden yayınla. Kendi gerçek reklamlarına test cihazında tıklama.

## Diller
Türkçe, İngilizce, Almanca, İspanyolca, Arapça (RTL), Fransızca. Metinler app/src/main/java/com/ozet/app/i18n/Str*.kt dosyalarındadır.
Özet, belge dilinde çıkarılır; farklı bir dil seçiliyse cihaz üzerinde ML Kit ile çevrilir (ilk kez Wi-Fi ve dil paketi gerekir).

## Dosya yapısı
- engine/Extractor.kt  : PDF, DOCX, TXT okuma
- engine/Summarizer.kt : özetleme algoritması
- MainViewModel.kt     : 10 sn bekleme + arka planda özetleme
- ui/App.kt            : ekranlar
