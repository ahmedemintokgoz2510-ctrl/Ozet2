package com.ozet.app.i18n

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

const val APP_VERSION = "1.0"

/** Desteklenen altı dil. Yeni dil eklemek için buraya bir satır + bir Str*.kt dosyası yeterli. */
enum class AppLang(
    val code: String,
    val flag: String,
    val nativeName: String,
    val rtl: Boolean,
    val strings: Strings
) {
    TR("tr", "🇹🇷", "Türkçe", false, StrTr),
    EN("en", "🇬🇧", "English", false, StrEn),
    DE("de", "🇩🇪", "Deutsch", false, StrDe),
    ES("es", "🇪🇸", "Español", false, StrEs),
    AR("ar", "🇸🇦", "العربية", true, StrAr),
    FR("fr", "🇫🇷", "Français", false, StrFr);

    val locale: Locale get() = Locale(code)

    fun num(n: Number): String = NumberFormat.getInstance(locale).format(n)

    fun formatSize(b: Long): String = when {
        b < 1024 -> num(b) + " " + strings.unitB
        b < 1024 * 1024 -> num(b / 1024) + " " + strings.unitKB
        else -> {
            val nf = NumberFormat.getInstance(locale)
            nf.maximumFractionDigits = 1
            nf.minimumFractionDigits = 1
            nf.format(b / 1048576.0) + " " + strings.unitMB
        }
    }

    fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))

    companion object {
        /** Kayıtlı dil yoksa telefonun dili (desteklenen altı dilden biriyse), değilse İngilizce. */
        fun fromCode(code: String?): AppLang {
            val c = code ?: Locale.getDefault().language
            return values().firstOrNull { it.code == c } ?: if (code == null) EN else TR
        }
    }
}
