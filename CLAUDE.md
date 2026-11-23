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

## Reihenfolge

1. **Spike (abgeschlossen 23.11.2026):** Kamera-Vorschau mit Live-OCR, Anzeige von gelesenem Wert und Scryfall-Treffer, ohne Speichern. Ergebnis: Der OCR-Ansatz trägt. Zweiter Lauf 14 von 15 erkannt (DE/EN, 9 Sets, Basic Lands), 0,8–1,5 s von der ersten Erkennung bis zum Treffer. Die eine Ausnahme ist eine alte Karte ohne Set-Code (Prophecy, 2000). Sven hat nur diese eine alte Karte.
2. **MVP:** Dauerscan, Bestätigung, Room-Datenbank, Liste, Bearbeiten, Lagerort, manuelle Suche, Export.
3. **Danach:** CSV-Re-Import, Cache-Optimierung. Ein OCR-Rückfall für alte Karten ohne Set-Code wird nicht gebaut, weil es kaum solche Karten gibt. Falls doch nötig: Nummer aus `45/143` (OCR liest den Schrägstrich auch als Punkt) und Scryfall-Suche `cn:45 lang:de`, dazu lokal den OCR-Namen mit `printed_name` vergleichen. `/cards/named?fuzzy=` findet keine deutschen Namen.

## Bauen

Android Studio installiert Sven selbst. Gebaut wird per Gradle auf der Kommandozeile, installiert per `adb install` über Wireless-Debugging aufs Pixel 9.

Debug-Builds werden mit `app/debug.keystore` aus dem Repo signiert, damit Builds vom Mac und von Windows einander per `adb install -r` ersetzen können. Den Schlüssel nicht ersetzen, sonst muss die App deinstalliert werden und ihre Daten gehen verloren.
