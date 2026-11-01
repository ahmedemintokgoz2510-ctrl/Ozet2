package com.ozet.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

enum class Err { NO_TEXT, TOO_SHORT, ENCRYPTED, NOT_FOUND, OPEN, READ }

class AppError(val kind: Err) : Exception()

object Extractor {
    private const val MAX_CHARS = 4_000_000

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
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(context, uri)
            name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".csv") ||
                mime.contains("spreadsheet") || mime.contains("excel") || mime == "text/csv" ->
                SpreadsheetExtractor.extract(context, uri)
            name.endsWith(".pptx") || mime.contains("presentationml") -> readPptx(context, uri)
            name.endsWith(".rtf") -> readRtf(context, uri)
            name.endsWith(".html") || name.endsWith(".htm") || mime.contains("html") -> readHtml(context, uri)
            name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".json") -> readTxt(context, uri)
            else -> readTxt(context, uri)
        }
        if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
    }

    private fun readTxt(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        var s = String(bytes, Charsets.UTF_8)
        if (s.count { it == '\uFFFD' } > 3) s = String(bytes, Charset.forName("windows-1254"))
        return s.removePrefix("\uFEFF")
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        return input.use { ins ->
            PDDocument.load(ins).use { doc ->
                if (doc.isEncrypted) throw AppError(Err.ENCRYPTED)
                PDFTextStripper().getText(doc)
            }
        }
    }

    private fun readPptx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        val out = StringBuilder()
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val slideMatch = Regex("^ppt/slides/slide\\d+\\.xml$").matches(entry.name)
                    if (slideMatch) {
                        val slide = parseXmlText(zis)
                        if (slide.isNotBlank()) out.append(slide.trim()).append("\n\n")
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return out.toString()
    }

    private fun parseXmlText(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name.substringAfter(':').lowercase() == "t") inText = true
                XmlPullParser.TEXT -> if (inText) out.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':').lowercase() == "t") {
                    inText = false
                    out.append(' ')
                }
            }
            event = parser.next()
        }
        return out.toString().replace(Regex("\\s+"), " ")
    }

    private fun readRtf(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return raw
            .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace(Regex("[{}\\\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun readHtml(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return raw
            .replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</h[1-6]>|</li>|</div>"), "\n")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .replace(Regex("&nbsp;|&#160;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun readDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        var result = ""
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        result = parseDocx(zis)
                        break
                    }
                    e = zis.nextEntry
                }
            }
        }
        return result
    }

    private fun parseDocx(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        val para = StringBuilder()
        var inT = false
        var inPPr = false
        var isHead = false
        var isList = false
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> { para.setLength(0); isHead = false; isList = false }
                    "pPr" -> inPPr = true
                    "pStyle" -> {
                        val v = (parser.getAttributeValue(null, "w:val") ?: parser.getAttributeValue(null, "val") ?: "")
                            .lowercase()
                        if (v.contains("heading") || v.contains("title") || v.contains("başlık") || v.contains("baslik")) {
                            isHead = true
                        }
                    }
                    "numPr" -> isList = true
                    "t" -> inT = true
                    "tab", "br" -> if (!inPPr) para.append(' ')
                }
                XmlPullParser.TEXT -> if (inT) para.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inT = false
                    "pPr" -> inPPr = false
                    "p" -> {
                        val t = para.toString().trim()
                        if (t.isNotEmpty()) {
                            out.append(if (isHead) "# " else if (isList) "• " else "")
                            out.append(t).append("\n\n")
                        }
                        para.setLength(0)
                    }
                }
            }
            ev = parser.next()
        }
        return out.toString()
    }
}
