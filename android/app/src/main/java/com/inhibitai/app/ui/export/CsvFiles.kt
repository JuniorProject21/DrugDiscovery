package com.inhibitai.app.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.inhibitai.app.data.BatchResult
import com.inhibitai.app.data.Compound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reading a CSV of compounds to screen, and writing results back out as a CSV for Excel.
 *
 * Both directions are deliberately plain text: a researcher's compound list arrives as a
 * spreadsheet export, and the results have to go back into one for further analysis.
 */

// ---------- reading a file of compounds ----------

/** What was found in the file the user picked. */
data class ParsedCsv(
    val fileName: String,
    /** SMILES and the name from the file, in file order. */
    val rows: List<Pair<String, String?>>,
    /** Lines that were skipped, and why: shown above the table so nothing goes missing silently. */
    val warnings: List<String> = emptyList(),
)

class CsvFormatException(message: String) : Exception(message)

object CsvImport {

    /** Largest file read, and the most rows the backend accepts in one batch. */
    private const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_ROWS = 500

    private val SMILES_HEADERS = setOf("smiles", "smile", "structure", "canonical_smiles", "canonicalsmiles", "smi")
    private val NAME_HEADERS = setOf("name", "id", "compound", "compound_id", "compoundid", "label", "title", "molecule")

    suspend fun read(context: Context, uri: Uri): ParsedCsv = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) {
                throw CsvFormatException("That file is larger than 4 MB. Please split it into smaller lists.")
            }
            bytes.toString(Charsets.UTF_8)
        } ?: throw CsvFormatException("Couldn't open that file.")
        parse(text, name)
    }

    /** Separated from [read] so the parsing rules can be exercised without a content provider. */
    fun parse(text: String, fileName: String): ParsedCsv {
        // A UTF-8 byte-order mark from Excel would otherwise end up inside the first cell.
        val lines = text.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw CsvFormatException("That file is empty.")

        val delimiter = delimiterOf(lines.first())
        val table = lines.map { splitLine(it, delimiter) }
        val header = table.first()
        val hasHeader = header.any { it.trim().lowercase() in SMILES_HEADERS }

        val smilesColumn: Int
        var nameColumn: Int?
        if (hasHeader) {
            smilesColumn = header.indexOfFirst { it.trim().lowercase() in SMILES_HEADERS }
            nameColumn = header.indexOfFirst { it.trim().lowercase() in NAME_HEADERS }.takeIf { it >= 0 }
        } else {
            // No header row: the structures are in the first column that looks like SMILES, and a
            // column before it (a spreadsheet's name column) is taken as the name.
            val guess = header.indexOfFirst { looksLikeSmiles(it) }
            if (guess < 0) {
                throw CsvFormatException(
                    "No SMILES found. Give the column a \"SMILES\" heading, or put one SMILES per line.",
                )
            }
            smilesColumn = guess
            nameColumn = (0 until smilesColumn).lastOrNull() ?: (smilesColumn + 1).takeIf { it in header.indices }
        }
        if (nameColumn == smilesColumn) nameColumn = null

        val body = if (hasHeader) table.drop(1) else table
        val rows = mutableListOf<Pair<String, String?>>()
        val warnings = mutableListOf<String>()
        body.forEachIndexed { i, cells ->
            // +1 for 1-based line numbers, +1 again when a header took the first line.
            val line = i + 1 + if (hasHeader) 1 else 0
            val smiles = cells.getOrNull(smilesColumn)?.trim().orEmpty()
            if (smiles.isEmpty()) {
                warnings += "Line $line has no SMILES, so it was skipped."
                return@forEachIndexed
            }
            if (rows.size >= MAX_ROWS) return@forEachIndexed
            val label = nameColumn?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            rows += smiles to label
        }
        if (rows.isEmpty()) throw CsvFormatException("No compounds found in that file.")
        val skipped = body.count { it.getOrNull(smilesColumn)?.isNotBlank() == true } - rows.size
        if (skipped > 0) {
            warnings += "Only the first $MAX_ROWS compounds were screened; $skipped more were left out."
        }
        return ParsedCsv(fileName, rows, warnings)
    }

    /** Comma, semicolon or tab, whichever the file actually uses. */
    private fun delimiterOf(line: String): Char =
        listOf(',', ';', '\t').maxByOrNull { d -> line.count { it == d } }
            ?.takeIf { d -> line.any { it == d } } ?: ','

    /** One CSV line into cells, honouring "quoted, fields" and doubled "" escapes. */
    private fun splitLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    cells += cell.toString()
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }

    /**
     * Whether a cell is a structure rather than a name. SMILES are written with brackets, bonds
     * and ring numbers, which compound names practically never contain.
     */
    private fun looksLikeSmiles(cell: String): Boolean {
        val value = cell.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        if (value.toDoubleOrNull() != null) return false
        return value.any { it in "()[]=#@123456789/\\" } && value.any { it.isLetter() }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "compounds.csv"
    }
}

// ---------- writing results out ----------

object CsvExport {

    /** Every prediction in the user's history, for analysis in Excel. */
    suspend fun shareHistory(context: Context, compounds: List<Compound>) {
        val csv = buildString {
            row(
                "Name", "Prediction", "Confidence (%)", "Model score", "Threshold", "Reliability",
                "Nearest training similarity", "In applicability domain", "Formula", "Molecular weight (g/mol)",
                "Common name", "IUPAC name", "PubChem CID", "Target", "Model version", "Saved",
                "Predicted at", "Input SMILES", "Canonical SMILES",
            )
            compounds.sortedByDescending { it.timestamp }.forEach { c ->
                row(
                    c.name, c.status.label, c.confidence.toString(), decimal(c.score, 4), decimal(c.threshold, 4),
                    c.reliability, decimal(c.nearestSimilarity, 3), c.inDomain?.let { if (it) "Yes" else "No" },
                    c.formula, decimal(c.molecularWeight, 2), c.commonName, c.iupacName, c.pubchemCid?.toString(),
                    c.target, c.modelVersion, if (c.isSaved) "Yes" else "No",
                    c.timestamp.format(TIMESTAMP), c.smiles, c.canonicalSmiles,
                )
            }
        }
        share(context, csv, "InhibitAI_history_${stamp()}.csv", "InhibitAI prediction history (${compounds.size})")
    }

    /** The screening table from one uploaded file, including the rows that could not be read. */
    suspend fun shareBatch(context: Context, result: BatchResult) {
        val csv = buildString {
            row(
                "Line", "Name", "Name in file", "Prediction", "Confidence (%)", "Model score", "Threshold",
                "Reliability", "Formula", "Molecular weight (g/mol)", "Problem", "Saved to history",
                "Input SMILES", "Canonical SMILES",
            )
            result.rows.forEach { r ->
                row(
                    (r.index + 1).toString(), r.name, r.labelFromFile, r.status?.label, r.confidence?.toString(),
                    decimal(r.score, 4), decimal(r.threshold, 4), r.reliability, r.formula,
                    decimal(r.molecularWeight, 2), r.error, if (r.savedId != null) "Yes" else "No",
                    r.inputSmiles, r.canonicalSmiles,
                )
            }
        }
        val base = result.fileName.substringBeforeLast('.').ifBlank { "batch" }
        share(context, csv, "InhibitAI_${safe(base)}_results.csv",
            "InhibitAI screening results: ${result.succeeded} of ${result.total} compounds")
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US))

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)

    private fun decimal(value: Number?, places: Int) =
        value?.let { String.format(Locale.US, "%.${places}f", it.toDouble()) }

    /** Excel reads CRLF rows, and a blank cell for anything the server didn't provide. */
    private fun StringBuilder.row(vararg cells: String?) {
        append(cells.joinToString(",") { quote(it.orEmpty()) })
        append("\r\n")
    }

    private fun quote(value: String): String {
        val cleaned = value.replace("\r", " ").replace("\n", " ")
        // A SMILES can contain a comma (salts, e.g. "...C.Cl"), so cells are always quoted.
        return "\"" + cleaned.replace("\"", "\"\"") + "\""
    }

    private suspend fun share(context: Context, csv: String, fileName: String, subject: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }  // keep only the latest export
            File(dir, fileName).apply {
                // The byte-order mark is what makes Excel open the file as UTF-8.
                writeText("﻿$csv")
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Save or share the CSV"))
    }
}
