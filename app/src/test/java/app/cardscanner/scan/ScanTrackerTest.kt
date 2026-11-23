package app.cardscanner.scan

import app.cardscanner.CollectorKey
import app.cardscanner.ParsedCollectorLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTrackerTest {

    private val lookups = mutableListOf<CollectorKey>()
    private var misses = 0
    private val tracker = ScanTracker(onLookup = { p, _ -> lookups += p.key }, onMiss = { misses++ })
    private var now = 0L

    private val gandalf = ParsedCollectorLine("HOB", "41", "DE", 'R')
    private val bolt = ParsedCollectorLine("M11", "149", "EN", 'C')

    /** Ein Kamerabild alle 100 ms. */
    private fun frame(parsed: ParsedCollectorLine?, lines: Int = 12): ScanPhase {
        now += 100
        return tracker.onFrame(lines, parsed, now)
    }

    private fun empty() = frame(null, lines = 0)

    private fun counted(name: String) = tracker.onOutcome(lookups.last(), ScanPhase.Counted(name))

    @Test
    fun `zwei gleiche Bilder loesen eine Abfrage aus`() {
        assertTrue(frame(gandalf) is ScanPhase.Reading)
        assertEquals(ScanPhase.Checking(gandalf.key), frame(gandalf))
        assertEquals(listOf(gandalf.key), lookups)
    }

    @Test
    fun `liegende Karte zaehlt nur einmal und zeigt das Ergebnis`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        repeat(30) { assertEquals(ScanPhase.Counted("Gandalf"), frame(gandalf)) }
        assertEquals(1, lookups.size)
    }

    @Test
    fun `flackernde Nummer zaehlt nicht doppelt`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        // 5 Bilder ohne lesbare Nummer, Karte liegt aber (viel Text) – früher galt das als „weg“.
        repeat(5) { assertEquals(ScanPhase.Counted("Gandalf"), frame(null)) }
        frame(gandalf); frame(gandalf)
        assertEquals(1, lookups.size)
    }

    @Test
    fun `langes Flackern zaehlt auch nicht doppelt`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        repeat(25) { frame(null) }
        frame(gandalf); frame(gandalf)
        assertEquals(1, lookups.size)
    }

    @Test
    fun `zweites Exemplar zaehlt nach leerer Halterung`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        assertEquals(ScanPhase.Waiting, run { empty(); empty() })
        frame(gandalf); frame(gandalf)
        assertEquals(2, lookups.size)
    }

    @Test
    fun `andere Karte direkt darueber zaehlt sofort`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        frame(bolt); frame(bolt)
        assertEquals(listOf(gandalf.key, bolt.key), lookups)
    }

    @Test
    fun `unlesbare Karte meldet sich nach 3 Sekunden genau einmal`() {
        // Der Zähler startet mit dem ersten Bild; nach 30 weiteren Bildern (3 s) ist Schluss.
        var phase: ScanPhase = ScanPhase.Waiting
        repeat(30) { phase = frame(null) }
        assertTrue(phase is ScanPhase.Reading)
        assertEquals(ScanPhase.NotRecognized, frame(null))
        repeat(10) { assertEquals(ScanPhase.NotRecognized, frame(null)) }
        assertEquals(1, misses)
    }

    @Test
    fun `einzelne Flacker-Treffer setzen den 3-Sekunden-Zaehler nicht zurueck`() {
        // Nummer wird nur in jedem dritten Bild gelesen, nie zweimal hintereinander.
        var phase: ScanPhase = ScanPhase.Waiting
        repeat(33) { i -> phase = frame(if (i % 3 == 0) gandalf else null) }
        assertEquals(ScanPhase.NotRecognized, phase)
        assertEquals(1, misses)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `unlesbare Karte direkt nach gezaehlter Karte meldet sich auch`() {
        frame(gandalf); frame(gandalf)
        counted("Gandalf")
        repeat(15) { frame(null) } // 1,5 s: zeigt noch Gandalf
        var phase: ScanPhase = ScanPhase.Waiting
        repeat(31) { phase = frame(null) }
        assertEquals(ScanPhase.NotRecognized, phase)
        assertEquals(1, misses)
    }

    @Test
    fun `Ergebnis einer alten Karte wird nicht mehr angezeigt`() {
        frame(gandalf); frame(gandalf)
        frame(bolt); frame(bolt)
        assertEquals(null, tracker.onOutcome(gandalf.key, ScanPhase.Counted("Gandalf")))
    }

    @Test
    fun `nach Fehler erst neu abfragen, wenn die Karte neu aufgelegt wird`() {
        frame(gandalf); frame(gandalf)
        tracker.onOutcome(gandalf.key, ScanPhase.Problem("offline"))
        repeat(5) { assertEquals(ScanPhase.Problem("offline"), frame(gandalf)) }
        empty(); empty()
        frame(gandalf); frame(gandalf)
        assertEquals(2, lookups.size)
    }
}
