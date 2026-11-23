package app.cardscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectorLineParserTest {

    private fun parse(vararg lines: String) = CollectorLineParser.parse(lines.toList())

    @Test
    fun `altes Format mit Gesamtzahl`() {
        val p = parse("Lightning Bolt", "149/249 C", "M11 • EN ✒ Christopher Moeller")!!
        assertEquals(CollectorKey("M11", "149"), p.key)
        assertEquals("EN", p.language)
        assertEquals('C', p.rarity)
    }

    @Test
    fun `neues Format ohne Gesamtzahl mit fuehrenden Nullen`() {
        val p = parse("0081 U", "MOM • DE  Jane Doe")!!
        assertEquals(CollectorKey("MOM", "81"), p.key)
        assertEquals("DE", p.language)
        assertEquals('U', p.rarity)
    }

    @Test
    fun `Seltenheit vor der Nummer`() {
        val p = parse("R 0215", "LCI • EN")!!
        assertEquals(CollectorKey("LCI", "215"), p.key)
        assertEquals('R', p.rarity)
    }

    @Test
    fun `Aufzaehlungspunkt als Punkt oder fehlend gelesen`() {
        assertEquals(CollectorKey("KLD", "12"), parse("012/264 R", "KLD. EN")!!.key)
        assertEquals(CollectorKey("KLD", "12"), parse("012/264 R", "KLD EN")!!.key)
    }

    @Test
    fun `beide Angaben in einer Zeile`() {
        val p = parse("149/249 C M11 • EN")!!
        assertEquals(CollectorKey("M11", "149"), p.key)
    }

    @Test
    fun `Staerke und Widerstand oder Regeltext werden ignoriert`() {
        val p = parse(
            "Put a +1/+1 counter on target creature.",
            "3/3",
            "201/274 U",
            "ZNR • EN",
        )!!
        assertEquals(CollectorKey("ZNR", "201"), p.key)
    }

    @Test
    fun `Set-Code mit Ziffern`() {
        assertEquals(CollectorKey("2X2", "5"), parse("005/332 M", "2X2 • EN")!!.key)
    }

    @Test
    fun `ohne Sprachzeile kein Ergebnis`() {
        assertNull(parse("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target."))
    }

    @Test
    fun `Sprachkuerzel allein ist kein Set-Code`() {
        assertNull(parse("THE EN", "foo"))
    }

    // Die folgenden Fälle stammen aus echten OCR-Texten des Spike-Tests (19.11.2026).

    @Test
    fun `Copyright-Jahr ist keine Sammlernummer`() {
        val p = parse("TV& C 2024 Wizards of the Coast", "FDN • EN >lONAS DE Ro", "L 0270")!!
        assertEquals(CollectorKey("FDN", "270"), p.key)
        assertEquals('L', p.rarity)
    }

    @Test
    fun `deutsches Wort auf -en ist kein Set-Code`() {
        val p = parse(
            "Vergesst das nicht, ihr zwei Idioten!«",
            "C2025 Viacom.",
            "C",
            "O193",
            "TM & C 2025 Wizards of the Coast",
            "TLA • DE > HKYOUNG KIM",
        )!!
        assertEquals(CollectorKey("TLA", "193"), p.key)
    }

    @Test
    fun `Seltenheit direkt vor der Nummer`() {
        assertEquals(CollectorKey("TLA", "166"), parse("IM & C2025 Wizards of the Coast", "TLA • DE MATTEO BASSINI", "C0166")!!.key)
    }

    @Test
    fun `O statt 0 in der Nummer`() {
        assertEquals(CollectorKey("TLA", "176"), parse("IV&C2025 \\W'izards of the Coast", "TLA DE", "►TONI", "INEANTE", "CO176")!!.key)
        assertEquals(CollectorKey("TLA", "80"), parse("IM &© 2025 Wizards", "TLA DE >SYLVAIN SARRAILH", "C O08o")!!.key)
    }

    // Regeltext enthält Wörter, die wie "SET SPRACHE" aussehen. Die Setliste filtert sie heraus.

    private val catalog = SetCatalog(listOf("mkm", "tla", "lci", "m20", "2x2"))

    private fun parseKnown(vararg lines: String) = CollectorLineParser.parse(lines.toList(), catalog::resolve)

    @Test
    fun `englischer Regeltext wird nicht zum Set`() {
        val p = parseKnown("Exile it. Its controller", "gains 3 life.", "3/3", "0123 R", "MKM • EN")!!
        assertEquals(CollectorKey("MKM", "123"), p.key)
        assertEquals("EN", p.language)
    }

    @Test
    fun `deutscher Regeltext wird nicht zum Set`() {
        val p = parseKnown("Wenn es stirbt, ziehe", "eine Karte.", "C", "0049", "TLA • DE")!!
        assertEquals(CollectorKey("TLA", "49"), p.key)
        assertEquals("DE", p.language)
    }

    @Test
    fun `Regeltext wird nicht zum Set, auch wenn der Trenner fehlt`() {
        assertEquals(CollectorKey("TLA", "49"), parseKnown("Wenn es stirbt.", "C", "0049", "TLA DE")!!.key)
    }

    @Test
    fun `ohne Setliste gewinnt die Zeile mit Trenner`() {
        assertEquals(CollectorKey("TLA", "49"), parse("Wenn es stirbt, ziehe", "C", "0049", "TLA • DE")!!.key)
    }

    @Test
    fun `Setliste korrigiert O und 0 im Set-Code`() {
        assertEquals(CollectorKey("M20", "149"), parseKnown("149/280 C", "M2O • EN")!!.key)
    }

    @Test
    fun `unbekanntes Set ergibt kein Ergebnis`() {
        assertNull(parseKnown("0123 R", "XYZ • EN"))
    }

    @Test
    fun `Seltenheit und Nummer auf getrennten Zeilen`() {
        val p = parse("IM & C2025 Wizards of the Coast", "C2025", "Viacom.", "TLA • DE", "KS/MIZUTAMETORI", "C", "0049")!!
        assertEquals(CollectorKey("TLA", "49"), p.key)
    }
}
