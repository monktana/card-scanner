package app.cardscanner

import java.io.File
import java.io.IOException

/** Bekannte Set-Codes von Scryfall. Prüft gelesene Set-Codes und korrigiert 0/O-Verwechslungen. */
class SetCatalog(codes: Collection<String>) {

    private val codes = codes.map { it.uppercase() }.toHashSet()

    val size get() = codes.size

    /** Gibt den bekannten Set-Code zurück ("M2O" → "M20") oder null, wenn es kein Set ist. */
    fun resolve(raw: String): String? =
        listOf(raw, raw.replace('O', '0'), raw.replace('0', 'O')).firstOrNull { it in codes }
}

/**
 * Lädt die Setliste und hält sie als Datei vor. Ist sie älter als [MAX_AGE_MS], wird sie neu
 * geladen; klappt das nicht (offline), bleibt die alte Datei in Gebrauch.
 */
class SetCatalogLoader(private val file: File, private val scryfall: ScryfallClient) {

    suspend fun load(): SetCatalog? {
        val cached = readFile()
        if (cached != null && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return cached
        return try {
            val codes = scryfall.fetchSetCodes()
            file.parentFile?.mkdirs()
            file.writeText(codes.joinToString("\n"))
            SetCatalog(codes)
        } catch (e: IOException) {
            cached
        }
    }

    private fun readFile(): SetCatalog? =
        file.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.let(::SetCatalog)

    private companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
