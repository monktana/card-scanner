package app.cardscanner.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSessionTest {

    private val empty = ScanSession(locationId = 1, locationLabel = "Bulk: Box 1", foil = false, replaceDeck = false)

    @Test
    fun `neuer Scan kommt nach oben`() {
        val s = empty.withScan(10, 1).withScan(20, 1).withScan(10, 1)
        assertEquals(listOf(10L, 20L), s.recent)
        assertEquals(mapOf(10L to 2, 20L to 1), s.scanned)
        assertEquals(3, s.total)
    }

    @Test
    fun `auf null verschwindet der Eintrag aus der Sitzung`() {
        val s = empty.withScan(10, 2).withScan(20, 1).withScan(10, -2)
        assertEquals(listOf(20L), s.recent)
        assertEquals(mapOf(20L to 1), s.scanned)
    }

    @Test
    fun `Foil umschalten verschiebt die Anzahl auf den neuen Eintrag und behaelt die Position`() {
        val s = empty.withScan(10, 2).withScan(20, 1).moved(from = 10, to = 30)
        assertEquals(listOf(20L, 30L), s.recent)
        assertEquals(mapOf(30L to 2, 20L to 1), s.scanned)
    }

    @Test
    fun `Foil umschalten in einen schon gescannten Eintrag fuehrt zusammen`() {
        val s = empty.withScan(10, 1).withScan(30, 1).moved(from = 10, to = 30)
        assertEquals(listOf(30L), s.recent)
        assertEquals(mapOf(30L to 2), s.scanned)
    }
}
