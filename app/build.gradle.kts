import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ozet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ozet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Yayın imzası: keystore.properties dosyasından ya da ortam değişkenlerinden (GitHub Secrets) okunur.
    // Hiçbiri yoksa release imzasız üretilir (debug derlemesi etkilenmez).
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun ks(key: String, env: String): String? = ksProps.getProperty(key) ?: System.getenv(env)
    val ksFile = ks("storeFile", "OZET_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ks("storePassword", "OZET_KEYSTORE_PASSWORD")
                keyAlias = ks("keyAlias", "OZET_KEY_ALIAS")
                keyPassword = ks("keyPassword", "OZET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Uygulama içi dil seçimi var: dil bölmesi açık olursa seçilmeyen dillerin kaynakları AAB'den düşer.
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-ads:23.2.0")
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")   // eski .doc / .ppt

    testImplementation("junit:junit:4.13.2")
}
