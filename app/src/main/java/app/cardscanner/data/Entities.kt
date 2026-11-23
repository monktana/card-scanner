package app.cardscanner.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class LocationKind(val label: String) {
    DECK("Deck"),
    BULK("Bulk"),
}

/** Wo Exemplare physisch liegen: ein gebautes Deck oder eine Bulk-Box. */
@Entity(
    tableName = "locations",
    indices = [Index(value = ["kind", "name"], unique = true)],
)
data class StorageLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: LocationKind,
    val name: String,
) {
    val label get() = "${kind.label}: $name"
}

/**
 * Ein Eintrag der Sammlung: Druckversion + Foil + Lagerort, dazu die Anzahl.
 * Die Kartendaten werden beim Hinzufügen mitgespeichert, damit Liste und Export offline gehen.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = StorageLocation::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("locationId"), Index(value = ["scryfallId", "foil", "locationId"])],
)
data class CollectionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scryfallId: String,
    val oracleId: String?,
    val name: String,
    val printedName: String?,
    val setCode: String,
    val setName: String,
    val collectorNumber: String,
    val lang: String,
    val imageUrl: String?,
    val foil: Boolean,
    val locationId: Long?,
    val quantity: Int,
    val addedAt: Long,
    val updatedAt: Long,
)

data class EntryWithLocation(
    @Embedded val entry: CollectionEntry,
    @Relation(parentColumn = "locationId", entityColumn = "id")
    val location: StorageLocation?,
)
