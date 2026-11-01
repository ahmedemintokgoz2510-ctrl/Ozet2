package com.ozet.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozet.app.data.HistoryItem
import com.ozet.app.data.HistoryStore
import com.ozet.app.data.sizeOf
import com.ozet.app.data.typeOf
import com.ozet.app.engine.AppError
import com.ozet.app.engine.Err
import com.ozet.app.engine.Extractor
import com.ozet.app.engine.DeepTextProcessor
import com.ozet.app.engine.Length
import com.ozet.app.engine.Mode
import com.ozet.app.engine.Result
import com.ozet.app.engine.Summarizer
import com.ozet.app.engine.Translate
import com.ozet.app.i18n.AppLang
import com.ozet.app.ui.RewardedGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Home : UiState
    data object History : UiState
    data object Settings : UiState
    data object Paste : UiState
    data object Loading : UiState
    data class Working(val secondsLeft: Int, val ready: Boolean) : UiState
    data object Reward : UiState
    data class Done(val result: Result, val mode: Mode, val length: Length, val fileName: String) : UiState
    data class Error(val kind: Err) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val WAIT = 10
    }

    private val store = HistoryStore(app)
    private val settings = app.getSharedPreferences("ozet_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UiState>(UiState.Home)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _defaultLength = MutableStateFlow(readLength())
    val defaultLength: StateFlow<Length> = _defaultLength.asStateFlow()

    // Seçilen dil kalıcıdır; uygulama yeniden açılınca son seçilen dil kullanılır.
    private val _lang = MutableStateFlow(AppLang.fromCode(settings.getString("lang", null)))
    val lang: StateFlow<AppLang> = _lang.asStateFlow()

    private var text = ""
    private var name = ""
    private var mode = Mode.AUTO
    private var length = _defaultLength.value
    private var job: Job? = null
    private var pending: UiState.Done? = null
    private var pendingType = ""
    private var pendingSize = 0L
    private var rewardConsumed = false

    private fun readLength(): Length = try {
        Length.valueOf(settings.getString("length", "MEDIUM") ?: "MEDIUM")
    } catch (e: Exception) {
        Length.MEDIUM
    }

    fun setLang(l: AppLang) {
        settings.edit().putString("lang", l.code).apply()
        _lang.value = l
        // Dil paketlerini ek indirme yükünden bağımsız tut; arayüz kaynakları uygulama APK'sında gelir.
    }

    fun goHome() {
        job?.cancel()
        pending = null
        pendingType = ""
        pendingSize = 0L
        rewardConsumed = false
        text = ""
        _state.value = UiState.Home
    }

    fun showHistory() {
        job?.cancel()
        _state.value = UiState.History
    }

    fun showSettings() {
        _state.value = UiState.Settings
    }

    fun openPaste() {
        _state.value = UiState.Paste
    }

    fun setDefaultLength(l: Length) {
        settings.edit().putString("length", l.name).apply()
        _defaultLength.value = l
        length = l
    }

    fun deleteHistory(id: String) {
        _history.value = store.remove(id)
    }

    fun clearHistory() {
        _history.value = store.clear()
    }

    /** Özeti çıkarır, gerekirse seçili dile çevirir. */
    private suspend fun build(t: String, m: Mode, l: Length): Result {
        val code = _lang.value.code
        val res = withContext(Dispatchers.Default) { Summarizer.summarize(t, m, l) }
        return Translate.translate(res, code)
    }

    fun openUri(uri: Uri) {
        val ctx = getApplication<Application>()
        val n = Extractor.displayName(ctx, uri, _lang.value.strings.fileFallback)
        start(n, typeOf(n), sizeOf(ctx, uri)) { Extractor.extract(ctx, uri) }
    }

    fun submitText(pasted: String) {
        val first = pasted.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val label = if (first.length > 32) first.substring(0, 32) + "…" else first
        start(label.ifEmpty { _lang.value.strings.pastedText }, "METIN", pasted.toByteArray().size.toLong()) { pasted }
    }

    fun openHistory(item: HistoryItem) {
        job?.cancel()
        name = item.name
        mode = Mode.AUTO
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            val r = runCatching {
                val t = withContext(Dispatchers.IO) { store.readText(item.id) } ?: throw AppError(Err.NOT_FOUND)
                Pair(t, build(t, mode, length))
            }
            r.onSuccess { p ->
                text = p.first
                _state.value = UiState.Done(p.second, mode, length, name)
            }.onFailure { e ->
                _state.value = UiState.Error((e as? AppError)?.kind ?: Err.OPEN)
            }
        }
    }

    fun setMode(m: Mode) = resummarize(m, length)

    fun setLength(l: Length) = resummarize(mode, l)

    private fun start(label: String, type: String, size: Long, load: suspend () -> String) {
        job?.cancel()
        name = label
        mode = Mode.AUTO
        _state.value = UiState.Working(0, false)
        pending = null
        pendingType = ""
        pendingSize = 0L
        rewardConsumed = false
        job = viewModelScope.launch {
            RewardedGate.load(getApplication())
            val outcome = runCatching {
            val loaded = withContext(Dispatchers.IO) { load() }
            if (loaded.isBlank()) throw AppError(Err.NO_TEXT)
            val t = withContext(Dispatchers.Default) { DeepTextProcessor.prepare(loaded) }
            val res = build(t, mode, length)
            if (res.sentenceCount == 0) throw AppError(Err.TOO_SHORT)
            Pair(t, res)
            }
            outcome.onSuccess { pair ->
                text = pair.first
                pendingType = type
                pendingSize = size
                pending = UiState.Done(pair.second, mode, length, name)
                _state.value = UiState.Reward
            }.onFailure { e ->
                _state.value = UiState.Error((e as? AppError)?.kind ?: Err.READ)
            }
        }
    }

    /** Ödüllü reklam izlendi: özeti göster. */
    fun unlock() {
        val ready = pending ?: return
        if (rewardConsumed) return
        rewardConsumed = true
        viewModelScope.launch {
            if (text.isNotEmpty() && name.isNotEmpty() && pendingType.isNotEmpty()) {
                val saved = withContext(Dispatchers.IO) { store.add(name, pendingType, pendingSize, text) }
                _history.value = saved
            }
            _state.value = ready
            pending = null
            pendingType = ""
            pendingSize = 0L
        }
    }

    private fun resummarize(m: Mode, l: Length) {
        if (_state.value !is UiState.Done || text.isEmpty()) return
        mode = m
        length = l
        job?.cancel()
        job = viewModelScope.launch {
            val res = build(text, m, l)
            _state.value = UiState.Done(res, m, l, name)
        }
    }
}
