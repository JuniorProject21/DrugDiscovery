package com.inhibitai.app.ui.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.screens.reliabilityNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One-page A4 report of a prediction, written on the phone and handed to the share sheet. */
object PdfReport {

    private const val PAGE_WIDTH = 595  // A4 in PostScript points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f

    private val PURPLE = Color.rgb(109, 40, 217)
    private val TEXT = Color.rgb(24, 24, 27)
    private val MUTED = Color.rgb(100, 100, 110)
    private val RULE = Color.rgb(220, 218, 230)

    suspend fun share(context: Context, compound: Compound, structure: Bitmap?) {
        val file = withContext(Dispatchers.IO) { write(context, compound, structure) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "InhibitAI prediction: ${compound.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Save or share the report"))
    }

    private fun write(context: Context, c: Compound, structure: Bitmap?): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        Page(page.canvas).draw(c, structure)
        document.finishPage(page)

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // keep only the latest report
        val file = File(dir, "InhibitAI_${c.name.replace(Regex("[^A-Za-z0-9._-]+"), "_")}.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    private class Page(private val canvas: Canvas) {
        private var y = MARGIN
        private val width = PAGE_WIDTH - 2 * MARGIN

        fun draw(c: Compound, structure: Bitmap?) {
            text("InhibitAI", 22f, PURPLE, bold = true)
            text("PARP-1 inhibitor activity prediction", 12f, MUTED)
            y += 6f
            rule()

            text(c.name, 20f, TEXT, bold = true)
            c.iupacName?.let { text(it, 9.5f, MUTED) }
            val facts = listOfNotNull(
                c.formula,
                c.molecularWeight?.let { String.format(Locale.US, "%.1f g/mol", it) },
                c.pubchemCid?.let { "PubChem CID $it" },
            )
            if (facts.isNotEmpty()) text(facts.joinToString("   ·   "), 10.5f, TEXT)
            text("SMILES: ${c.canonicalSmiles}", 9f, MUTED, mono = true)
            y += 8f

            // Structure on the left, results on the right
            val top = y
            val box = 200f
            val frame = RectF(MARGIN, top, MARGIN + box, top + box)
            canvas.drawRoundRect(frame, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = RULE; strokeWidth = 1f
            })
            structure?.let { canvas.drawBitmap(it, null, RectF(frame.left + 8, frame.top + 8, frame.right - 8, frame.bottom - 8), null) }

            val left = MARGIN + box + 24f
            val colWidth = PAGE_WIDTH - MARGIN - left
            var ry = top
            fun row(label: String, value: String, color: Int = TEXT, size: Float = 12f) {
                ry += drawText(label.uppercase(Locale.US), left, ry, colWidth, 8f, MUTED, bold = true) + 2f
                ry += drawText(value, left, ry, colWidth, size, color, bold = true) + 9f
            }
            row("Prediction", c.status.label, statusColor(c.status), 18f)
            row("Confidence", "${c.confidence}%")
            row("Model score", String.format(Locale.US, "%.3f  (threshold %.3f)", c.score, c.threshold))
            row("Reliability", listOfNotNull(c.reliability, c.nearestSimilarity?.let {
                String.format(Locale.US, "nearest training compound %.2f similar", it)
            }).joinToString(": ").ifEmpty { "Not available" })
            row("Target · model", "${c.target} · ${c.modelVersion}")
            y = maxOf(top + box, ry) + 14f

            text("ADME: predictions are not available yet.", 10f, MUTED)
            val notes = reliabilityNotes(c)
            if (notes.isNotEmpty()) {
                y += 4f
                text("Read with care", 11f, TEXT, bold = true)
                notes.forEach { text("•  $it", 10f, TEXT) }
            }
            y += 6f
            rule()
            text(
                "This is a computational prediction from the InhibitAI model (Extra Trees on Morgan fingerprints, with " +
                    "conformal prediction for uncertain cases). It is not experimental validation: confirm promising " +
                    "compounds in the lab.", 9f, MUTED,
            )
            text("Predicted ${c.formattedDate}  ·  Report created " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy · hh:mm a", Locale.US)), 9f, MUTED)
        }

        private fun text(value: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) {
            y += drawText(value, MARGIN, y, width, size, color, bold, mono) + size * 0.45f
        }

        private fun rule() {
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, Paint().apply { color = RULE; strokeWidth = 1f })
            y += 14f
        }

        /** Draws wrapped text and returns its height. */
        private fun drawText(
            value: String, x: Float, top: Float, maxWidth: Float, size: Float, color: Int,
            bold: Boolean = false, mono: Boolean = false,
        ): Float {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = when {
                    mono -> Typeface.MONOSPACE
                    bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    else -> Typeface.SANS_SERIF
                }
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, maxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            canvas.save()
            canvas.translate(x, top)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }

        private fun statusColor(status: PredictionStatus) = when (status) {
            PredictionStatus.ACTIVE -> Color.rgb(22, 163, 74)
            PredictionStatus.INACTIVE -> Color.rgb(220, 38, 38)
            PredictionStatus.UNCERTAIN -> Color.rgb(217, 119, 6)
        }
    }
}
