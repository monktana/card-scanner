package app.cardscanner.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cardscanner.ScryfallCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CollectionRepository

    private val boltEn = card("bolt-en", "en")
    private val boltDe = card("bolt-de", "de", printedName = "Blitzschlag")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = CollectionRepository(db) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun entries() = repo.entries.first().map { it.entry }

    @Test
    fun gleicheKarteErhoehtAnzahl() = runTest {
        repo.addCopies(boltEn, foil = false, locationId = null)
        repo.addCopies(boltEn, foil = false, locationId = null, count = 2)
        val all = entries()
        assertEquals(1, all.size)
        assertEquals(3, all.single().quantity)
    }

    @Test
    fun foilUndLagerortTrennenEintraege() = runTest {
        val box = repo.location(LocationKind.BULK, "Box 1")
        repo.addCopies(boltEn, foil = false, locationId = null)
        repo.addCopies(boltEn, foil = true, locationId = null)
        repo.addCopies(boltEn, foil = false, locationId = box.id)
        assertEquals(3, entries().size)
    }

    @Test
    fun lagerortWirdNurEinmalAngelegt() = runTest {
        val a = repo.location(LocationKind.DECK, "vivi-ornitier")
        val b = repo.location(LocationKind.DECK, " vivi-ornitier ")
        val c = repo.location(LocationKind.BULK, "vivi-ornitier")
        assertEquals(a.id, b.id)
        assertTrue(a.id != c.id)
    }

    @Test
    fun foilUmschaltenFuehrtMitVorhandenemEintragZusammen() = runTest {
        repo.addCopies(boltEn, foil = true, locationId = null, count = 2)
        val nonFoil = repo.addCopies(boltEn, foil = false, locationId = null)
        repo.saveEntry(nonFoil, quantity = 1, foil = true, locationId = null)
        val all = entries()
        assertEquals(1, all.size)
        assertEquals(3, all.single().quantity)
        assertTrue(all.single().foil)
    }

    @Test
    fun spracheAendernTauschtDruckversion() = runTest {
        val id = repo.addCopies(boltEn, foil = false, locationId = null)
        repo.saveEntry(id, quantity = 1, foil = false, locationId = null, card = boltDe)
        val entry = entries().single()
        assertEquals("bolt-de", entry.scryfallId)
        assertEquals("de", entry.lang)
        assertEquals("Blitzschlag", entry.printedName)
    }

    @Test
    fun anzahlNullLoescht() = runTest {
        val id = repo.addCopies(boltEn, foil = false, locationId = null)
        repo.saveEntry(id, quantity = 0, foil = false, locationId = null)
        assertTrue(entries().isEmpty())
    }

    @Test
    fun lagerortWechselnFuehrtZusammen() = runTest {
        val box = repo.location(LocationKind.BULK, "Box 1")
        repo.addCopies(boltEn, foil = false, locationId = box.id)
        val loose = repo.addCopies(boltEn, foil = false, locationId = null, count = 2)
        repo.saveEntry(loose, quantity = 2, foil = false, locationId = box.id)
        val entry = entries().single()
        assertEquals(3, entry.quantity)
        assertEquals(box.id, entry.locationId)
    }

    private fun card(id: String, lang: String, printedName: String? = null) = ScryfallCard(
        id = id, oracleId = "bolt", name = "Lightning Bolt", printedName = printedName,
        setCode = "m11", setName = "Magic 2011", collectorNumber = "149", lang = lang, imageUrl = null,
    )
}
