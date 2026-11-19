package app.cardscanner

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Protokoll für die Auswertung des Spikes. Liegt im internen App-Speicher und wird mit
 *   adb exec-out run-as app.cardscanner cat files/spike/treffer.csv
 * auf den PC geholt.
 */
class SpikeLog(private val dir: File) {

    private val hits = File(dir, "treffer.csv")
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    init {
        dir.mkdirs()
        if (!hits.exists()) {
            hits.writeText("zeit;set_ocr;nr_ocr;sprache_ocr;ergebnis;set;nr;sprache;name;druckname;ocr_bis_treffer_ms;scryfall_ms\n")
        }
    }

    @Synchronized
    fun appendHit(parsed: ParsedCollectorLine, result: LookupResult, ocrToHitMs: Long, scryfallMs: Long) {
        val card = (result as? LookupResult.Found)?.card
        val outcome = when (result) {
            is LookupResult.Found -> "ok"
            LookupResult.NotFound -> "nicht_gefunden"
            is LookupResult.Failed -> "fehler:${result.message}"
        }
        val row = listOf(
            LocalDateTime.now().toString(), parsed.setCode, parsed.number, parsed.language ?: "",
            outcome, card?.setCode ?: "", card?.collectorNumber ?: "", card?.lang ?: "",
            card?.name ?: "", card?.printedName ?: "", ocrToHitMs.toString(), scryfallMs.toString(),
        ).joinToString(";") { it.replace(';', ',') }
        hits.appendText(row + "\n")
    }

    /** Speichert Kamerabild und OCR-Text einer Karte, die nicht erkannt wurde. */
    fun saveCapture(bitmap: Bitmap, rotationDegrees: Int, lines: List<String>): String {
        val name = "nicht-erkannt-" + LocalDateTime.now().format(stamp)
        val rotated = if (rotationDegrees == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        )
        File(dir, "$name.jpg").outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        File(dir, "$name.txt").writeText(lines.joinToString("\n"))
        return name
    }
}
