package app.cardscanner.scan

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.cardscanner.CollectorKey
import app.cardscanner.CollectorLineParser
import app.cardscanner.LookupResult
import app.cardscanner.ParsedCollectorLine
import app.cardscanner.ScryfallClient
import app.cardscanner.SetCatalog
import app.cardscanner.SetCatalogLoader
import app.cardscanner.SpikeLog
import app.cardscanner.data.CollectionEntry
import app.cardscanner.data.CollectionRepository
import app.cardscanner.ui.LocationChoice
import app.cardscanner.ui.resolve
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class ScanUiState(
    val session: ScanSession? = null,
    val parsed: ParsedCollectorLine? = null,
    val rawLines: List<String> = emptyList(),
    val ocrMs: Long = 0,
    val lookingUp: CollectorKey? = null,
    /** Eine Karte liegt seit [MISS_AFTER_MS] da, ohne dass Set und Nummer gelesen wurden. */
    val notRecognized: Boolean = false,
    val message: String? = null,
)

/**
 * Dauerscan: Live-OCR → Parser → Scryfall → Sammlung. Eine Druckversion wird abgefragt, sobald
 * sie in [STABLE_FRAMES] Bildern hintereinander gelesen wurde. Dieselbe Druckversion zählt erst
 * wieder, wenn zwischendurch [EMPTY_FRAMES] Bilder ohne Treffer kamen („Karte weg“).
 *
 * Alle Änderungen an Sitzung und Sammlung laufen über [scope] (Main-Thread).
 */
class ScanController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: CollectionRepository,
    private val scryfall: ScryfallClient,
) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state

    val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val diagnostics = SpikeLog(File(context.filesDir, "spike"))
    private val sessions = SessionStore(File(context.filesDir, "session.json"))
    private val feedback = Feedback(context)

    // Bis die Setliste geladen ist (oder wenn sie fehlt), wird jeder Set-Code angenommen.
    @Volatile private var setCatalog: SetCatalog? = null

    // Nur im analysisExecutor verwendet.
    private var currentKey: CollectorKey? = null
    private var sameCount = 0
    private var emptyCount = 0
    private var lastCounted: CollectorKey? = null
    private var firstSeenAt = 0L
    private var unrecognizedSince = 0L
    private var missSignaled = false

    @Volatile private var captureRequested = false

    val analyzer = ImageAnalysis.Analyzer(::analyze)

    init {
        _state.update { it.copy(session = sessions.load()) }
        val loader = SetCatalogLoader(File(context.filesDir, "sets.txt"), scryfall)
        scope.launch {
            setCatalog = withContext(Dispatchers.IO) { loader.load() }
            if (setCatalog == null) message("Setliste nicht geladen, Set-Codes ungeprüft")
        }
    }

    // --- Sitzung -------------------------------------------------------------------------

    fun start(location: LocationChoice, foil: Boolean, replaceDeck: Boolean) {
        scope.launch {
            val locationId = repository.resolve(location)
            val before = if (replaceDeck && locationId != null) repository.quantitiesIn(locationId) else emptyMap()
            analysisExecutor.execute { lastCounted = null }
            setSession(ScanSession(locationId, location.label, foil, replaceDeck, before))
            _state.update { it.copy(message = null, notRecognized = false) }
        }
    }

    fun setFoil(foil: Boolean) = updateSession { it.copy(foil = foil) }

    fun finish() {
        scope.launch {
            val s = _state.value.session ?: return@launch
            if (s.replaceDeck && s.locationId != null) {
                repository.finishDeckReplace(s.locationId, s.before, s.scanned)
            }
            setSession(null)
            _state.update { ScanUiState() }
        }
    }

    fun recentEntries(ids: List<Long>): Flow<List<CollectionEntry>> = repository.observeEntries(ids)

    fun increment(entryId: Long) = scope.launch {
        repository.addToEntry(entryId, 1)
        updateSession { it.withScan(entryId, 1) }
    }

    fun decrement(entryId: Long) = scope.launch {
        repository.removeCopies(entryId, 1)
        updateSession { it.withScan(entryId, -1) }
    }

    /** Verwirft alle in dieser Sitzung gescannten Exemplare des Eintrags. */
    fun discard(entryId: Long) = scope.launch {
        val count = _state.value.session?.scanned?.get(entryId) ?: return@launch
        repository.removeCopies(entryId, count)
        updateSession { it.withScan(entryId, -count) }
    }

    /** Schaltet Foil nur für die in dieser Sitzung gescannten Exemplare um. */
    fun toggleFoil(entry: CollectionEntry) = scope.launch {
        val count = _state.value.session?.scanned?.get(entry.id) ?: return@launch
        val target = repository.moveCopies(entry.id, count, !entry.foil, entry.locationId) ?: return@launch
        updateSession { it.moved(entry.id, target) }
    }

    fun requestCapture() {
        captureRequested = true
    }

    private fun updateSession(change: (ScanSession) -> ScanSession) {
        _state.value.session?.let { setSession(change(it)) }
    }

    private fun setSession(session: ScanSession?) {
        sessions.save(session)
        _state.update { it.copy(session = session) }
    }

    private fun message(text: String?) = _state.update { it.copy(message = text) }

    // --- Erkennung -----------------------------------------------------------------------

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || _state.value.session == null) return proxy.close()
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val capture = if (captureRequested) proxy.toBitmap() else null
        val started = SystemClock.elapsedRealtime()

        recognizer.process(input).addOnCompleteListener(analysisExecutor) { task ->
            try {
                if (!task.isSuccessful) return@addOnCompleteListener
                val lines = task.result.textBlocks.flatMap { it.lines }.map { it.text }
                if (capture != null) {
                    captureRequested = false
                    message("Gespeichert: ${diagnostics.saveCapture(capture, rotation, lines)}")
                }
                onLines(lines, SystemClock.elapsedRealtime() - started)
            } finally {
                proxy.close()
            }
        }
    }

    private fun onLines(lines: List<String>, ocrMs: Long) {
        val parsed = CollectorLineParser.parse(lines, ::resolveSet)
        val key = parsed?.key
        _state.update { it.copy(rawLines = lines, parsed = parsed, ocrMs = ocrMs) }
        trackUnrecognized(lines, parsed)

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

    /** Signal, wenn eine Karte (viel Text) länger daliegt, ohne dass Set und Nummer gelesen werden. */
    private fun trackUnrecognized(lines: List<String>, parsed: ParsedCollectorLine?) {
        val cardPresent = lines.size >= MIN_CARD_LINES
        if (parsed != null || !cardPresent) {
            unrecognizedSince = 0
            missSignaled = false
            if (_state.value.notRecognized) _state.update { it.copy(notRecognized = false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (unrecognizedSince == 0L) unrecognizedSince = now
        if (!missSignaled && now - unrecognizedSince >= MISS_AFTER_MS) {
            missSignaled = true
            feedback.miss()
            _state.update { it.copy(notRecognized = true) }
        }
    }

    private fun resolveSet(raw: String): String? {
        val catalog = setCatalog ?: return raw
        return catalog.resolve(raw)
    }

    private fun lookup(parsed: ParsedCollectorLine, firstSeenAt: Long) {
        scope.launch {
            _state.update { it.copy(lookingUp = parsed.key) }
            val t0 = SystemClock.elapsedRealtime()
            val result = scryfall.find(parsed)
            val now = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { diagnostics.appendHit(parsed, result, now - firstSeenAt, now - t0) }
            _state.update { it.copy(lookingUp = null) }

            val session = _state.value.session ?: return@launch
            when (result) {
                is LookupResult.Found -> {
                    val entryId = repository.addCopies(result.card, session.foil, session.locationId)
                    updateSession { it.withScan(entryId, 1) }
                    feedback.hit()
                    message(null)
                }
                LookupResult.NotFound -> {
                    feedback.miss()
                    message("Nicht bei Scryfall: ${parsed.setCode} ${parsed.number}")
                }
                is LookupResult.Failed -> {
                    feedback.miss()
                    message("Scryfall-Fehler: ${result.message}")
                    // Nochmal versuchen, sobald die Karte erneut erkannt wird.
                    analysisExecutor.execute { if (lastCounted == parsed.key) lastCounted = null }
                }
            }
        }
    }

    fun close() {
        recognizer.close()
        analysisExecutor.shutdown()
        feedback.release()
    }

    private companion object {
        const val STABLE_FRAMES = 2
        const val EMPTY_FRAMES = 3
        const val MIN_CARD_LINES = 4
        const val MISS_AFTER_MS = 3_000L
    }
}
