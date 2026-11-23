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

/** Was der Scanner gerade tut; wird immer angezeigt, damit nie unklar ist, ob die App hängt. */
sealed interface ScanPhase {
    data object Waiting : ScanPhase
    /** Karte liegt da, Set und Nummer noch nicht sicher gelesen; [progress] bis „nicht erkannt“. */
    data class Reading(val progress: Float) : ScanPhase
    data class Checking(val key: CollectorKey) : ScanPhase
    data class Counted(val name: String) : ScanPhase
    data object NotRecognized : ScanPhase
    data class Problem(val message: String) : ScanPhase
}

data class ScanUiState(
    val session: ScanSession? = null,
    val phase: ScanPhase = ScanPhase.Waiting,
    val parsed: ParsedCollectorLine? = null,
    val rawLines: List<String> = emptyList(),
    val ocrMs: Long = 0,
    val message: String? = null,
)

/**
 * Dauerscan: Live-OCR → Parser → [ScanTracker] → Scryfall → Sammlung.
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
    private val tracker = ScanTracker(onLookup = ::lookup, onMiss = { feedback.miss() })

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
            analysisExecutor.execute { tracker.reset() }
            setSession(ScanSession(locationId, location.label, foil, replaceDeck, before))
            _state.update { it.copy(message = null, phase = ScanPhase.Waiting) }
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
        _state.update { it.copy(rawLines = lines, parsed = parsed, ocrMs = ocrMs) }
        setPhase(tracker.onFrame(lines.size, parsed, SystemClock.elapsedRealtime()), lines.size, parsed?.key)
    }

    private fun setPhase(phase: ScanPhase, lines: Int, key: CollectorKey?) {
        val old = _state.value.phase
        if (old::class != phase::class) diagnostics.appendEvent(phase.javaClass.simpleName, lines, key?.let { "${it.setCode} ${it.number}" })
        if (old != phase) _state.update { it.copy(phase = phase) }
    }

    private fun resolveSet(raw: String): String? {
        val catalog = setCatalog ?: return raw
        return catalog.resolve(raw)
    }

    private fun lookup(parsed: ParsedCollectorLine, firstSeenAt: Long) {
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = scryfall.find(parsed)
            val now = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { diagnostics.appendHit(parsed, result, now - firstSeenAt, now - t0) }

            val session = _state.value.session ?: return@launch
            val outcome = when (result) {
                is LookupResult.Found -> {
                    val entryId = repository.addCopies(result.card, session.foil, session.locationId)
                    updateSession { it.withScan(entryId, 1) }
                    feedback.hit()
                    ScanPhase.Counted(result.card.printedName ?: result.card.name)
                }
                LookupResult.NotFound -> {
                    feedback.miss()
                    ScanPhase.Problem("${parsed.setCode} ${parsed.number} gibt es bei Scryfall nicht – beiseitelegen")
                }
                is LookupResult.Failed -> {
                    feedback.miss()
                    ScanPhase.Problem("Scryfall nicht erreichbar (${result.message}) – Karte neu auflegen")
                }
            }
            // Nur anzeigen, wenn noch dieselbe Karte liegt; sonst ist der Scanner schon weiter.
            analysisExecutor.execute {
                tracker.onOutcome(parsed.key, outcome)?.let { setPhase(it, 0, parsed.key) }
            }
        }
    }

    fun close() {
        recognizer.close()
        analysisExecutor.shutdown()
        feedback.release()
    }
}
