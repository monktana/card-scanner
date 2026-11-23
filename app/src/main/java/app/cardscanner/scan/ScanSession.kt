package app.cardscanner.scan

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Eine laufende Scan-Sitzung. Wird nach jeder Änderung gespeichert, damit sie einen Absturz
 * oder das Beenden der App übersteht.
 */
data class ScanSession(
    val locationId: Long?,
    val locationLabel: String,
    val foil: Boolean,
    /** „Deck ersetzen“: Beim Beenden bleibt im Deck nur, was gescannt wurde. */
    val replaceDeck: Boolean,
    /** Stand des Decks beim Start (nur bei [replaceDeck]). */
    val before: Map<Long, Int> = emptyMap(),
    /** In dieser Sitzung gescannte Exemplare je Eintrag. */
    val scanned: Map<Long, Int> = emptyMap(),
    /** Einträge in der Reihenfolge der letzten Scans, neueste zuerst. */
    val recent: List<Long> = emptyList(),
) {
    val total get() = scanned.values.sum()

    fun withScan(entryId: Long, delta: Int): ScanSession {
        val count = (scanned[entryId] ?: 0) + delta
        return copy(
            scanned = if (count > 0) scanned + (entryId to count) else scanned - entryId,
            recent = if (count > 0) listOf(entryId) + (recent - entryId) else recent - entryId,
        )
    }

    /** Exemplare sind in einen anderen Eintrag gewandert (Foil umgeschaltet). */
    fun moved(from: Long, to: Long): ScanSession {
        val count = scanned[from] ?: return this
        val merged = (scanned - from) + (to to (scanned[to] ?: 0) + count)
        val order = recent.map { if (it == from) to else it }.distinct()
        return copy(scanned = merged, recent = order)
    }

    fun toJson(): String = JSONObject().apply {
        put("locationId", locationId ?: JSONObject.NULL)
        put("locationLabel", locationLabel)
        put("foil", foil)
        put("replaceDeck", replaceDeck)
        put("before", before.toJson())
        put("scanned", scanned.toJson())
        put("recent", JSONArray(recent))
    }.toString()

    companion object {
        fun fromJson(text: String): ScanSession {
            val json = JSONObject(text)
            val recent = json.getJSONArray("recent")
            return ScanSession(
                locationId = if (json.isNull("locationId")) null else json.getLong("locationId"),
                locationLabel = json.getString("locationLabel"),
                foil = json.getBoolean("foil"),
                replaceDeck = json.getBoolean("replaceDeck"),
                before = json.getJSONObject("before").toCounts(),
                scanned = json.getJSONObject("scanned").toCounts(),
                recent = List(recent.length()) { recent.getLong(it) },
            )
        }

        private fun Map<Long, Int>.toJson() = JSONObject().also { o -> forEach { (k, v) -> o.put(k.toString(), v) } }

        private fun JSONObject.toCounts(): Map<Long, Int> = keys().asSequence().associate { it.toLong() to getInt(it) }
    }
}

class SessionStore(private val file: File) {
    fun load(): ScanSession? = try {
        file.takeIf { it.exists() }?.readText()?.let(ScanSession::fromJson)
    } catch (e: Exception) {
        null
    }

    fun save(session: ScanSession?) {
        if (session == null) file.delete() else file.writeText(session.toJson())
    }
}
