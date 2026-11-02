package com.ozet.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Err { NO_TEXT, TOO_SHORT, ENCRYPTED, NOT_FOUND, OPEN, READ }

class AppError(val kind: Err) : Exception()

object Extractor {
    fun displayName(context: Context, uri: Uri, fallback: String = "File"): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: uri.lastPathSegment ?: fallback
    }

    suspend fun extract(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(context, uri).lowercase()
        val mime = context.contentResolver.getType(uri) ?: ""
        val text = when {
            mime == "application/pdf" || name.endsWith(".pdf") -> readPdf(context, uri)
            name.endsWith(".docx") || name.endsWith(".docm") || mime.contains("wordprocessingml") -> readDocx(context, uri)
            name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls") || name.endsWith(".csv") || name.endsWith(".tsv") ||
                mime.contains("spreadsheet") || mime.contains("excel") || mime == "text/csv" ->
                SpreadsheetExtractor.extract(context, uri)
            name.endsWith(".pptx") || name.endsWith(".pptm") || mime.contains("presentationml") -> readPptx(context, uri)
            name.endsWith(".rtf") || mime == "text/rtf" || mime == "application/rtf" -> RtfReader.read(readTxt(context, uri))
            name.endsWith(".html") || name.endsWith(".htm") || mime.contains("html") -> HtmlReader.read(readTxt(context, uri))
            name.endsWith(".doc") || mime == "application/msword" -> LegacyOffice.readDoc(readBytes(context, uri))
            name.endsWith(".ppt") || mime == "application/vnd.ms-powerpoint" -> LegacyOffice.readPpt(readBytes(context, uri))
            else -> readTxt(context, uri)
        }
        text
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw AppError(Err.OPEN)

    private fun readTxt(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        return TextDecode.decode(bytes)
    }

    // ---------------------------------------------------------------- PDF

    /**
     * Metni normal okur ama aynı satırdaki sözcükler arasında büyük yatay boşluk (tablo sütunu) varsa araya sekme koyar;
     * böylece tablo satırları sonradan `| a | b | c |` biçimine çevrilebilir ([TabularText]).
     */
    private class GapStripper : PDFTextStripper() {
        private var prevEnd = Float.NaN

        override fun writeLineSeparator() {
            prevEnd = Float.NaN
            super.writeLineSeparator()
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isNotEmpty()) {
                val first = textPositions.first()
                val last = textPositions.last()
                if (!prevEnd.isNaN() && first.xDirAdj - prevEnd > first.fontSizeInPt * 1.2f) output.write("\t")
                prevEnd = last.xDirAdj + last.widthDirAdj
            }
            super.writeString(text, textPositions)
        }
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        return input.use { ins ->
            PDDocument.load(ins).use { doc ->
                if (doc.isEncrypted) throw AppError(Err.ENCRYPTED)
                val pages = ArrayList<String>()
                for (p in 1..doc.numberOfPages) {
                    val stripper = GapStripper()
                    stripper.startPage = p
                    stripper.endPage = p
                    pages.add(stripper.getText(doc))
                }
                // Her sayfada tekrar eden üst/alt bilgi ve sayfa numaraları atılır; sütunlu satırlar tabloya çevrilir.
                TabularText.convert(PageCleaner.joinWrappedBullets(PageCleaner.strip(pages)))
            }
        }
    }

    // ---------------------------------------------------------------- Office (DOCX / PPTX)

    private fun readDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        val parts = input.use { OoxmlZip.readParts(it) { name -> name in DocxReader.PARTS } }
        return DocxReader.read(parts)
    }

    private fun readPptx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        val parts = input.use { OoxmlZip.readParts(it) { name -> PptxReader.accepts(name) } }
        return PptxReader.read(parts)
    }
}
