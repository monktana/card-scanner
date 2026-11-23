package app.cardscanner

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

data class Hit(
    val parsed: ParsedCollectorLine,
    val result: LookupResult,
    val ocrToHitMs: Long,
)

data class SpikeState(
    val rawLines: List<String> = emptyList(),
    val parsed: ParsedCollectorLine? = null,
    val ocrMs: Long = 0,
    val frames: Int = 0,
    val lookingUp: CollectorKey? = null,
    val hits: List<Hit> = emptyList(),
    val message: String? = null,
)

/**
 * Spike-Logik: Live-OCR → Parser → Scryfall. Eine Druckversion wird abgefragt, sobald sie in
 * [STABLE_FRAMES] Bildern hintereinander gelesen wurde. Dieselbe Druckversion zählt erst wieder,
 * wenn zwischendurch [EMPTY_FRAMES] Bilder ohne Treffer kamen („Karte weg“).
 */
class SpikeController(context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(SpikeState())
    val state: StateFlow<SpikeState> = _state

    val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scryfall = ScryfallClient()
    private val log = SpikeLog(File(context.filesDir, "spike"))

    // Bis die Setliste geladen ist (oder wenn sie fehlt), wird jeder Set-Code angenommen.
    @Volatile private var setCatalog: SetCatalog? = null

    init {
        val loader = SetCatalogLoader(File(context.filesDir, "sets.txt"), scryfall)
        scope.launch {
            val catalog = withContext(Dispatchers.IO) { loader.load() }
            setCatalog = catalog
            _state.update {
                it.copy(message = catalog?.let { c -> "Setliste: ${c.size} Sets" } ?: "Setliste nicht geladen, Set-Codes ungeprüft")
            }
        }
    }

    private fun resolveSet(raw: String): String? {
        val catalog = setCatalog ?: return raw
        return catalog.resolve(raw)
    }

    // Nur im analysisExecutor verwendet.
    private var currentKey: CollectorKey? = null
    private var sameCount = 0
    private var emptyCount = 0
    private var lastCounted: CollectorKey? = null
    private var firstSeenAt = 0L

    @Volatile private var captureRequested = false

    val analyzer = ImageAnalysis.Analyzer(::analyze)

    fun requestCapture() {
        captureRequested = true
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image ?: return proxy.close()
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val capture = if (captureRequested) proxy.toBitmap() else null
        val started = SystemClock.elapsedRealtime()

        recognizer.process(input).addOnCompleteListener(analysisExecutor) { task ->
            try {
                if (!task.isSuccessful) return@addOnCompleteListener
                val lines = task.result.textBlocks.flatMap { it.lines }.map { it.text }
                val ocrMs = SystemClock.elapsedRealtime() - started
                if (capture != null) {
                    captureRequested = false
                    val name = log.saveCapture(capture, rotation, lines)
                    _state.update { it.copy(message = "Gespeichert: $name") }
                }
                onLines(lines, ocrMs)
            } finally {
                proxy.close()
            }
        }
    }

    private fun onLines(lines: List<String>, ocrMs: Long) {
        val parsed = CollectorLineParser.parse(lines, ::resolveSet)
        val key = parsed?.key
        _state.update { it.copy(rawLines = lines, parsed = parsed, ocrMs = ocrMs, frames = it.frames + 1) }

        if (key == null) {
            if (++emptyCount >= EMPTY_FRAMES) lastCounted = null
            currentKey = null
            sameCount = 0
            return
        }
        emptyCount = 0
        if (key != currentKey) {
            currentKey = key
            sameCount = 0
            firstSeenAt = SystemClock.elapsedRealtime()
        }
        if (++sameCount == STABLE_FRAMES && key != lastCounted) {
            lastCounted = key
            lookup(parsed, firstSeenAt)
        }
    }

    private fun lookup(parsed: ParsedCollectorLine, firstSeenAt: Long) {
        scope.launch {
            _state.update { it.copy(lookingUp = parsed.key) }
            val t0 = SystemClock.elapsedRealtime()
            val result = scryfall.find(parsed)
            val now = SystemClock.elapsedRealtime()
            val hit = Hit(parsed, result, now - firstSeenAt)
            log.appendHit(parsed, result, now - firstSeenAt, now - t0)
            _state.update {
                it.copy(lookingUp = null, hits = (listOf(hit) + it.hits).take(MAX_HITS))
            }
        }
    }

    fun close() {
        recognizer.close()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val STABLE_FRAMES = 2
        const val EMPTY_FRAMES = 3
        const val MAX_HITS = 50
    }
}
