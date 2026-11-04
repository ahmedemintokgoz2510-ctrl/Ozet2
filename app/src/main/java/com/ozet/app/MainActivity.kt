package com.ozet.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.ozet.app.ui.AdsGate
import com.ozet.app.ui.App
import com.ozet.app.ui.OzetTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdsGate.start(this)
        setContent {
            OzetTheme { App(vm) }
        }
        // Ekran döndürmede aynı paylaşımı ikinci kez işleme
        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShared(intent)
    }

    /** Başka bir uygulamadan "Paylaş" ya da "Birlikte aç" ile gelen dosya / metni özetlemeye başlar. */
    private fun handleShared(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> vm.openUri(stream)
                    !text.isNullOrBlank() -> vm.submitText(text)
                }
            }
            Intent.ACTION_VIEW -> i.data?.let { vm.openUri(it) }
        }
        // Aynı intent yeniden işlenmesin
        i.action = null
    }
}
