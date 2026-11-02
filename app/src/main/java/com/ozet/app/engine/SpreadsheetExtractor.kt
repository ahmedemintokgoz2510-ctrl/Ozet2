package com.ozet.app.engine

import android.content.Context
import android.net.Uri
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.CellValue
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Excel (.xlsx/.xlsm/.xls) ve CSV/TSV okuyucu. Burada yalnızca POI -> [SGrid] dönüşümü yapılır;
 * tablo/istatistik/özet metni üretimi POI'den bağımsız [SheetRenderer] içindedir (JVM birim testleriyle doğrulanır).
 */
object SpreadsheetExtractor {
    // Sayfa/satır/sütun/hücre sınırı yoktur; yalnızca bellek yettiği kadar okunur.

    fun extract(context: Context, uri: Uri): String {
        val name = Extractor.displayName(context, uri).lowercase(Locale.ROOT)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw AppError(Err.OPEN)
        return extractBytes(bytes, name)
    }

    internal fun extractBytes(bytes: ByteArray, lowerName: String): String {
        val grids: List<SGrid> = if (lowerName.endsWith(".csv") || lowerName.endsWith(".tsv")) {
            listOf(CsvParser.toGrid(TextDecode.decode(bytes)))
        } else {
            readWorkbook(bytes)
        }
        val text = SheetRenderer.render(grids)
        if (text.isBlank()) throw AppError(Err.NO_TEXT)
        return text
    }

    private fun readWorkbook(bytes: ByteArray): List<SGrid> {
        var workbook: Workbook? = null
        try {
            workbook = WorkbookFactory.create(ByteArrayInputStream(bytes))
            val evaluator: FormulaEvaluator? = try { workbook.creationHelper.createFormulaEvaluator() } catch (_: Throwable) { null }
            val budget = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE)
            val grids = ArrayList<SGrid>()
            val count = workbook.numberOfSheets
            for (i in 0 until count) grids.add(buildGrid(workbook, i, evaluator, budget))
            return grids
        } catch (e: EncryptedDocumentException) {
            throw AppError(Err.ENCRYPTED)
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError(Err.OPEN)
        } finally {
            try { workbook?.close() } catch (_: Exception) {}
        }
    }

    private fun buildGrid(wb: Workbook, index: Int, ev: FormulaEvaluator?, budget: IntArray): SGrid {
        val sheet = wb.getSheetAt(index)
        val first = sheet.firstRowNum.coerceAtLeast(0)
        val lastAll = sheet.lastRowNum
        val last = lastAll
        val rows = ArrayList<List<SCell?>>()
        for (r in first..last) {
            val row = sheet.getRow(r)
            if (row == null) { rows.add(emptyList()); continue }
            val lc = row.lastCellNum.toInt()
            val cells = ArrayList<SCell?>()
            for (c in 0 until lc) {
                val cell = row.getCell(c)
                if (cell == null || budget[0] <= 0) { cells.add(null); continue }
                budget[0]--
                cells.add(convert(cell, if (budget[1] > 0) ev else null, budget))
            }
            rows.add(cells)
        }
        return SGrid(sheet.sheetName ?: "Sheet${index + 1}", wb.isSheetHidden(index), rows, truncated = lastAll > last)
    }

    private fun convert(cell: Cell, ev: FormulaEvaluator?, budget: IntArray): SCell? {
        var formula: String? = null
        var type: CellType = cell.cellType
        var number = 0.0
        var str = ""
        var bool = false
        var err = false

        if (type == CellType.FORMULA) {
            formula = try { cell.cellFormula } catch (_: Exception) { null }
            var cv: CellValue? = null
            if (ev != null) {
                budget[1]--
                cv = try { ev.evaluate(cell) } catch (_: Throwable) { null }
            }
            if (cv != null) {
                type = cv.cellType
                when (type) {
                    CellType.NUMERIC -> number = cv.numberValue
                    CellType.STRING -> str = cv.stringValue ?: ""
                    CellType.BOOLEAN -> bool = cv.booleanValue
                    CellType.ERROR -> err = true
                    else -> {}
                }
            } else {
                type = cell.cachedFormulaResultType
                try {
                    when (type) {
                        CellType.NUMERIC -> number = cell.numericCellValue
                        CellType.STRING -> str = cell.stringCellValue ?: ""
                        CellType.BOOLEAN -> bool = cell.booleanCellValue
                        CellType.ERROR -> err = true
                        else -> {}
                    }
                } catch (_: Exception) { err = true }
            }
        } else {
            when (type) {
                CellType.NUMERIC -> number = cell.numericCellValue
                CellType.STRING -> str = cell.stringCellValue ?: ""
                CellType.BOOLEAN -> bool = cell.booleanCellValue
                CellType.ERROR -> err = true
                else -> {}
            }
        }

        return when {
            err -> SCell(SCell.Kind.ERR, text = "#ERR", formula = formula)
            type == CellType.STRING -> {
                val t = str.replace(Regex("\\s+"), " ").trim()
                if (t.isEmpty()) null else SCell(SCell.Kind.TEXT, text = t, formula = formula)
            }
            type == CellType.BOOLEAN -> SCell(SCell.Kind.BOOL, text = bool.toString(), formula = formula)
            type == CellType.NUMERIC -> numeric(cell, number, formula)
            else -> null
        }
    }

    private fun numeric(cell: Cell, v: Double, formula: String?): SCell {
        val fmt = try { cell.cellStyle?.dataFormatString } catch (_: Exception) { null } ?: "General"
        val isDate = try { DateUtil.isADateFormat(cell.cellStyle.dataFormat.toInt(), fmt) } catch (_: Exception) { false }
        if (isDate) return SCell(SCell.Kind.DATE, text = dateText(v), formula = formula)
        val sec = fmt.substringBefore(';')
        val pct = '%' in sec
        val dec = decimalsOf(sec, v)
        val grouping = ',' in sec.substringBefore('.')
        val suffix = when {
            '₺' in sec || sec.contains("TL") -> "₺"
            '€' in sec -> "€"
            '$' in sec -> "$"
            '£' in sec -> "£"
            else -> ""
        }
        return if (pct) SCell(SCell.Kind.PCT, num = v, decimals = dec, formula = formula)
        else SCell(SCell.Kind.NUM, num = v, decimals = dec, grouping = grouping, suffix = suffix, formula = formula)
    }

    private fun dateText(v: Double): String = try {
        val d = DateUtil.getJavaDate(v)
        val frac = v - Math.floor(v)
        when {
            v < 1.0 -> SimpleDateFormat("HH:mm", Locale.ROOT).format(d)
            frac > 1e-6 -> SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(d)
            else -> SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(d)
        }
    } catch (_: Exception) { v.toString() }

    private fun decimalsOf(section: String, v: Double): Int {
        val clean = section.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*]"), "")
        if (clean.isBlank() || clean.equals("General", ignoreCase = true)) {
            return BigDecimal.valueOf(v).stripTrailingZeros().scale().coerceIn(0, 6)
        }
        val dot = clean.indexOf('.')
        if (dot < 0) return 0
        var n = 0
        var k = dot + 1
        while (k < clean.length && clean[k] in "0#?") { n++; k++ }
        return n
    }
}
