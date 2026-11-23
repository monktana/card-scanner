package app.cardscanner.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanSessionJsonTest {

    @Test
    fun sitzungUebersteht() {
        val session = ScanSession(
            locationId = 7, locationLabel = "Deck: vivi-ornitier", foil = true, replaceDeck = true,
            before = mapOf(1L to 2, 2L to 1), scanned = mapOf(3L to 1), recent = listOf(3L),
        )
        assertEquals(session, ScanSession.fromJson(session.toJson()))
    }

    @Test
    fun ohneLagerort() {
        val session = ScanSession(locationId = null, locationLabel = "Ohne Lagerort", foil = false, replaceDeck = false)
        assertEquals(session, ScanSession.fromJson(session.toJson()))
    }
}
