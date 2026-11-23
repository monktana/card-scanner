package app.cardscanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Transaction
    @Query("SELECT * FROM entries ORDER BY name COLLATE NOCASE, setCode, collectorNumber")
    fun observeEntries(): Flow<List<EntryWithLocation>>

    @Query("SELECT * FROM locations ORDER BY kind, name COLLATE NOCASE")
    fun observeLocations(): Flow<List<StorageLocation>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: Long): CollectionEntry?

    // "IS" statt "=", damit auch "ohne Lagerort" (NULL) passt.
    @Query("SELECT * FROM entries WHERE scryfallId = :scryfallId AND foil = :foil AND locationId IS :locationId LIMIT 1")
    suspend fun findSame(scryfallId: String, foil: Boolean, locationId: Long?): CollectionEntry?

    @Insert
    suspend fun insert(entry: CollectionEntry): Long

    @Update
    suspend fun update(entry: CollectionEntry)

    @Delete
    suspend fun delete(entry: CollectionEntry)

    @Query("SELECT * FROM locations WHERE kind = :kind AND name = :name")
    suspend fun findLocation(kind: LocationKind, name: String): StorageLocation?

    @Insert
    suspend fun insertLocation(location: StorageLocation): Long
}
