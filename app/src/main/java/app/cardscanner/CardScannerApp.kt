package app.cardscanner

import android.app.Application
import app.cardscanner.data.AppDatabase
import app.cardscanner.data.CollectionRepository

/** Hält die app-weiten Objekte (Datenbank, Scryfall), damit es sie nur einmal gibt. */
class CardScannerApp : Application() {
    val database by lazy { AppDatabase.open(this) }
    val repository by lazy { CollectionRepository(database) }
    val scryfall by lazy { ScryfallClient() }
}
