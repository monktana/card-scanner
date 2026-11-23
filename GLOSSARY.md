# Glossar

Begriffe für Code, Doku und Oberfläche. Namen-Datei und Gruppe kommen aus `C:\Users\sveng\Code\scripts\GLOSSARY.md` und bedeuten hier dasselbe.

**Sammlung**:
Alle Karten, die Sven physisch besitzt. Die App ist ihr einziger Ort.
_Vermeiden_: Bestand, Kollektion

**Druckversion**:
Eine Karte in einem bestimmten Set mit bestimmter Sammlernummer (Scryfall: „printing“).
_Vermeiden_: Edition, Version

**Exemplar**:
Ein physisches Stück einer Druckversion. Die Anzahl zählt Exemplare.

**Lagerort**:
Wo Exemplare physisch liegen. Hat eine Art (Deck oder Bulk) und einen Namen. Im Export wird er zur Gruppe (`#! Deck: vivi-ornitier`).

**Deck**:
Lagerort-Art für ein gebautes Deck. Der Name ist der Ordnername des Decks in `scripts/scryfall`.

**Bulk**:
Lagerort-Art für lose Karten, z. B. eine Box. „Aus Decks“ ist der Bulk-Lagerort für Karten, die beim Neuscannen aus einem Deck gefallen sind.
_Vermeiden_: Lose, Vorrat

**Eintrag**:
Eine Zeile der Sammlung: Druckversion + Foil + Lagerort, dazu die Anzahl der Exemplare.

**Scan-Sitzung**:
Ein Dauerscan-Durchlauf mit einem festen Lagerort. Eine Deck-Sitzung kann ein vorhandenes Deck ersetzen oder ergänzen.

**Namen-Datei**:
Eine Liste von Kartennamen, eine Karte pro Zeile (`1 Name (SET) Nr`), optional mit `#! Gruppe`. `sammlung.txt` ist eine Namen-Datei.

**Gruppe**:
Eine benannte Teilmenge der Karten in einer Namen-Datei.
