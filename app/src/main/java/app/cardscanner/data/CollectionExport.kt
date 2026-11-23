package app.cardscanner.data

import java.time.Instant
import java.time.LocalDate

/**
 * Export und Import der Sammlung. Reines Kotlin ohne Android, damit es mit Unit-Tests geprüft
 * werden kann.
 *
 * - `sammlung.txt` ist eine Namen-Datei für `scripts/scryfall/kartentexte.py` und Claude Desktop:
 *   `1 Lightning Bolt (M11) 149`, gruppiert mit `#! Deck: name` / `#! Bulk: name` / `#! Ohne Lagerort`.
 *   Das Skript schneidet `(SET) Nr` am Zeilenende ab; eine Foil-Markierung dahinter würde das
 *   verhindern. Foil und Sprache stehen deshalb nur in der CSV.
 * - `sammlung.csv` enthält alle Felder und ist wieder importierbar (Trenner `;`).
 */
object CollectionExport {

    const val NO_LOCATION = "Ohne Lagerort"

    val CSV_COLUMNS = listOf(
        "scryfall_id", "oracle_id", "name", "druckname", "set", "set_name", "nr", "sprache",
        "foil", "anzahl", "art", "lagerort", "bild_url", "hinzugefuegt",
    )

    fun namesFile(entries: List<EntryWithLocation>, date: LocalDate): String = buildString {
        appendLine("# Sammlung (card-scanner), exportiert am $date")
        appendLine("# ${entries.sumOf { it.entry.quantity }} Karten. Foil und Sprache stehen in sammlung.csv.")
        for ((group, items) in grouped(entries)) {
            appendLine()
            appendLine("#! $group")
            // Gleiche Druckversion (z. B. DE und EN, Foil und nicht Foil) wird eine Zeile.
            items.groupBy { Triple(it.entry.name, it.entry.setCode, it.entry.collectorNumber) }
                .map { (key, list) -> key to list.sumOf { it.entry.quantity } }
                .sortedWith(compareBy({ it.first.first.lowercase() }, { it.first.second }, { it.first.third }))
                .forEach { (key, count) ->
                    appendLine("$count ${key.first} (${key.second.uppercase()}) ${key.third}")
                }
        }
    }

    /** Decks zuerst, dann Bulk, dann ohne Lagerort; jeweils nach Name. */
    private fun grouped(entries: List<EntryWithLocation>): List<Pair<String, List<EntryWithLocation>>> =
        entries.groupBy { it.location }
            .toList()
            .sortedWith(compareBy({ it.first?.kind?.ordinal ?: Int.MAX_VALUE }, { it.first?.name?.lowercase() }))
            .map { (location, items) -> (location?.label ?: NO_LOCATION) to items }

    fun csv(entries: List<EntryWithLocation>): String = buildString {
        appendLine(CSV_COLUMNS.joinToString(";"))
        for (item in entries.sortedWith(compareBy({ it.location?.label ?: "~" }, { it.entry.name.lowercase() }))) {
            val e = item.entry
            val row = listOf(
                e.scryfallId, e.oracleId.orEmpty(), e.name, e.printedName.orEmpty(), e.setCode, e.setName,
                e.collectorNumber, e.lang, if (e.foil) "ja" else "nein", e.quantity.toString(),
                item.location?.kind?.label.orEmpty(), item.location?.name.orEmpty(), e.imageUrl.orEmpty(),
                Instant.ofEpochMilli(e.addedAt).toString(),
            )
            appendLine(row.joinToString(";", transform = ::quote))
        }
    }

    data class ImportRow(
        val scryfallId: String,
        val oracleId: String?,
        val name: String,
        val printedName: String?,
        val setCode: String,
        val setName: String,
        val collectorNumber: String,
        val lang: String,
        val foil: Boolean,
        val quantity: Int,
        val locationKind: LocationKind?,
        val locationName: String?,
        val imageUrl: String?,
    )

    class ImportException(message: String) : Exception(message)

    /** Liest eine mit [csv] geschriebene Datei. Spalten werden über die Kopfzeile gefunden. */
    fun parseCsv(text: String): List<ImportRow> {
        val rows = parseRows(text.removePrefix("﻿"))
        if (rows.isEmpty()) throw ImportException("Die Datei ist leer.")
        val header = rows.first().map { it.trim() }
        val missing = CSV_COLUMNS.filter { it !in header && it != "hinzugefuegt" && it != "oracle_id" && it != "bild_url" }
        if (missing.isNotEmpty()) throw ImportException("Spalten fehlen: ${missing.joinToString()}")
        val col = header.withIndex().associate { (i, name) -> name to i }

        return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.mapIndexed { i, r ->
            fun get(name: String) = col[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
            val line = i + 2
            val quantity = get("anzahl").toIntOrNull()?.takeIf { it > 0 }
                ?: throw ImportException("Zeile $line: Anzahl „${get("anzahl")}“ ist keine positive Zahl.")
            val kind = when (get("art")) {
                "" -> null
                LocationKind.DECK.label -> LocationKind.DECK
                LocationKind.BULK.label -> LocationKind.BULK
                else -> throw ImportException("Zeile $line: Art „${get("art")}“ ist weder Deck noch Bulk.")
            }
            if (get("scryfall_id").isEmpty()) throw ImportException("Zeile $line: scryfall_id fehlt.")
            ImportRow(
                scryfallId = get("scryfall_id"),
                oracleId = get("oracle_id").ifEmpty { null },
                name = get("name"),
                printedName = get("druckname").ifEmpty { null },
                setCode = get("set"),
                setName = get("set_name"),
                collectorNumber = get("nr"),
                lang = get("sprache"),
                foil = get("foil") == "ja",
                quantity = quantity,
                locationKind = kind,
                locationName = get("lagerort").ifEmpty { null }.takeIf { kind != null },
                imageUrl = get("bild_url").ifEmpty { null },
            )
        }
    }

    private fun quote(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** CSV mit `;`, Felder in Anführungszeichen dürfen `;`, `"` (verdoppelt) und Zeilenumbrüche enthalten. */
    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> quoted = true
                c == ';' -> { row.add(field.toString()); field.clear() }
                c == '\r' -> {}
                c == '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
