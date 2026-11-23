# Card Scanner

Android-App (nur Pixel 9, nur privat) zum Scannen von Magic-Karten in einer Handy-Halterung. Die App ist der einzige Ort der Sammlung; Decks und Wantlists entstehen in Claude Desktop und in `C:\Users\sveng\Code\scripts\scryfall`. Begriffe stehen im `GLOSSARY.md`.

## Entscheidungen (16.11.2026)

- **Stack:** Native Kotlin, CameraX, Google ML Kit Text Recognition (on-device). Lokale Datenbank mit Room.
- **Erkennung:** Set-Code + Sammlernummer per OCR (unten links), Abgleich über Scryfall `/cards/{set}/{nr}`. Set-Codes werden gegen die Scryfall-Setliste geprüft. Karten, die die OCR nicht liest (alt, verknickt, Foil), kommen über eine manuelle Suche dazu: Name auf Englisch oder Deutsch eingeben (Scryfall `lang:any`), Druckversion aus einer Liste mit Bildern wählen. Foil und Sprache von Hand, kein Zustand.
- **Sprache:** Deutsche und englische Karten gemischt; Abgleich sprachunabhängig über Set + Nummer.
- **Dauerscan:** Ein Exemplar zählt erst, wenn zwischendurch keine Karte erkannt wurde. Liste der letzten Scans mit +/−.
- **Scryfall:** Live-API mit lokalem Cache, eigener `User-Agent`, Pause zwischen Abfragen (max. ~10/s). Bulk-Daten nur, falls der Dauerscan zu langsam wird.
- **Sammlung:** Startet leer, kein Import der Namen-Dateien. Flach, mit optionalem Lagerort pro Scan-Sitzung. Kein Wantlist-Abgleich in der App.
- **Export:** `sammlung.txt` im Namen-Datei-Format (`1 Lightning Bolt (M11) 149`, englischer Oracle-Name, doppelseitige Karten `A // B`, `#! Lagerort` als Gruppen) und `sammlung.csv` mit allen Feldern (Scryfall-ID, Name EN/DE, Set, Nummer, Sprache, Foil, Anzahl, Lagerort, Scan-Datum). Die CSV ist wieder importierbar. Weg: Android-Teilen-Menü → `scripts/scryfall/sammlung/` und Claude-Projekt.

## MVP-Entscheidungen (23.11.2026)

- **Aufbau:** Zwei Reiter, Scannen und Sammlung. Die Sammlung ist eine durchsuchbare Liste mit Bearbeiten, Verschieben und Löschen. Das Menü enthält Exportieren und Import (CSV), die +-Schaltfläche startet die manuelle Suche.
- **Sprache** kommt automatisch von der Karte (gedrucktes `DE`/`EN`) und ist am Eintrag korrigierbar.
- **Foil:** Schalter „Foil-Modus“ pro Scan-Sitzung, zusätzlich pro Eintrag in der Liste der letzten Scans umschaltbar.
- **Rückmeldung:** Vibration + Ton bei Treffer. Ist eine Karte nach etwa 3 s nicht erkannt, gibt es ein eigenes Signal und einen roten Hinweis „Nicht erkannt – beiseitelegen“.
- **Eintrag** = Druckversion (Scryfall-ID, je Sprache verschieden) + Foil + Lagerort, dazu die Anzahl. Die Kartendaten (Name EN/DE, Set, Nummer, Bild-URL) werden beim Hinzufügen gespeichert, damit Liste und Export offline funktionieren.
- **Lagerort** hat eine Art, *Deck* oder *Bulk*, und einen Namen (Freitext mit Vorschlägen). Decknamen sind per Konvention die Ordnernamen aus `scripts/scryfall` (z. B. `multiverse-reforged`). Die App kennt keine Decklisten, nur den Ort der Karten.
- **Deck neu scannen:** Gibt es den Decknamen schon, fragt die App „Ersetzen“ oder „Ergänzen“. Beim Ersetzen werden die alten Einträge erst beim Abschließen der Sitzung entfernt. Karten, die nicht mehr im Deck sind, landen im Bulk-Lagerort „Aus Decks“.
- **Verschieben** am Eintrag, mit Anzahl und Ziel-Lagerort.
- **Export:** Feste Dateinamen `sammlung.txt` und `sammlung.csv`, beide zusammen über das Teilen-Menü. Die Gruppen heißen `#! Deck: <name>`, `#! Bulk: <name>` und `#! Ohne Lagerort`. Die CSV hat die zusätzliche Spalte `art`.
- **Export-Format (umgesetzt):** In `sammlung.txt` steht keine Foil-Markierung, weil `kartentexte.py` sonst `(SET) Nr` nicht abschneidet. Gleiche Druckversionen innerhalb eines Lagerorts (DE/EN, Foil) werden zu einer Zeile zusammengefasst. Achtung: `kartentexte.py` addiert eine Karte über alle Gruppen und ordnet sie der ersten Gruppe zu. Das Skript braucht die Sammlung nur als Namensliste, die Aufteilung nach Lagerort liest Claude direkt aus der Datei. Die CSV nutzt `;` als Trenner, UTF-8, Foil `ja`/`nein`, Art `Deck`/`Bulk`/leer. Import: „Ersetzen“ (Wiederherstellen) oder „Ergänzen“, alles oder nichts.
- **Diagnose-Ansicht** (ausgeblendet, langes Drücken auf den Lagerort-Namen in der laufenden Scan-Sitzung): Rohtext, „Nicht erkannt: Bild speichern“, `treffer.csv`. Werkzeug zum Verbessern des Parsers.

- **Scan-Sitzung:** Startbildschirm (Lagerort, Foil-Modus, bei vorhandenem Deck Ergänzen/Ersetzen), dann erst die Kamera. Jeder Treffer wird sofort gespeichert. Die Sitzung liegt in `session.json` und übersteht einen Neustart der App. „Nicht erkannt“ kommt nach 3 s mit Text (mindestens 4 Zeilen) ohne Set und Nummer. Eine Statusleiste über der Kamera zeigt immer, was der Scanner tut.
- **Gleiche Karte hintereinander:** Sven schiebt Karten in der Halterung auf einen Stapel, das Fach ist erst leer, wenn er den Stapel entnimmt. Dieselbe Druckversion zählt automatisch erst nach leerem Fach erneut. Für eine nachgeschobene gleiche Karte gibt es „+1“ in der grünen Statusleiste. Bewusst keine Bewegungserkennung, weil sie unbemerkt doppelt zählen könnte.

## Reihenfolge

1. **Spike (abgeschlossen 23.11.2026):** Kamera-Vorschau mit Live-OCR, Anzeige von gelesenem Wert und Scryfall-Treffer, ohne Speichern. Ergebnis: Der OCR-Ansatz trägt. Zweiter Lauf 14 von 15 erkannt (DE/EN, 9 Sets, Basic Lands), 0,8–1,5 s von der ersten Erkennung bis zum Treffer. Die eine Ausnahme ist eine alte Karte ohne Set-Code (Prophecy, 2000). Sven hat nur diese eine alte Karte.
2. **MVP (fertig 23.11.2026):** Dauerscan, Bestätigung, Room-Datenbank, Liste, Bearbeiten, Lagerort, manuelle Suche, Export und CSV-Import, Verschieben mit Anzahl. Dazugekommen sind Mehrfachauswahl zum Löschen und die Lagerort-Verwaltung (Umbenennen und Zusammenführen, Löschen mit Verschieben der Karten).
3. **Danach:** Cache-Optimierung. Ein OCR-Rückfall für alte Karten ohne Set-Code wird nicht gebaut, weil es kaum solche Karten gibt. Falls doch nötig: Nummer aus `45/143` (OCR liest den Schrägstrich auch als Punkt) und Scryfall-Suche `cn:45 lang:de`, dazu lokal den OCR-Namen mit `printed_name` vergleichen. `/cards/named?fuzzy=` findet keine deutschen Namen.

## Bauen

Android Studio installiert Sven selbst. Gebaut wird per Gradle auf der Kommandozeile, installiert per `adb install` über Wireless-Debugging aufs Pixel 9.

Debug-Builds werden mit `app/debug.keystore` aus dem Repo signiert, damit Builds vom Mac und von Windows einander per `adb install -r` ersetzen können. Den Schlüssel nicht ersetzen, sonst muss die App deinstalliert werden und ihre Daten gehen verloren.
