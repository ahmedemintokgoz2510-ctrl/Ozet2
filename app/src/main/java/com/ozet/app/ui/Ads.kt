package com.ozet.app.ui

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.ozet.app.R
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reklam kapısı: önce Google UMP ile kullanıcı rızası (AB/UK'de zorunlu) alınır, izin varsa AdMob başlatılır.
 * İzin yoksa / internet yoksa reklam yüklenmez, uygulama normal çalışır.
 */
object AdsGate {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _privacyRequired = MutableStateFlow(false)
    val privacyRequired: StateFlow<Boolean> = _privacyRequired.asStateFlow()
    private var started = false

    fun start(activity: Activity) {
        try {
            val ci = UserMessagingPlatform.getConsentInformation(activity)
            ci.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                ConsentInformation.OnConsentInfoUpdateSuccessListener {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                        activity,
                        ConsentForm.OnConsentFormDismissedListener { _ -> after(activity) }
                    )
                },
                ConsentInformation.OnConsentInfoUpdateFailureListener { _ -> after(activity) }
            )
            after(activity)
        } catch (e: Exception) {
        }
    }

    private fun after(activity: Activity) {
        try {
            val ci = UserMessagingPlatform.getConsentInformation(activity)
            _privacyRequired.value =
                ci.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            if (ci.canRequestAds() && !started) {
                started = true
                MobileAds.initialize(activity.applicationContext) { _ready.value = true }
            }
        } catch (e: Exception) {
        }
    }

    fun showPrivacyOptions(activity: Activity) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity, ConsentForm.OnConsentFormDismissedListener { _ -> after(activity) })
        } catch (e: Exception) {
        }
    }
}

@Composable
fun AdBox(bannerSize: AdSize, unitIdRes: Int, modifier: Modifier = Modifier) {
    val context: Context = LocalContext.current
    val adLabel = LocalStrings.current.adLabel
    val ready by AdsGate.ready.collectAsStateWithLifecycle()
    var failures by remember { mutableStateOf(0) }
    var retry by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val adView = remember {
        AdView(context).apply {
            setAdSize(bannerSize)
            adUnitId = context.getString(unitIdRes)
        }
    }
    DisposableEffect(adView) {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                loaded = true
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                loaded = false
                failures += 1
            }
        }
        onDispose { adView.destroy() }
    }
    LaunchedEffect(ready, retry) {
        if (ready) {
            try {
                adView.loadAd(AdRequest.Builder().build())
            } catch (e: Exception) {
            }
        }
    }
    LaunchedEffect(failures) {
        if (failures in 1..3) {
            delay(60_000L)
            retry += 1
        }
    }
    if (loaded) {
        AndroidView(
            factory = { adView },
            modifier = modifier.semantics { contentDescription = adLabel }
        )
    }
}

/**
 * Ödüllü reklam: özet hazır olunca kullanıcı reklamı izler, izleme tamamlanınca özet açılır.
 * Durum: 0 boşta, 1 yükleniyor, 2 hazır, 3 yüklenemedi / gösterilemedi.
 */
object RewardedGate {
    private val _status = MutableStateFlow(0)
    val status: StateFlow<Int> = _status.asStateFlow()
    private var ad: RewardedAd? = null

    fun load(context: Context) {
        if (!AdsGate.ready.value || _status.value == 1 || _status.value == 2) return
        _status.value = 1
        try {
            RewardedAd.load(
                context.applicationContext,
                context.getString(R.string.admob_rewarded_id),
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(loaded: RewardedAd) {
                        ad = loaded
                        _status.value = 2
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        ad = null
                        _status.value = 3
                    }
                }
            )
        } catch (e: Exception) {
            _status.value = 3
        }
    }

    fun show(activity: Activity, onReward: () -> Unit) {
        val a = ad ?: return
        var rewarded = false
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                _status.value = 0
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                ad = null
                _status.value = 3
            }
        }
        a.show(activity, OnUserEarnedRewardListener {
            if (!rewarded) {
                rewarded = true
                onReward()
            }
        })
    }
}
