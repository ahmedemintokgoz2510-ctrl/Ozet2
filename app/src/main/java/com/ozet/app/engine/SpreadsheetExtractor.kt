package com.ozet.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object SpreadsheetExtractor {
    private const val MAX_SHEETS = 100
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 250
    private const val MAX_CELLS = 250_000

    fun extract(context: Context, uri: Uri): String {
        val name = Extractor.displayName(context, uri).lowercase(Locale.ROOT)
        return context.contentResolver.openInputStream(uri)?.use { input ->
            when {
                name.endsWith(".csv") -> extractCsv(input.bufferedReader(Charsets.UTF_8))
                else -> extractWorkbook(input)
            }
        } ?: throw AppError(Err.OPEN)
    }

    private fun extractWorkbook(input: java.io.InputStream): String {
        val out = StringBuilder()
        var workbook: org.apache.poi.ss.usermodel.Workbook? = null
        try {
            workbook = WorkbookFactory.create(input)
            out.append("ÇALIŞMA KITABI\n")
            out.append("Çalışma sayfası sayısı: ${workbook.numberOfSheets.coerceAtMost(MAX_SHEETS)}\n\n")
            var cellsRead = 0
            val sheetCount = workbook.numberOfSheets.coerceAtMost(MAX_SHEETS)
            for (sheetIndex in 0 until sheetCount) {
                val sheet = workbook.getSheetAt(sheetIndex)
                out.append("Sayfa: ").append(sheet.sheetName).append('\n')
                if (workbook.isSheetHidden(sheetIndex)) out.append("Durum: gizli\n")
                val first = sheet.firstRowNum.coerceAtLeast(0)
                val last = sheet.lastRowNum.coerceAtMost(MAX_ROWS - 1).coerceAtLeast(first)
                var numericCount = 0
                var formulaCount = 0
                var formulaWithNoCachedValue = 0
                var negativeCount = 0
                var nonEmptyCount = 0
                val numericColumns = linkedMapOf<Int, MutableList<Double>>()

                for (rowIndex in first..last) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    val firstColumn = row.firstCellNum.toInt().coerceAtLeast(0)
                    val lastColumn = row.lastCellNum.toInt().coerceAtMost(MAX_COLUMNS) - 1
                    val columns = if (firstColumn <= lastColumn) firstColumn..lastColumn else emptyList<Int>()
                    for (column in columns) {
                        if (cellsRead >= MAX_CELLS) break
                        val cell = row.getCell(column) ?: continue
                        if (cell.toString().isBlank()) continue
                        cellsRead++
                        nonEmptyCount++
                        val value = cellValue(cell)
                        if (value.isNotEmpty()) out.append("${cellAddress(cell)}: $value\n")
                        if (cell.cellType == CellType.FORMULA) {
                            formulaCount++
                            if (cell.cachedFormulaResultType == CellType.BLANK) formulaWithNoCachedValue++
                        }
                        val number = numericValue(cell)
                        if (number != null) {
                            numericCount++
                            numericColumns.getOrPut(column) { mutableListOf() }.add(number)
                            if (number < 0) negativeCount++
                        }
                    }
                }
                out.append("Doluluk: $nonEmptyCount dolu hücre (işlenen satır sınırı: ${last - first + 1})\n")
                if (formulaCount > 0) {
                    out.append("Formül hücreleri: $formulaCount")
                    if (formulaWithNoCachedValue > 0) out.append("; önbellek sonucu olmayan/belirsiz: $formulaWithNoCachedValue")
                    out.append('\n')
                }
                appendNumericSummary(out, numericColumns, numericCount, negativeCount)
                out.append('\n')
            }
            if (cellsRead >= MAX_CELLS) out.append("UYARI: Dosyanın analizi güvenli sınırlar nedeniyle $MAX_CELLS hücreyle sınırlandırıldı.\n")
        } catch (e: Exception) {
            throw AppError(Err.OPEN)
        } finally {
            workbook?.close()
        }
        if (out.length == 0) throw AppError(Err.NO_TEXT)
        return out.toString().take(3_500_000)
    }

    private fun extractCsv(reader: BufferedReader): String {
        val out = StringBuilder("ÇALIŞMA KITABI\nSayfa: Varsayılan\n")
        var rowIndex = 0
        reader.useLines { lines ->
            for (line in lines) {
                if (rowIndex >= MAX_ROWS) break
                if (line.isBlank()) continue
                val fields = parseCsvLine(line)
                if (fields.isEmpty()) continue
                out.append("Satır ").append(rowIndex + 1).append(": ")
                    .append(fields.joinToString(" | ") { it.trim() }).append('\n')
                rowIndex++
            }
        }
        if (rowIndex == 0) throw AppError(Err.NO_TEXT)
        out.append("Çözümlenen satır sayısı: $rowIndex\n")
        if (rowIndex >= MAX_ROWS) out.append("UYARI: Büyük dosya, ilk $MAX_ROWS satırla sınırlandırıldı.\n")
        return out.toString()
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !quoted -> quoted = true
                ch == '"' && quoted -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { value.append('"'); i++ } else quoted = false
                }
                ch == ',' && !quoted -> { fields.add(value.toString()); value.setLength(0) }
                else -> value.append(ch)
            }
            i++
        }
        fields.add(value.toString())
        return fields
    }

    private fun cellValue(cell: org.apache.poi.ss.usermodel.Cell): String {
        val raw = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) cell.localDateTimeCellValue.toString() else formatNumber(cell.numericCellValue)
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.STRING -> "[FORMÜL: ${cell.cellFormula}] = ${cell.stringCellValue}"
                CellType.NUMERIC -> "[FORMÜL: ${cell.cellFormula}] = ${formatNumber(cell.numericCellValue)}"
                CellType.BOOLEAN -> "[FORMÜL: ${cell.cellFormula}] = ${cell.booleanCellValue}"
                else -> "[FORMÜL: ${cell.cellFormula}] = sonuç önbelleği yok"
            }
            CellType.ERROR -> "[HÜCRE HATASI: ${cell.errorCellValue}]"
            else -> ""
        }
        return raw.trim().replace('\n', ' ').replace(Regex("\\s+"), " ")
    }

    private fun numericValue(cell: org.apache.poi.ss.usermodel.Cell): Double? {
        if (cell.cellType == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) return cell.numericCellValue
        if (cell.cellType == CellType.FORMULA && cell.cachedFormulaResultType == CellType.NUMERIC) {
            return try { cell.numericCellValue } catch (_: Exception) { null }
        }
        return null
    }

    private fun appendNumericSummary(out: StringBuilder, columns: Map<Int, List<Double>>, count: Int, negative: Int) {
        if (count == 0) return
        out.append("Sayısal hücre: $count; negatif hücre: $negative\n")
        for ((column, values) in columns) {
            if (values.size < 2) continue
            val sum = values.sum()
            val average = sum / values.size
            val min = values.minOrNull() ?: 0.0
            val max = values.maxOrNull() ?: 0.0
            out.append("Sütun ${column + 1}: toplam=${formatNumber(sum)}, ortalama=${formatNumber(average)}, min=${formatNumber(min)}, max=${formatNumber(max)}\n")
        }
    }

    private fun cellAddress(cell: org.apache.poi.ss.usermodel.Cell): String {
        val row = cell.rowIndex + 1
        var col = cell.columnIndex
        val letters = StringBuilder()
        do {
            letters.insert(0, ('A'.code + (col % 26)).toChar())
            col = col / 26 - 1
        } while (col >= 0)
        return "$letters$row"
    }

    private fun formatNumber(value: Double): String {
        val symbols = DecimalFormatSymbols(Locale("tr", "TR")).apply { decimalSeparator = ','; groupingSeparator = '.' }
        val formatter = DecimalFormat("#,##0.########", symbols)
        return formatter.format(value)
    }
}
