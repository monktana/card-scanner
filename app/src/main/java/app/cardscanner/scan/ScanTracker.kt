package app.cardscanner.scan

import app.cardscanner.CollectorKey
import app.cardscanner.ParsedCollectorLine

/**
 * Zustandsautomat des Dauerscans, ein Aufruf je Kamerabild. Ohne Android, damit er testbar ist.
 *
 * - Eine Druckversion wird abgefragt ([onLookup]), sobald sie in [STABLE_FRAMES] Bildern
 *   hintereinander gelesen wurde.
 * - Dieselbe Druckversion zählt erst wieder, nachdem die Halterung leer war: [ABSENT_FRAMES]
 *   Bilder mit weniger als [MIN_CARD_LINES] Textzeilen. Ein Bild ohne lesbare Nummer bei
 *   liegender Karte zählt nicht als leer.
 * - Liegt eine Karte [MISS_AFTER_MS] da, ohne sicher erkannt zu sein, kommt [onMiss].
 *   Einzelne Flacker-Treffer setzen diesen Zähler nicht zurück.
 *
 * Nicht threadsicher; der Aufrufer ruft alle Methoden aus demselben Thread.
 */
class ScanTracker(
    private val onLookup: (ParsedCollectorLine, firstSeenAt: Long) -> Unit,
    private val onMiss: () -> Unit,
) {
    private var currentKey: CollectorKey? = null
    private var sameCount = 0
    private var absentCount = 0
    private var firstSeenAt = 0L
    private var unrecognizedSince = 0L
    private var missSignaled = false

    /** Zuletzt gezählte Druckversion; nur eine leere Halterung setzt sie zurück. */
    private var lastCounted: CollectorKey? = null
    private var lastCountedSeenAt = 0L

    /** Ergebnis der letzten Abfrage; wird angezeigt, solange diese Karte liegt. */
    private var lastOutcome: ScanPhase = ScanPhase.Waiting

    fun reset() {
        currentKey = null
        sameCount = 0
        absentCount = 0
        lastCounted = null
        lastOutcome = ScanPhase.Waiting
        resetReading()
    }

    fun onFrame(lineCount: Int, parsed: ParsedCollectorLine?, now: Long): ScanPhase {
        val key = parsed?.key
        if (lineCount < MIN_CARD_LINES) {
            if (++absentCount >= ABSENT_FRAMES) {
                lastCounted = null
                lastOutcome = ScanPhase.Waiting
            }
            currentKey = null
            sameCount = 0
            resetReading()
            return if (lastCounted == null) ScanPhase.Waiting else lastOutcome
        }
        absentCount = 0

        // Die gezählte Karte liegt noch: Ergebnis weiter anzeigen, nicht nochmal zählen.
        if (key != null && key == lastCounted) {
            lastCountedSeenAt = now
            currentKey = null
            sameCount = 0
            resetReading()
            return lastOutcome
        }

        if (key == null) {
            currentKey = null
            sameCount = 0
        } else {
            if (key != currentKey) {
                currentKey = key
                sameCount = 0
                firstSeenAt = now
            }
            if (++sameCount >= STABLE_FRAMES) {
                lastCounted = key
                lastCountedSeenAt = now
                lastOutcome = ScanPhase.Checking(key)
                currentKey = null
                sameCount = 0
                resetReading()
                onLookup(parsed!!, firstSeenAt)
                return lastOutcome
            }
        }

        // Kurzes Flackern der gezählten Karte: Ergebnis weiter anzeigen. Bleibt ihre Nummer länger
        // weg, liegt wohl eine andere, unlesbare Karte – dann läuft der Zähler.
        val countedCardVisible = lastCounted != null && now - lastCountedSeenAt <= COUNTED_GONE_MS
        if (countedCardVisible && currentKey == null) return lastOutcome

        if (unrecognizedSince == 0L) unrecognizedSince = now
        val elapsed = now - unrecognizedSince
        if (elapsed >= MISS_AFTER_MS) {
            if (!missSignaled) {
                missSignaled = true
                onMiss()
            }
            return ScanPhase.NotRecognized
        }
        return ScanPhase.Reading(elapsed / MISS_AFTER_MS.toFloat())
    }

    /**
     * Ergebnis einer Abfrage. Gibt die anzuzeigende Phase zurück, oder null, wenn der Scanner
     * schon bei einer anderen Karte ist. Nach einem Fehler wird dieselbe Karte erst neu
     * abgefragt, wenn sie neu aufgelegt wird.
     */
    fun onOutcome(key: CollectorKey, outcome: ScanPhase): ScanPhase? {
        if (key != lastCounted) return null
        lastOutcome = outcome
        return outcome
    }

    private fun resetReading() {
        unrecognizedSince = 0
        missSignaled = false
    }

    companion object {
        const val STABLE_FRAMES = 2
        const val ABSENT_FRAMES = 2
        const val MIN_CARD_LINES = 4
        const val MISS_AFTER_MS = 3_000L
        const val COUNTED_GONE_MS = 1_500L
    }
}
