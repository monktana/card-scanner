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

    @Test
    fun foilUmschaltenVerschiebtNurDieGescanntenExemplare() = runTest {
        val id = repo.addCopies(boltEn, foil = false, locationId = null, count = 3)
        val foilId = repo.moveCopies(id, count = 1, foil = true, locationId = null)!!
        val all = entries().associateBy { it.id }
        assertEquals(2, all.getValue(id).quantity)
        assertEquals(1, all.getValue(foilId).quantity)
        assertTrue(all.getValue(foilId).foil)
    }

    @Test
    fun verwerfenNimmtExemplareWeg() = runTest {
        val id = repo.addCopies(boltEn, foil = false, locationId = null, count = 2)
        repo.removeCopies(id, 1)
        assertEquals(1, entries().single().quantity)
        repo.removeCopies(id, 1)
        assertTrue(entries().isEmpty())
    }

    // Deck ersetzen: Vorher Sol Ring + Bolt im Deck, neu gescannt Sol Ring + Counterspell.
    @Test
    fun deckErsetzenBehaeltGescanntesUndVerschiebtDenRest() = runTest {
        val deck = repo.location(LocationKind.DECK, "vivi-ornitier")
        val solRing = card("sol-ring", "en", name = "Sol Ring")
        val counter = card("counterspell", "en", name = "Counterspell")
        val solId = repo.addCopies(solRing, foil = false, locationId = deck.id)
        val boltId = repo.addCopies(boltEn, foil = false, locationId = deck.id)
        val before = repo.quantitiesIn(deck.id)

        // Sitzung: Sol Ring landet im selben Eintrag (Anzahl kurz 2), Counterspell ist neu.
        repo.addCopies(solRing, foil = false, locationId = deck.id)
        val counterId = repo.addCopies(counter, foil = false, locationId = deck.id)
        repo.finishDeckReplace(deck.id, before, scanned = mapOf(solId to 1, counterId to 1))

        val all = repo.entries.first()
        val inDeck = all.filter { it.location?.id == deck.id }.associate { it.entry.name to it.entry.quantity }
        val removed = all.filter { it.location?.name == CollectionRepository.FROM_DECKS }.map { it.entry.name to it.entry.quantity }
        assertEquals(mapOf("Sol Ring" to 1, "Counterspell" to 1), inDeck)
        assertEquals(listOf("Lightning Bolt" to 1), removed)
        assertTrue(all.none { it.entry.id == boltId })
    }

    @Test
    fun deckErsetzenLaesstNebenbeiHinzugefuegtesInRuhe() = runTest {
        val deck = repo.location(LocationKind.DECK, "zwerge")
        val before = repo.quantitiesIn(deck.id)
        repo.addCopies(boltEn, foil = false, locationId = deck.id)
        repo.finishDeckReplace(deck.id, before, scanned = emptyMap())
        assertEquals(1, entries().single().quantity)
    }

    @Test
    fun exportUndImportStellenDieSammlungWiederHer() = runTest {
        val deck = repo.location(LocationKind.DECK, "vivi-ornitier")
        repo.addCopies(boltDe, foil = true, locationId = deck.id, count = 2)
        repo.addCopies(boltEn, foil = false, locationId = null)
        val csv = CollectionExport.csv(repo.entries.first())

        // Neue, leere Datenbank wie nach einer Neuinstallation.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val restored = CollectionRepository(fresh) { 2000L }
            assertEquals(3, restored.import(CollectionExport.parseCsv(csv), replace = true))
            val all = restored.entries.first()
            val inDeck = all.single { it.location != null }
            assertEquals("vivi-ornitier", inDeck.location!!.name)
            assertEquals(LocationKind.DECK, inDeck.location!!.kind)
            assertEquals(2, inDeck.entry.quantity)
            assertTrue(inDeck.entry.foil)
            assertEquals("Blitzschlag", inDeck.entry.printedName)
            assertEquals(1, all.single { it.location == null }.entry.quantity)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun lagerortUmbenennenUndArtAendern() = runTest {
        val box = repo.location(LocationKind.BULK, "Box1")
        repo.addCopies(boltEn, foil = false, locationId = box.id)
        assertEquals(CollectionRepository.RenameResult.Done, repo.renameLocation(box.id, LocationKind.DECK, " zwerge "))
        val stats = repo.locationStats.first().single()
        assertEquals(StorageLocation(box.id, LocationKind.DECK, "zwerge"), stats.location)
        assertEquals(1, stats.cardCount)
    }

    @Test
    fun umbenennenAufVorhandenenNamenFuehrtNachRueckfrageZusammen() = runTest {
        val typo = repo.location(LocationKind.BULK, "Box1")
        val box = repo.location(LocationKind.BULK, "Box 1")
        repo.addCopies(boltEn, foil = false, locationId = typo.id, count = 2)
        repo.addCopies(boltEn, foil = false, locationId = box.id)
        repo.addCopies(boltDe, foil = false, locationId = typo.id)

        val conflict = repo.renameLocation(typo.id, LocationKind.BULK, "Box 1")
        assertEquals(CollectionRepository.RenameResult.Conflict(box), conflict)
        assertEquals(2, repo.locationStats.first().size) // ohne merge passiert nichts

        repo.renameLocation(typo.id, LocationKind.BULK, "Box 1", merge = true)
        val stats = repo.locationStats.first().single()
        assertEquals(box.id, stats.location.id)
        assertEquals(2, stats.entryCount)
        assertEquals(4, stats.cardCount)
    }

    @Test
    fun lagerortLoeschenVerschiebtKarten() = runTest {
        val deck = repo.location(LocationKind.DECK, "alt")
        val box = repo.location(LocationKind.BULK, "Box 1")
        repo.addCopies(boltEn, foil = false, locationId = deck.id, count = 2)
        repo.addCopies(boltEn, foil = false, locationId = box.id)
        repo.addCopies(boltDe, foil = true, locationId = deck.id)

        repo.deleteLocation(deck.id, targetLocationId = box.id)
        val stats = repo.locationStats.first().single()
        assertEquals(box.id, stats.location.id)
        assertEquals(2, stats.entryCount)
        assertEquals(4, stats.cardCount)

        repo.deleteLocation(box.id, targetLocationId = null)
        assertTrue(repo.locationStats.first().isEmpty())
        assertEquals(4, entries().sumOf { it.quantity })
        assertTrue(entries().all { it.locationId == null })
    }

    @Test
    fun mehrereEintraegeLoeschen() = runTest {
        val a = repo.addCopies(boltEn, foil = false, locationId = null, count = 3)
        val b = repo.addCopies(boltEn, foil = true, locationId = null)
        val keep = repo.addCopies(boltDe, foil = false, locationId = null)
        repo.delete(listOf(a, b))
        assertEquals(listOf(keep), entries().map { it.id })
    }

    @Test
    fun importErgaenztOderErsetzt() = runTest {
        repo.addCopies(boltEn, foil = false, locationId = null)
        val rows = CollectionExport.parseCsv(CollectionExport.csv(repo.entries.first()))
        repo.import(rows, replace = false)
        assertEquals(2, entries().single().quantity)
        repo.import(rows, replace = true)
        assertEquals(1, entries().single().quantity)
    }

    private fun card(id: String, lang: String, printedName: String? = null, name: String = "Lightning Bolt") = ScryfallCard(
        id = id, oracleId = id, name = name, printedName = printedName,
        setCode = "m11", setName = "Magic 2011", collectorNumber = "149", lang = lang, imageUrl = null,
    )
}
