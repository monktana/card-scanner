package app.cardscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StorageLocation::class, CollectionEntry::class], version = 1)
abstract class AppDatabase : RoomDatabase() {

    abstract fun collectionDao(): CollectionDao

    companion object {
        // Die Sammlung existiert nur hier. Schemaänderungen brauchen eine Migration,
        // nie fallbackToDestructiveMigration.
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sammlung.db").build()
    }
}
