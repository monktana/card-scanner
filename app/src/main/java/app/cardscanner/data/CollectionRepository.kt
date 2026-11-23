package app.cardscanner.data

import androidx.room.withTransaction
import app.cardscanner.ScryfallCard
import kotlinx.coroutines.flow.Flow

/**
 * Schreibzugriffe auf die Sammlung. Gleiche Einträge (Druckversion + Foil + Lagerort) werden
 * zusammengeführt, statt doppelt angelegt.
 */
class CollectionRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.collectionDao()

    val entries: Flow<List<EntryWithLocation>> = dao.observeEntries()
    val locations: Flow<List<StorageLocation>> = dao.observeLocations()

    /** Fügt Exemplare hinzu; gibt es den Eintrag schon, steigt nur die Anzahl. */
    suspend fun addCopies(card: ScryfallCard, foil: Boolean, locationId: Long?, count: Int = 1): Long {
        require(count > 0)
        return db.withTransaction {
            val now = clock()
            val same = dao.findSame(card.id, foil, locationId)
            if (same != null) {
                dao.update(same.copy(quantity = same.quantity + count, updatedAt = now))
                same.id
            } else {
                dao.insert(card.toEntry(foil, locationId, count, now))
            }
        }
    }

    /**
     * Speichert Änderungen an einem Eintrag. Anzahl 0 löscht ihn. Passt er danach zu einem
     * anderen Eintrag (z. B. Foil umgeschaltet), werden beide zusammengeführt.
     *
     * @param card neue Druckversion, wenn die Sprache geändert wurde, sonst null
     */
    suspend fun saveEntry(
        entryId: Long,
        quantity: Int,
        foil: Boolean,
        locationId: Long?,
        card: ScryfallCard? = null,
    ) = db.withTransaction {
        val original = dao.entry(entryId) ?: return@withTransaction
        if (quantity <= 0) {
            dao.delete(original)
            return@withTransaction
        }
        val now = clock()
        val updated = (card?.let { original.withCard(it) } ?: original)
            .copy(quantity = quantity, foil = foil, locationId = locationId, updatedAt = now)
        val other = dao.findSame(updated.scryfallId, updated.foil, updated.locationId)
            ?.takeIf { it.id != original.id }
        if (other != null) {
            dao.update(other.copy(quantity = other.quantity + quantity, updatedAt = now))
            dao.delete(original)
        } else {
            dao.update(updated)
        }
    }

    fun observeEntries(ids: Collection<Long>): Flow<List<CollectionEntry>> = dao.observeEntries(ids.toList())

    /** Anzahl je Eintrag in einem Lagerort, z. B. als Stand vor „Deck ersetzen“. */
    suspend fun quantitiesIn(locationId: Long): Map<Long, Int> =
        dao.entriesIn(locationId).associate { it.id to it.quantity }

    /**
     * Verschiebt [count] Exemplare eines Eintrags in einen anderen (Foil, Lagerort). Der alte
     * Eintrag wird gelöscht, wenn nichts übrig bleibt. Gibt die ID des Ziel-Eintrags zurück.
     */
    suspend fun moveCopies(entryId: Long, count: Int, foil: Boolean, locationId: Long?): Long? =
        db.withTransaction {
            val source = dao.entry(entryId) ?: return@withTransaction null
            val moved = count.coerceIn(0, source.quantity)
            if (moved == 0) return@withTransaction null
            if (source.foil == foil && source.locationId == locationId) return@withTransaction source.id
            val now = clock()
            removeFrom(source, moved, now)
            val target = dao.findSame(source.scryfallId, foil, locationId)
            if (target != null) {
                dao.update(target.copy(quantity = target.quantity + moved, updatedAt = now))
                target.id
            } else {
                dao.insert(source.copy(id = 0, foil = foil, locationId = locationId, quantity = moved, addedAt = now, updatedAt = now))
            }
        }

    suspend fun addToEntry(entryId: Long, count: Int) = db.withTransaction {
        dao.entry(entryId)?.let { dao.update(it.copy(quantity = it.quantity + count, updatedAt = clock())) }
    }

    /** Nimmt Exemplare weg, z. B. einen verworfenen Scan. Bei 0 wird der Eintrag gelöscht. */
    suspend fun removeCopies(entryId: Long, count: Int) = db.withTransaction {
        dao.entry(entryId)?.let { removeFrom(it, count.coerceIn(0, it.quantity), clock()) }
    }

    /**
     * Schließt „Deck ersetzen“ ab. Im Deck bleibt je Eintrag genau, was gescannt wurde; was
     * vorher im Deck war und nicht mehr gescannt wurde, wandert nach Bulk „Aus Decks“.
     * Einträge, die weder vorher da waren noch gescannt wurden (z. B. nebenbei von Hand
     * hinzugefügt), bleiben unverändert.
     *
     * @param before Anzahl je Eintrag beim Start der Sitzung
     * @param scanned in der Sitzung gescannte Exemplare je Eintrag
     */
    suspend fun finishDeckReplace(locationId: Long, before: Map<Long, Int>, scanned: Map<Long, Int>) =
        db.withTransaction {
            val now = clock()
            var removedBulk: StorageLocation? = null
            for (entry in dao.entriesIn(locationId)) {
                val old = before[entry.id] ?: 0
                if (old == 0 && entry.id !in scanned) continue
                val new = scanned[entry.id] ?: 0
                val removed = (old - new).coerceAtLeast(0)
                if (new == 0) dao.delete(entry) else dao.update(entry.copy(quantity = new, updatedAt = now))
                if (removed > 0) {
                    val bulkId = (removedBulk ?: location(LocationKind.BULK, FROM_DECKS).also { removedBulk = it }).id
                    val target = dao.findSame(entry.scryfallId, entry.foil, bulkId)
                    if (target != null) {
                        dao.update(target.copy(quantity = target.quantity + removed, updatedAt = now))
                    } else {
                        dao.insert(entry.copy(id = 0, locationId = bulkId, quantity = removed, addedAt = now, updatedAt = now))
                    }
                }
            }
        }

    private suspend fun removeFrom(entry: CollectionEntry, count: Int, now: Long) {
        val left = entry.quantity - count
        if (left <= 0) dao.delete(entry) else dao.update(entry.copy(quantity = left, updatedAt = now))
    }

    suspend fun entryCount(): Int = dao.allEntries().size

    /**
     * Übernimmt eine importierte CSV. [replace] löscht vorher alle Einträge (z. B. Wiederherstellen
     * nach Neuinstallation); sonst werden gleiche Einträge zusammengeführt. Alles oder nichts.
     * Gibt die Zahl der importierten Karten zurück.
     */
    suspend fun import(rows: List<CollectionExport.ImportRow>, replace: Boolean): Int = db.withTransaction {
        if (replace) dao.deleteAllEntries()
        val now = clock()
        val locations = mutableMapOf<Pair<LocationKind, String>, Long>()
        for (row in rows) {
            val locationId = if (row.locationKind != null && row.locationName != null) {
                locations.getOrPut(row.locationKind to row.locationName) { location(row.locationKind, row.locationName).id }
            } else {
                null
            }
            val same = dao.findSame(row.scryfallId, row.foil, locationId)
            if (same != null) {
                dao.update(same.copy(quantity = same.quantity + row.quantity, updatedAt = now))
            } else {
                dao.insert(
                    CollectionEntry(
                        scryfallId = row.scryfallId, oracleId = row.oracleId, name = row.name,
                        printedName = row.printedName, setCode = row.setCode, setName = row.setName,
                        collectorNumber = row.collectorNumber, lang = row.lang, imageUrl = row.imageUrl,
                        foil = row.foil, locationId = locationId, quantity = row.quantity,
                        addedAt = now, updatedAt = now,
                    ),
                )
            }
        }
        rows.sumOf { it.quantity }
    }

    /** Löscht mehrere Einträge vollständig (alle Exemplare). SQLite erlaubt nur begrenzt viele Parameter, daher in Blöcken. */
    suspend fun delete(entryIds: Collection<Long>) = db.withTransaction {
        entryIds.chunked(500).forEach { dao.deleteEntries(it) }
    }

    suspend fun delete(entryId: Long) = db.withTransaction {
        dao.entry(entryId)?.let { dao.delete(it) }
    }

    /** Lagerort zu Art und Name; wird bei Bedarf angelegt. */
    suspend fun location(kind: LocationKind, name: String): StorageLocation {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty())
        return db.withTransaction {
            dao.findLocation(kind, trimmed)
                ?: StorageLocation(kind = kind, name = trimmed).let { it.copy(id = dao.insertLocation(it)) }
        }
    }

    companion object {
        /** Bulk-Lagerort für Karten, die beim Neuscannen aus einem Deck gefallen sind. */
        const val FROM_DECKS = "Aus Decks"
    }

    private fun ScryfallCard.toEntry(foil: Boolean, locationId: Long?, quantity: Int, now: Long) =
        CollectionEntry(
            scryfallId = id, oracleId = oracleId, name = name, printedName = printedName,
            setCode = setCode, setName = setName, collectorNumber = collectorNumber, lang = lang,
            imageUrl = imageUrl, foil = foil, locationId = locationId, quantity = quantity,
            addedAt = now, updatedAt = now,
        )

    private fun CollectionEntry.withCard(card: ScryfallCard) = copy(
        scryfallId = card.id, oracleId = card.oracleId, name = card.name, printedName = card.printedName,
        setCode = card.setCode, setName = card.setName, collectorNumber = card.collectorNumber,
        lang = card.lang, imageUrl = card.imageUrl,
    )
}
