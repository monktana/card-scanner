package app.cardscanner

import android.app.Application
import app.cardscanner.data.AppDatabase
import app.cardscanner.data.CollectionRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/** Hält die app-weiten Objekte (Datenbank, Scryfall, Bildlader), damit es sie nur einmal gibt. */
class CardScannerApp : Application(), SingletonImageLoader.Factory {
    val database by lazy { AppDatabase.open(this) }
    val repository by lazy { CollectionRepository(database) }
    val scryfall by lazy { ScryfallClient() }

    /**
     * Bildlader für die Kartenbilder. Ohne eigenen User-Agent schickt OkHttp „okhttp/…“, und
     * cards.scryfall.io antwortet mit 400 statt dem Bild.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", ScryfallClient.USER_AGENT).build())
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
