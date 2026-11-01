package com.ozet.app

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
    }
}
