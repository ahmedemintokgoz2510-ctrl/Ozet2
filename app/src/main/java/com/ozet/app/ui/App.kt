package com.ozet.app.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdSize
import com.ozet.app.MainViewModel
import com.ozet.app.R
import com.ozet.app.UiState
import com.ozet.app.data.HistoryItem
import com.ozet.app.engine.Err
import com.ozet.app.engine.SecTitle
import com.ozet.app.engine.Section
import com.ozet.app.i18n.APP_VERSION
import com.ozet.app.i18n.AppLang
import com.ozet.app.i18n.StrTr
import com.ozet.app.i18n.Strings
import com.ozet.app.engine.Length
import com.ozet.app.engine.Mode
import com.ozet.app.engine.Result
import com.ozet.app.engine.SectionKind

private val ALL_TYPES = arrayOf(
    "application/pdf",
    "text/plain",
    "text/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/html", "text/markdown", "application/rtf", "text/rtf", "application/json"
)
private val PDF_TYPES = arrayOf("application/pdf")
private val DOCX_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
private val TXT_TYPES = arrayOf("text/plain")
private val EXCEL_TYPES = arrayOf(
    "text/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.binary.macroenabled.12"
)
private val PRESENTATION_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")
private val WEB_TYPES = arrayOf("text/html", "text/markdown", "application/rtf", "text/rtf", "application/json")

val LocalStrings = staticCompositionLocalOf { StrTr }
val LocalLang = staticCompositionLocalOf { AppLang.TR }

/** Sağdan sola dillerde (Arapça) yön okları aynalanır. */
@Composable
private fun Modifier.mirror(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) this.scale(-1f, 1f) else this

private fun secTitle(sec: Section, st: Strings): String? = sec.title ?: when (sec.key) {
    SecTitle.SUMMARY -> st.secSummary
    SecTitle.TURNING -> st.secTurning
    SecTitle.NOTES -> st.secNotes
    SecTitle.GENERAL -> st.secGeneral
    SecTitle.CUSTOM -> null
}

private fun errText(k: Err, st: Strings): String = when (k) {
    Err.NO_TEXT -> st.errNoText
    Err.TOO_SHORT -> st.errTooShort
    Err.ENCRYPTED -> st.errEncrypted
    Err.NOT_FOUND -> st.errNotFound
    Err.OPEN -> st.errOpen
    Err.READ -> st.errRead
}

// ---------------------------------------------------------------- ana iskelet

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val defLen by vm.defaultLength.collectAsStateWithLifecycle()
    val lang by vm.lang.collectAsStateWithLifecycle()
    val view = LocalView.current
    val working = state is UiState.Summarizing || (state as? UiState.Reward)?.ready == false

    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = state !is UiState.Home) { vm.goHome() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }

    CompositionLocalProvider(
        LocalStrings provides lang.strings,
        LocalLang provides lang,
        LocalLayoutDirection provides (if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr)
    ) {
    Scaffold(
        containerColor = Pal.Bg,
        topBar = {
            if (state is UiState.Home) {
                Box(
                    Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { AdBox(AdSize.BANNER, R.string.admob_banner_id) }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
                if (state is UiState.Home || state is UiState.History) {
                    NavBar(
                        homeSelected = state is UiState.Home,
                        onHome = vm::goHome,
                        onHistory = vm::showHistory
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    AdBox(AdSize.BANNER, R.string.admob_banner_id)
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is UiState.Home -> HomeScreen(
                    onPickAll = { picker.launch(ALL_TYPES) },
                    onPickPdf = { picker.launch(PDF_TYPES) },
                    onPickDocx = { picker.launch(DOCX_TYPES) },
                    onPickTxt = { picker.launch(TXT_TYPES) },
                    onPickExcel = { picker.launch(EXCEL_TYPES) },
                    onPickPresentation = { picker.launch(PRESENTATION_TYPES) },
                    onPickWeb = { picker.launch(WEB_TYPES) },
                    onPaste = vm::openPaste,
                    onSettings = vm::showSettings
                )
                is UiState.History -> HistoryScreen(history, vm::openHistory, vm::deleteHistory)
                is UiState.Settings -> SettingsScreen(
                    defLen = defLen,
                    historyCount = history.size,
                    lang = lang,
                    onLang = vm::setLang,
                    onLength = vm::setDefaultLength,
                    onClear = vm::clearHistory,
                    onBack = vm::goHome
                )
                is UiState.Paste -> PasteScreen(onBack = vm::goHome, onSubmit = vm::submitText)
                is UiState.Loading -> LoadingScreen()
                is UiState.Summarizing -> WorkingScreen()
                is UiState.Reward -> RewardScreen(s.ready, vm::unlock, vm::goHome)
                is UiState.Done -> ResultScreen(s, vm::setMode, vm::setLength, vm::goHome)
                is UiState.Error -> ErrorScreen(s.kind, vm::goHome)
            }
        }
    }
    }
}

// ---------------------------------------------------------------- ortak parcalar

private val R16 = RoundedCornerShape(16.dp)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pal.Red,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFF1C9C9),
            disabledContentColor = Color.White
        )
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Pal.Red),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.Red)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TypeBadge(type: String, size: Dp) {
    val normalized = type.uppercase()
    val icon = when (normalized) {
        "PDF" -> R.drawable.file_pdf
        "DOCX", "DOC" -> R.drawable.file_word
        "EXCEL", "XLSX", "XLS", "CSV" -> R.drawable.file_excel
        "PPTX", "PPT" -> R.drawable.file_powerpoint
        else -> R.drawable.file_text
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = type, modifier = Modifier.size(size), tint = Color.Unspecified)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), LocalStrings.current.back, tint = Pal.Ink, modifier = Modifier.size(24.dp).mirror())
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Pal.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NavItem(label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = if (selected) Pal.Red else Pal.Sub
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(iconRes), null, tint = c, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = c, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NavBar(homeSelected: Boolean, onHome: () -> Unit, onHistory: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
        Row(Modifier.fillMaxWidth()) {
            val st = LocalStrings.current
            NavItem(st.navHome, R.drawable.ic_home, homeSelected, onHome, Modifier.weight(1f))
            NavItem(st.navHistory, R.drawable.ic_description, !homeSelected, onHistory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val st = LocalStrings.current
    val lang = LocalLang.current
    val typeLabel = if (item.type == "METIN") st.tileText else item.type
    val meta = buildString {
        append(typeLabel)
        if (item.sizeBytes > 0) append("  •  ").append(lang.formatSize(item.sizeBytes))
        append("  •  ").append(lang.formatDate(item.dateMillis))
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(R16).background(Color.White)
            .border(1.dp, Pal.Line, R16).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Pal.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { TypeBadge(item.type, 36.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, fontSize = 16.sp, color = Pal.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(meta, fontSize = 13.sp, color = Pal.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Pal.Red else Color.White)
            .border(1.dp, if (selected) Pal.Red else Pal.Line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else Pal.Ink,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------- ana sayfa

@Composable
private fun HomeScreen(
    onPickAll: () -> Unit,
    onPickPdf: () -> Unit,
    onPickDocx: () -> Unit,
    onPickTxt: () -> Unit,
    onPickExcel: () -> Unit,
    onPickPresentation: () -> Unit,
    onPickWeb: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit
) {
    val st = LocalStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Red),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_description), null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.appName, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Text(st.tagline, fontSize = 14.sp, color = Pal.Sub)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_settings), st.settings, tint = Pal.Ink, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Pal.RedLight, Pal.RedDark)))
                    .clickable(onClick = onPickAll)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.07f),
                        radius = size.height * 0.9f,
                        center = Offset(size.width * 0.97f, size.height * 1.15f)
                    )
                }
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(62.dp).border(3.dp, Color.White, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_add), null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.pickFile, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            st.pickFileDesc,
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = Color.White, modifier = Modifier.size(24.dp).mirror())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("PDF", "PDF", st.tileSubPick, onPickPdf, Modifier.weight(1f))
                Tile("DOCX", "DOCX", st.tileSubPick, onPickDocx, Modifier.weight(1f))
                Tile("TXT", "TXT", st.tileSubPick, onPickTxt, Modifier.weight(1f))
                Tile("EXCEL", "XLSX / XLS / CSV", st.tileSubPick, onPickExcel, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("PPTX", "PPTX", st.tileSubPick, onPickPresentation, Modifier.weight(1f))
                Tile("WEB", "HTML / RTF / MD", st.tileSubPick, onPickWeb, Modifier.weight(1f))
                Tile("METIN", st.tileText, st.tileSubPaste, onPaste, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Tile(type: String, label: String, sub: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.clip(R16).background(Color.White).border(1.dp, Pal.Line, R16)
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TypeBadge(type, 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Pal.Ink, maxLines = 1)
        Text(sub, fontSize = 11.sp, color = Pal.Sub, maxLines = 1)
    }
}

// ---------------------------------------------------------------- gecmis

@Composable
private fun HistoryScreen(history: List<HistoryItem>, onOpen: (HistoryItem) -> Unit, onDelete: (String) -> Unit) {
    val st = LocalStrings.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
    ) {
        item {
            Text(st.history, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
            Spacer(Modifier.height(16.dp))
        }
        if (history.isEmpty()) {
            item { Text(st.noHistory, color = Pal.Sub, fontSize = 14.sp) }
        } else {
            items(history, key = { it.id }) { h ->
                HistoryRow(h, onClick = { onOpen(h) }) {
                    Text(
                        st.deleteBtn,
                        color = Pal.Red,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { onDelete(h.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- ayarlar

@Composable
private fun SettingsScreen(
    defLen: Length,
    historyCount: Int,
    lang: AppLang,
    onLang: (AppLang) -> Unit,
    onLength: (Length) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val st = LocalStrings.current
    val privacy by AdsGate.privacyRequired.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(st.settings, onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text(st.language, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text(st.languageDesc, fontSize = 13.sp, color = Pal.Sub)
                Spacer(Modifier.height(12.dp))
                AppLang.values().toList().chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { l ->
                            Choice(l.flag + " " + l.nativeName, l == lang, { onLang(l) }, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (lang != AppLang.EN) Text(st.langPackHint, fontSize = 12.sp, color = Pal.Sub)
            }
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text(st.defaultLength, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(st.lenShort, defLen == Length.SHORT, { onLength(Length.SHORT) }, Modifier.weight(1f))
                    Choice(st.lenMedium, defLen == Length.MEDIUM, { onLength(Length.MEDIUM) }, Modifier.weight(1f))
                    Choice(st.lenLong, defLen == Length.LONG, { onLength(Length.LONG) }, Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text(st.history, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text(st.savedCount.format(lang.num(historyCount)), fontSize = 13.sp, color = Pal.Sub)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(st.clearHistory, {
                    onClear()
                    Toast.makeText(ctx, st.historyCleared, Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
            }
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text(st.about, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    st.aboutText.format(APP_VERSION),
                    fontSize = 13.sp,
                    color = Pal.Sub
                )
            }
            if (privacy) {
                Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                    Text(st.privacyOptions, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(st.privacyOptionsDesc, fontSize = 13.sp, color = Pal.Sub)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(st.privacyOptions, {
                        (ctx as? Activity)?.let { AdsGate.showPrivacyOptions(it) }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- yapistir, bekleme, hata

@Composable
private fun PasteScreen(onBack: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val enough = text.trim().length >= 150
    val st = LocalStrings.current
    val lang = LocalLang.current
    Column(Modifier.fillMaxSize()) {
        TopBar(st.pasteTitle, onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(st.pastePlaceholder) }
            )
            Spacer(Modifier.height(8.dp))
            if (text.isNotEmpty() && !enough) {
                Text(st.minChars.format(lang.num(150)), fontSize = 13.sp, color = Pal.Sub)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton(st.summarizeBtn, { onSubmit(text) }, Modifier.fillMaxWidth(), enabled = enough)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Pal.Red)
        Spacer(Modifier.height(16.dp))
        Text(LocalStrings.current.opening, color = Pal.Sub)
    }
}

@Composable
private fun WorkingScreen() {
    val st = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(st.stillSummarizing, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(8.dp))
        Text(st.workingText, textAlign = TextAlign.Center, color = Pal.Sub)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Pal.Red,
            trackColor = Pal.RedSoft
        )
        Spacer(Modifier.height(24.dp))
        AdBox(AdSize.MEDIUM_RECTANGLE, R.string.admob_working_id)
    }
}

@Composable
private fun RewardScreen(summaryReady: Boolean, onUnlock: () -> Unit, onBack: () -> Unit) {
    val st = LocalStrings.current
    val ctx = LocalContext.current
    val ready by AdsGate.ready.collectAsStateWithLifecycle()
    val status by RewardedGate.status.collectAsStateWithLifecycle()
    LaunchedEffect(ready) { RewardedGate.load(ctx) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (summaryReady) st.rewardTitle else st.summarizing, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (status == 3) st.rewardFailed else if (summaryReady) st.rewardDesc else st.rewardDescWorking,
            textAlign = TextAlign.Center,
            color = Pal.Sub
        )
        Spacer(Modifier.height(24.dp))
        when (status) {
            2 -> PrimaryButton(st.rewardWatch, {
                (ctx as? Activity)?.let { act -> RewardedGate.show(act, onUnlock) }
            }, Modifier.fillMaxWidth())
            3 -> PrimaryButton(st.rewardRetry, { RewardedGate.load(ctx) }, Modifier.fillMaxWidth())
            else -> {
                CircularProgressIndicator(color = Pal.Red)
                Spacer(Modifier.height(12.dp))
                Text(st.rewardLoading, color = Pal.Sub)
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton(st.goBack, onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorScreen(kind: Err, onBack: () -> Unit) {
    val st = LocalStrings.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(st.errorTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(8.dp))
        Text(errText(kind, st), textAlign = TextAlign.Center, color = Pal.Sub)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(st.goBack, onBack, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- sonuc

private fun plainText(r: Result, title: String, st: Strings, lang: AppLang): String = buildString {
    appendLine(title)
    appendLine()
    for (sec in r.sections) {
        if (sec.items.isEmpty()) continue
        secTitle(sec, st)?.let { appendLine(it.uppercase(lang.locale)) }
        sec.items.forEachIndexed { i, t ->
            when (sec.kind) {
                SectionKind.BULLETS -> appendLine("- $t")
                SectionKind.NUMBERED -> appendLine("${i + 1}. $t")
                SectionKind.PARAGRAPHS -> {
                    appendLine(t)
                    appendLine()
                }
            }
        }
        appendLine()
    }
    if (r.keywords.isNotEmpty()) appendLine(st.keywords + ": " + r.keywords.joinToString(", "))
}

@Composable
private fun ResultScreen(
    s: UiState.Done,
    onMode: (Mode) -> Unit,
    onLength: (Length) -> Unit,
    onNew: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val st = LocalStrings.current
    val lang = LocalLang.current
    val out = remember(s.result, s.fileName, lang) { plainText(s.result, s.fileName, st, lang) }
    val r = s.result

    Column(Modifier.fillMaxSize()) {
        TopBar(s.fileName, onNew)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val kind = when {
                    s.mode != Mode.AUTO -> ""
                    r.mode == Mode.STORY -> st.detectedStory + " "
                    else -> st.detectedExam + " "
                }
                Text(kind + st.wordsScanned.format(lang.num(r.wordCount)), fontSize = 13.sp, color = Pal.Sub)
                if (r.translated) Text(st.translatedNote, fontSize = 12.sp, color = Pal.Sub)
                if (r.translateFailed) Text(st.translateFail, fontSize = 12.sp, color = Pal.Sub)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(st.modeAuto, s.mode == Mode.AUTO, { onMode(Mode.AUTO) }, Modifier.weight(1f))
                    Choice(st.modeExam, s.mode == Mode.EXAM, { onMode(Mode.EXAM) }, Modifier.weight(1f))
                    Choice(st.modeStory, s.mode == Mode.STORY, { onMode(Mode.STORY) }, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(st.lenShort, s.length == Length.SHORT, { onLength(Length.SHORT) }, Modifier.weight(1f))
                    Choice(st.lenMedium, s.length == Length.MEDIUM, { onLength(Length.MEDIUM) }, Modifier.weight(1f))
                    Choice(st.lenLong, s.length == Length.LONG, { onLength(Length.LONG) }, Modifier.weight(1f))
                }
            }
            items(r.sections.filter { it.items.isNotEmpty() }) { sec ->
                Column(
                    Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    secTitle(sec, st)?.let {
                        Text(it, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Red)
                    }
                    sec.items.forEachIndexed { i, t ->
                        when (sec.kind) {
                            SectionKind.PARAGRAPHS -> Text(t, fontSize = 15.sp, color = Pal.Ink)
                            else -> Row {
                                Text(
                                    if (sec.kind == SectionKind.NUMBERED) "${i + 1}." else "•",
                                    modifier = Modifier.width(26.dp),
                                    color = Pal.Red,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(t, fontSize = 15.sp, color = Pal.Ink)
                            }
                        }
                    }
                }
            }
            if (r.keywords.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                        Text(st.keywords, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Red)
                        Spacer(Modifier.height(6.dp))
                        Text(r.keywords.joinToString(", "), fontSize = 15.sp, color = Pal.Ink)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(st.copyBtn, {
                        clipboard.setText(AnnotatedString(out))
                        Toast.makeText(ctx, st.copied, Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f))
                    SecondaryButton(st.shareBtn, {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, out)
                        }
                        ctx.startActivity(Intent.createChooser(send, st.shareBtn))
                    }, Modifier.weight(1f))
                    PrimaryButton(st.newBtn, onNew, Modifier.weight(1f))
                }
            }
        }
    }
}
