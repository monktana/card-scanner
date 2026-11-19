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

    private val setLang = Regex("""(?<![A-Z0-9])([A-Z0-9]{3,5})\s*[•·∙*.\-+]?\s*($LANGS)(?![A-Z])""")

    // "149/249" – Gesamtzahl mit 2–4 Stellen, kein Vorzeichen davor (schließt "+1/+1" aus).
    private val numberWithTotal = Regex("""(?<![\d+\-])(\d{1,4})\s*/\s*\d{2,4}(?!\d)""")

    // "C 0149" bzw. "0149 C" (neues Format ohne Gesamtzahl, 3–4 Stellen).
    private val rarityThenNumber = Regex("""(?<![A-Z0-9])([CURMLSTP])\s+(\d{3,4})(?!\d)""")
    private val numberThenRarity = Regex("""(?<![\d/])(\d{3,4})\s+([CURMLSTP])(?![A-Z0-9])""")

    private const val MAX_LINE_DISTANCE = 2

    fun parse(lines: List<String>): ParsedCollectorLine? {
        val upper = lines.map { it.uppercase() }

        for ((index, line) in upper.withIndex()) {
            val match = setLang.findAll(line).firstOrNull { m ->
                m.groupValues[1].any(Char::isLetter)
            } ?: continue
            val setCode = match.groupValues[1]
            val language = match.groupValues[2]

            // Zuerst in derselben Zeile (ohne den Set-Teil), dann in den Nachbarzeilen suchen.
            val sameLine = line.removeRange(match.range)
            val candidates = buildList {
                add(sameLine)
                for (d in 1..MAX_LINE_DISTANCE) {
                    upper.getOrNull(index - d)?.let(::add)
                    upper.getOrNull(index + d)?.let(::add)
                }
            }
            for (text in candidates) {
                val number = findNumber(text) ?: continue
                return ParsedCollectorLine(setCode, number.first, language, number.second)
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
        rarityThenNumber.find(text)?.let { m ->
            return normalize(m.groupValues[2]) to m.groupValues[1].single()
        }
        numberThenRarity.find(text)?.let { m ->
            return normalize(m.groupValues[1]) to m.groupValues[2].single()
        }
        return null
    }

    /** Scryfall speichert Sammlernummern ohne führende Nullen ("0149" → "149"). */
    private fun normalize(number: String): String = number.trimStart('0').ifEmpty { "0" }
}
