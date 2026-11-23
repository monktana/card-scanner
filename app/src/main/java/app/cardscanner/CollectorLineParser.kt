package app.cardscanner

/** Druckversion, wie sie auf der Karte steht: Set-Code + Sammlernummer. */
data class CollectorKey(val setCode: String, val number: String)

data class ParsedCollectorLine(
    val setCode: String,
    val number: String,
    /** Sprachkürzel wie auf der Karte gedruckt (EN, DE, …), null wenn nicht gelesen. */
    val language: String?,
    val rarity: Char?,
) {
    val key get() = CollectorKey(setCode, number)
}

/**
 * Liest Set-Code, Sammlernummer und Sprache aus den OCR-Zeilen einer Karte.
 *
 * Karten ab etwa 2014 tragen unten links zwei Zeilen, z. B.
 *   "149/249 C"   oder (ab 2023) "0149 C" / "C 0149"
 *   "M11 • EN  ✒ Artist"
 * Zuerst wird die Zeile mit Set-Code und Sprache gesucht, dann die Nummer in derselben
 * oder einer benachbarten Zeile. So stören Stärke/Widerstand ("3/3") und Regeltext ("+1/+1") nicht.
 */
object CollectorLineParser {

    private const val LANGS = "EN|DE|FR|IT|ES|PT|JA|JP|KO|RU|ZHS|ZHT|PH"

    // Zwischen Set und Sprache steht immer ein Trenner oder Leerraum. Ohne diese Bedingung
    // würde aus deutschem Text wie "Idioten" das Set "IDIOT" mit Sprache "EN".
    // Gruppe 2 ist der Trenner; fehlt er, stand nur Leerraum dazwischen.
    private val setLang = Regex("""(?<![A-Z0-9])([A-Z0-9]{3,5})(?:\s*([•·∙*.\-+])\s*|\s+)($LANGS)(?![A-Z])""")

    // "149/249" – Gesamtzahl mit 2–4 Stellen, kein Vorzeichen davor (schließt "+1/+1" aus).
    private val numberWithTotal = Regex("""(?<![\d+\-])(\d{1,4})\s*/\s*\d{2,4}(?!\d)""")

    // Neues Format: vierstellig mit führender Null ("0149"), Seltenheit davor oder dahinter,
    // mit oder ohne Leerzeichen ("C0166"). Die OCR liest 0 oft als O ("CO176", "O08O").
    // Die führende Null schließt das Copyright-Jahr ("© 2025", gelesen als "C 2025") aus.
    private val paddedNumber = Regex(
        """(?<![A-Z0-9/])(?:([CURMLSTP])\s*)?([0O](?=[0-9O]*[0-9])[0-9O]{3})(?![0-9A-Z])(?:\s*([CURMLSTP])(?![A-Z0-9]))?""",
    )

    // Seltenheit und Nummer landen manchmal auf getrennten Zeilen, bis zu drei Zeilen weg.
    private const val MAX_LINE_DISTANCE = 3

    private class SetLine(val index: Int, val match: MatchResult, val setCode: String) {
        val hasSeparator get() = match.groups[2] != null
        val language get() = match.groupValues[3]
    }

    /**
     * @param resolveSet prüft einen gelesenen Set-Code gegen die bekannten Sets und gibt den
     *   richtigen Code zurück (z. B. "M2O" → "M20") oder null, wenn es kein Set ist.
     *   Ohne Setliste wird jeder Code angenommen.
     */
    fun parse(lines: List<String>, resolveSet: (String) -> String? = { it }): ParsedCollectorLine? {
        val upper = lines.map { it.uppercase() }

        // Regeltext liefert falsche Kandidaten ("EXILE IT", "WENN ES"). Daher alle sammeln, nur
        // bekannte Sets behalten und die mit Trenner ("TLA • DE") sowie weiter unten zuerst prüfen.
        val setLines = upper.withIndex().flatMap { (index, line) ->
            setLang.findAll(line)
                .filter { m -> m.groupValues[1].any(Char::isLetter) }
                .mapNotNull { m -> resolveSet(m.groupValues[1])?.let { SetLine(index, m, it) } }
                .toList()
        }.sortedWith(compareByDescending<SetLine> { it.hasSeparator }.thenByDescending { it.index })

        for (setLine in setLines) {
            val index = setLine.index
            // Zuerst in derselben Zeile (ohne den Set-Teil), dann in den Nachbarzeilen suchen.
            val sameLine = upper[index].removeRange(setLine.match.range)
            val candidates = buildList {
                add(sameLine)
                for (d in 1..MAX_LINE_DISTANCE) {
                    upper.getOrNull(index - d)?.let(::add)
                    upper.getOrNull(index + d)?.let(::add)
                }
            }
            for (text in candidates) {
                val number = findNumber(text) ?: continue
                return ParsedCollectorLine(setLine.setCode, number.first, setLine.language, number.second)
            }
        }
        return null
    }

    private fun findNumber(text: String): Pair<String, Char?>? {
        numberWithTotal.find(text)?.let { m ->
            val rarity = text.substring(m.range.last + 1).trimStart().firstOrNull()
                ?.takeIf { it in "CURMLSTP" }
            return normalize(m.groupValues[1]) to rarity
        }
        paddedNumber.find(text)?.let { m ->
            val rarity = (m.groups[1] ?: m.groups[3])?.value?.single()
            return normalize(m.groupValues[2].replace('O', '0')) to rarity
        }
        return null
    }

    /** Scryfall speichert Sammlernummern ohne führende Nullen ("0149" → "149"). */
    private fun normalize(number: String): String = number.trimStart('0').ifEmpty { "0" }
}
