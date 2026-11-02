package com.ozet.app.engine

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.ByteArrayInputStream

/** Eski ikili Office biçimleri: .doc (Word 97-2003) ve .ppt (PowerPoint 97-2003). poi-scratchpad bağımlılığı gerekir. */
object LegacyOffice {
    private val FIELD_MARKS = Regex("[\\u0001\\u0013\\u0014\\u0015\\u0008]")

    fun readDoc(bytes: ByteArray): String {
        var doc: HWPFDocument? = null
        try {
            doc = HWPFDocument(ByteArrayInputStream(bytes))
            val raw = WordExtractor(doc).text ?: ""
            // \u0007 = tablo hücre/satır sonu işareti, \r = paragraf sonu
            val cleaned = FIELD_MARKS.replace(raw, "")
                .replace("\u0007\u0007", "\n").replace('\u0007', ' ')
                .replace("\r\n", "\n").replace('\r', '\n')
            return cleaned.lines().joinToString("\n") { it.trim() }.replace(Regex("\\n{3,}"), "\n\n").trim()
        } catch (e: EncryptedDocumentException) {
            throw AppError(Err.ENCRYPTED)
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError(Err.OPEN)
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }

    fun readPpt(bytes: ByteArray): String {
        var show: HSLFSlideShow? = null
        try {
            show = HSLFSlideShow(ByteArrayInputStream(bytes))
            val out = StringBuilder()
            for (slide in show.slides) {
                val title = slide.title?.trim().orEmpty()
                if (title.isNotEmpty()) out.append("# ").append(title).append("\n\n")
                for (shape in slide.shapes) {
                    if (shape is HSLFTextShape) {
                        val t = (shape.text ?: "").replace('\r', '\n').replace('\u000B', '\n').trim()
                        if (t.isNotEmpty() && t != title) out.append(t).append("\n\n")
                    }
                }
                val notes = slide.notes
                if (notes != null) {
                    for (shape in notes.shapes) {
                        if (shape is HSLFTextShape) {
                            val t = (shape.text ?: "").replace('\r', '\n').trim()
                            if (t.length > 3 && !t.all { it.isDigit() }) out.append(t).append("\n\n")
                        }
                    }
                }
            }
            return out.toString().trim()
        } catch (e: EncryptedDocumentException) {
            throw AppError(Err.ENCRYPTED)
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError(Err.OPEN)
        } finally {
            try { show?.close() } catch (_: Exception) {}
        }
    }
}
