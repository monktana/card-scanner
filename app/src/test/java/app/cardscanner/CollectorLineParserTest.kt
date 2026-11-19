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
}
