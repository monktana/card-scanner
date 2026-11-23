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
