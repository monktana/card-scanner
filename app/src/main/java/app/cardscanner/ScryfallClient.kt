package app.cardscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Eine Druckversion in einer Sprache, wie Scryfall sie liefert. */
data class ScryfallCard(
    val id: String,
    val oracleId: String?,
    /** Englischer Oracle-Name, bei doppelseitigen Karten "A // B". */
    val name: String,
    /** Gedruckter Name bei nicht-englischen Karten, sonst null. */
    val printedName: String?,
    val setCode: String,
    val setName: String,
    val collectorNumber: String,
    val lang: String,
    val imageUrl: String?,
)

sealed interface LookupResult {
    data class Found(val card: ScryfallCard) : LookupResult
    data object NotFound : LookupResult
    data class Failed(val message: String) : LookupResult
}

/** Scryfall-Abfrage mit Cache im Speicher und Mindestabstand zwischen Anfragen. */
class ScryfallClient {

    private val cache = ConcurrentHashMap<String, Optional>()
    private val rateLimit = Mutex()
    private var lastRequestAt = 0L

    private class Optional(val card: ScryfallCard?)

    suspend fun find(parsed: ParsedCollectorLine): LookupResult = withContext(Dispatchers.IO) {
        try {
            val lang = parsed.language?.let(::scryfallLang)
            for (set in setCandidates(parsed.setCode)) {
                if (lang != null && lang != "en") {
                    get("cards/$set/${parsed.number}/$lang")?.let { return@withContext LookupResult.Found(it) }
                }
                get("cards/$set/${parsed.number}")?.let { return@withContext LookupResult.Found(it) }
            }
            LookupResult.NotFound
        } catch (e: IOException) {
            LookupResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Dieselbe Druckversion in einer anderen Sprache, null wenn Scryfall sie nicht kennt. */
    suspend fun inLanguage(setCode: String, collectorNumber: String, lang: String): ScryfallCard? =
        withContext(Dispatchers.IO) { get("cards/${setCode.lowercase()}/${encode(collectorNumber)}/$lang") }

    /**
     * Karten zu einem Namen, englisch oder in einer anderen Sprache gedruckt ("Blitzschlag").
     * Eine Karte je Oracle-Text.
     */
    suspend fun searchCards(text: String): List<ScryfallCard> = withContext(Dispatchers.IO) {
        search("$text lang:any", unique = "cards")
    }

    /** Alle deutschen und englischen Druckversionen einer Karte, die neuesten zuerst. */
    suspend fun prints(oracleId: String): List<ScryfallCard> = withContext(Dispatchers.IO) {
        search("oracleid:$oracleId (lang:en or lang:de)", unique = "prints")
    }

    /** Alle Set-Codes (klein geschrieben, wie Scryfall sie führt). */
    suspend fun fetchSetCodes(): List<String> = withContext(Dispatchers.IO) {
        val json = request("sets") ?: throw IOException("Scryfall /sets nicht gefunden")
        val data = json.getJSONArray("data")
        List(data.length()) { data.getJSONObject(it).getString("code") }
    }

    /** Erste Ergebnisseite (bis 175 Karten); 404 heißt bei Scryfall „keine Treffer“. */
    private suspend fun search(query: String, unique: String): List<ScryfallCard> {
        val json = request("cards/search?order=released&unique=$unique&q=${encode(query)}") ?: return emptyList()
        val data = json.getJSONArray("data")
        return List(data.length()) { parseCard(data.getJSONObject(it)) }
    }

    /** OCR verwechselt 0 und O; Set-Codes wie "M20" oder "OTJ" brauchen beide Varianten. */
    private fun setCandidates(raw: String): List<String> =
        listOf(raw, raw.replace('O', '0'), raw.replace('0', 'O'))
            .map { it.lowercase() }
            .distinct()

    private fun scryfallLang(printed: String): String = when (printed) {
        "JP" -> "ja"
        else -> printed.lowercase()
    }

    private suspend fun get(path: String): ScryfallCard? {
        cache[path]?.let { return it.card }
        val card = request(path)?.let(::parseCard)
        cache[path] = Optional(card)
        return card
    }

    /** GET mit Mindestabstand; null bei 404. */
    private suspend fun request(path: String): JSONObject? {
        rateLimit.withLock {
            val wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val connection = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            return when (val code = connection.responseCode) {
                200 -> JSONObject(connection.inputStream.bufferedReader().readText())
                404 -> null
                else -> throw IOException("Scryfall HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCard(json: JSONObject): ScryfallCard {
        val faces = json.optJSONArray("card_faces")
        val firstFace = faces?.optJSONObject(0)
        val image = json.optJSONObject("image_uris") ?: firstFace?.optJSONObject("image_uris")
        // Doppelseitige Karten haben den gedruckten Namen nur je Seite.
        val printedName = json.optString("printed_name").ifEmpty { null }
            ?: faces?.let { f ->
                List(f.length()) { f.getJSONObject(it).optString("printed_name") }
                    .takeIf { names -> names.all { it.isNotEmpty() } }
                    ?.joinToString(" // ")
            }
        return ScryfallCard(
            id = json.getString("id"),
            oracleId = json.optString("oracle_id").ifEmpty { null }
                ?: firstFace?.optString("oracle_id")?.ifEmpty { null },
            name = json.getString("name"),
            printedName = printedName,
            setCode = json.getString("set"),
            setName = json.getString("set_name"),
            collectorNumber = json.getString("collector_number"),
            lang = json.getString("lang"),
            imageUrl = image?.optString("normal")?.ifEmpty { null },
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val BASE = "https://api.scryfall.com"
        const val USER_AGENT = "CardScanner/0.2 (private collection scanner)"
        const val MIN_INTERVAL_MS = 120L
    }
}
