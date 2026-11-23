# Card Scanner

Android-App (nur Pixel 9, nur privat) zum Scannen von Magic-Karten in einer Handy-Halterung. Die App ist der einzige Ort der Sammlung; Decks und Wantlists entstehen in Claude Desktop und in `C:\Users\sveng\Code\scripts\scryfall`. Begriffe stehen im `GLOSSARY.md`.

## Entscheidungen (16.11.2026)

- **Stack:** Native Kotlin, CameraX, Google ML Kit Text Recognition (on-device). Lokale Datenbank mit Room.
- **Erkennung:** Set-Code + Sammlernummer per OCR (unten links), Abgleich über Scryfall `/cards/{set}/{nr}`. Rückfall bei alten Karten: Name per OCR, Druckversion aus Liste wählen, Schalter „Druckversion egal“. Foil und Sprache von Hand, kein Zustand.
- **Sprache:** Deutsche und englische Karten gemischt; Abgleich sprachunabhängig über Set + Nummer.
- **Dauerscan:** Ein Exemplar zählt erst, wenn zwischendurch keine Karte erkannt wurde. Liste der letzten Scans mit +/−.
- **Scryfall:** Live-API mit lokalem Cache, eigener `User-Agent`, Pause zwischen Abfragen (max. ~10/s). Bulk-Daten nur, falls der Dauerscan zu langsam wird.
- **Sammlung:** Startet leer, kein Import der Namen-Dateien. Flach, mit optionalem Lagerort pro Scan-Sitzung. Kein Wantlist-Abgleich in der App.
- **Export:** `sammlung.txt` im Namen-Datei-Format (`1 Lightning Bolt (M11) 149`, englischer Oracle-Name, doppelseitige Karten `A // B`, `#! Lagerort` als Gruppen) und `sammlung.csv` mit allen Feldern (Scryfall-ID, Name EN/DE, Set, Nummer, Sprache, Foil, Anzahl, Lagerort, Scan-Datum). Die CSV ist wieder importierbar. Weg: Android-Teilen-Menü → `scripts/scryfall/sammlung/` und Claude-Projekt.

## Reihenfolge

1. **Spike:** Kamera-Vorschau mit Live-OCR auf festem Ausschnitt, Anzeige von gelesenem Wert und Scryfall-Treffer, ohne Speichern. Test mit 30–50 gemischten Karten (alt/neu, DE/EN, Foil). Kann den Erkennungsansatz noch ändern.
2. **MVP:** Dauerscan, Bestätigung, Room-Datenbank, Liste, Bearbeiten, Lagerort, Export.
3. **Danach:** Rückfall mit Druckversionsauswahl, CSV-Re-Import, Cache-Optimierung.

## Bauen

Android Studio installiert Sven selbst. Gebaut wird per Gradle auf der Kommandozeile, installiert per `adb install` über Wireless-Debugging aufs Pixel 9.

Debug-Builds werden mit `app/debug.keystore` aus dem Repo signiert, damit Builds vom Mac und von Windows einander per `adb install -r` ersetzen können. Den Schlüssel nicht ersetzen, sonst muss die App deinstalliert werden und ihre Daten gehen verloren.
