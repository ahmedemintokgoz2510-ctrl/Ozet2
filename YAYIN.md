# Google Play'e yayın (AAB)

## 1. Bir kez: yükleme anahtarı (keystore) oluştur
Bilgisayarda (ya da Termux'ta) Java varsa:

    keytool -genkeypair -v -keystore ozet-release.jks -alias ozet -keyalg RSA -keysize 2048 -validity 10000

Sorulan şifreleri bir yere yaz. **Bu dosyayı ve şifreleri kaybetme, GitHub'a yükleme** (`.gitignore` .jks dosyasını zaten dışlar).
Play Console'da "Play Uygulama İmzalama"yı açık bırak: bu anahtar yalnızca *yükleme anahtarı* olur, kaybedilirse Google üzerinden sıfırlanabilir.

## 2. GitHub Secrets (Settings → Secrets and variables → Actions)
| Secret | Değer |
|---|---|
| `OZET_KEYSTORE_BASE64` | `base64 -w0 ozet-release.jks` çıktısı |
| `OZET_KEYSTORE_PASSWORD` | keystore şifresi |
| `OZET_KEY_ALIAS` | `ozet` |
| `OZET_KEY_PASSWORD` | anahtar şifresi |

## 3. AAB üret
Actions → "Build APK" → Run workflow. `bundle` işi bitince **ozet-release-aab** çıktısından `app-release.aab` dosyasını indir ve Play Console'a yükle.
Secrets yoksa AAB imzasız üretilir ve Play'e yüklenemez.

## 4. Yayından önce
- `app/src/main/res/values/strings.xml` içindeki 4 AdMob kimliğini (`admob_*`) gerçek kimliklerle değiştir (şu an Google test kimlikleri).
- Her yeni sürümde `app/build.gradle.kts` içinde `versionCode`'u artır.
- Play Console: gizlilik politikası URL'si, "Veri güvenliği" formu (AdMob reklam kimliği kullanılıyor), içerik derecelendirmesi ve reklam içerdiğini belirten beyan gerekir.
- Hedef API 36'dır (Google Play'in 31 Ağustos 2026 şartı).
