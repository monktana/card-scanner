package app.cardscanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CollectionExportTest {

    private val vivi = StorageLocation(1, LocationKind.DECK, "vivi-ornitier")
    private val box = StorageLocation(2, LocationKind.BULK, "Box 1")
    private val reforged = StorageLocation(3, LocationKind.DECK, "multiverse-reforged")

    private var nextId = 1L

    private fun entry(
        name: String, set: String, nr: String, location: StorageLocation?, quantity: Int = 1,
        lang: String = "en", foil: Boolean = false, printedName: String? = null,
    ) = EntryWithLocation(
        CollectionEntry(
            id = nextId++, scryfallId = "id-$name-$lang-$foil", oracleId = "o-$name", name = name,
            printedName = printedName, setCode = set, setName = "Set $set", collectorNumber = nr, lang = lang,
            imageUrl = "https://cards.scryfall.io/$nr.jpg", foil = foil, locationId = location?.id,
            quantity = quantity, addedAt = 1_700_000_000_000, updatedAt = 1_700_000_000_000,
        ),
        location,
    )

    @Test
    fun `Namen-Datei mit Gruppen in fester Reihenfolge`() {
        val text = CollectionExport.namesFile(
            listOf(
                entry("Sol Ring", "c21", "263", box),
                entry("Lightning Bolt", "m11", "149", null),
                entry("Vivi Ornitier", "fin", "248", vivi),
                entry("Counterspell", "fdc", "61", vivi, quantity = 2),
                entry("Rhystic Study", "pcy", "45", reforged, lang = "de", printedName = "Rhystisches Studium"),
            ),
            LocalDate.of(2026, 11, 23),
        )
        val expected = """
            # Sammlung (card-scanner), exportiert am 2026-11-23
            # 6 Karten. Foil und Sprache stehen in sammlung.csv.

            #! Deck: multiverse-reforged
            1 Rhystic Study (PCY) 45

            #! Deck: vivi-ornitier
            2 Counterspell (FDC) 61
            1 Vivi Ornitier (FIN) 248

            #! Bulk: Box 1
            1 Sol Ring (C21) 263

            #! Ohne Lagerort
            1 Lightning Bolt (M11) 149

        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `gleiche Druckversion in DE, EN und Foil wird eine Zeile`() {
        val text = CollectionExport.namesFile(
            listOf(
                entry("Farewell", "neo", "13", box, lang = "de"),
                entry("Farewell", "neo", "13", box, lang = "en", quantity = 2),
                entry("Farewell", "neo", "13", box, foil = true),
            ),
            LocalDate.of(2026, 11, 23),
        )
        assertTrue(text.contains("\n4 Farewell (NEO) 13\n"))
    }

    @Test
    fun `doppelseitige Karte behaelt den vollen Namen`() {
        val text = CollectionExport.namesFile(
            listOf(entry("Lake-town Mariners // Gone Fishing", "hob", "44", null)),
            LocalDate.of(2026, 11, 23),
        )
        assertTrue(text.contains("\n1 Lake-town Mariners // Gone Fishing (HOB) 44\n"))
    }

    @Test
    fun `CSV hin und zurueck, auch mit Komma, Semikolon und Anfuehrungszeichen`() {
        val entries = listOf(
            entry("Jace, Multiverse Architect", "frc", "1", vivi, lang = "de", printedName = "Jace, Architekt des Multiversums"),
            entry("Strange; \"Quoted\" Card", "unf", "7a", box, quantity = 3, foil = true),
            entry("Island", "fra", "283", null),
        )
        val rows = CollectionExport.parseCsv(CollectionExport.csv(entries))
        assertEquals(3, rows.size)

        val jace = rows.single { it.name.startsWith("Jace") }
        assertEquals("Jace, Architekt des Multiversums", jace.printedName)
        assertEquals("de", jace.lang)
        assertEquals(LocationKind.DECK, jace.locationKind)
        assertEquals("vivi-ornitier", jace.locationName)

        val strange = rows.single { it.name.startsWith("Strange") }
        assertEquals("Strange; \"Quoted\" Card", strange.name)
        assertEquals("7a", strange.collectorNumber)
        assertEquals(3, strange.quantity)
        assertTrue(strange.foil)

        val island = rows.single { it.name == "Island" }
        assertEquals(null, island.locationKind)
        assertEquals(null, island.locationName)
        assertEquals("https://cards.scryfall.io/283.jpg", island.imageUrl)
    }

    @Test
    fun `CSV aus Excel mit BOM und CRLF wird gelesen`() {
        val csv = "﻿" + CollectionExport.csv(listOf(entry("Island", "fra", "283", box))).replace("\n", "\r\n")
        assertEquals("Island", CollectionExport.parseCsv(csv).single().name)
    }

    @Test
    fun `fehlerhafte CSV meldet Zeile und Grund`() {
        val header = CollectionExport.CSV_COLUMNS.joinToString(";")
        val badCount = "$header\nid;o;Island;;fra;Set;283;en;nein;null;Bulk;Box 1;;\n"
        val e1 = assertThrows(CollectionExport.ImportException::class.java) { CollectionExport.parseCsv(badCount) }
        assertTrue(e1.message!!.contains("Zeile 2"))

        val badKind = "$header\nid;o;Island;;fra;Set;283;en;nein;1;Kiste;Box 1;;\n"
        assertThrows(CollectionExport.ImportException::class.java) { CollectionExport.parseCsv(badKind) }

        assertThrows(CollectionExport.ImportException::class.java) { CollectionExport.parseCsv("name;anzahl\nIsland;1\n") }
    }
}
