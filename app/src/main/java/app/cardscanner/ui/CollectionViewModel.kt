package app.cardscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cardscanner.ScryfallClient
import app.cardscanner.data.CollectionExport
import app.cardscanner.data.CollectionRepository
import app.cardscanner.data.EntryWithLocation
import app.cardscanner.data.LocationKind
import app.cardscanner.data.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class CollectionViewModel(
    private val repository: CollectionRepository,
    private val scryfall: ScryfallClient,
) : ViewModel() {

    val query = MutableStateFlow("")

    val entries: StateFlow<List<EntryWithLocation>> =
        combine(repository.entries, query) { all, q -> all.filter { it.matches(q) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locations = repository.locations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Fehlermeldung beim Speichern, z. B. wenn es die Karte in der Sprache nicht gibt. */
    val error = MutableStateFlow<String?>(null)

    fun save(
        item: EntryWithLocation,
        quantity: Int,
        foil: Boolean,
        lang: String,
        location: LocationChoice,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val entry = item.entry
            val card = if (lang != entry.lang) {
                try {
                    scryfall.inLanguage(entry.setCode, entry.collectorNumber, lang)
                        ?: return@launch fail("Scryfall kennt ${entry.setCode.uppercase()} ${entry.collectorNumber} nicht auf ${lang.uppercase()}.")
                } catch (e: IOException) {
                    return@launch fail("Scryfall nicht erreichbar: ${e.message}")
                }
            } else {
                null
            }
            repository.saveEntry(entry.id, quantity, foil, repository.resolve(location), card)
            error.value = null
            onDone()
        }
    }

    /** Verschiebt [count] Exemplare in einen anderen Lagerort; Foil bleibt, wie es ist. */
    fun move(item: EntryWithLocation, count: Int, target: LocationChoice, onDone: () -> Unit) {
        viewModelScope.launch {
            val e = item.entry
            val targetId = repository.resolve(target)
            if (targetId != e.locationId) {
                repository.moveCopies(e.id, count, e.foil, targetId)
                notice.value = "$count × ${e.printedName ?: e.name} nach „${target.label}“ verschoben."
            }
            onDone()
        }
    }

    fun delete(item: EntryWithLocation, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(item.entry.id)
            onDone()
        }
    }

    // --- Lagerorte verwalten -------------------------------------------------------------

    val locationStats = repository.locationStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createLocation(kind: LocationKind, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.location(kind, name) }
    }

    /** Benennt um; gibt es den Namen schon, kommt [onConflict], erst mit merge = true wird zusammengeführt. */
    fun renameLocation(
        id: Long,
        kind: LocationKind,
        name: String,
        merge: Boolean,
        onConflict: (StorageLocation) -> Unit,
        onDone: () -> Unit,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.renameLocation(id, kind, name, merge)) {
                CollectionRepository.RenameResult.Done -> onDone()
                is CollectionRepository.RenameResult.Conflict -> onConflict(result.existing)
            }
        }
    }

    fun deleteLocation(id: Long, target: StorageLocation?, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteLocation(id, target?.id)
            onDone()
        }
    }

    // --- Mehrfachauswahl ----------------------------------------------------------------

    /** Ausgewählte Einträge; leer heißt: keine Auswahl aktiv. */
    val selected = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelected(entryId: Long) {
        selected.value = selected.value.let { if (entryId in it) it - entryId else it + entryId }
    }

    /** Wählt alle gerade angezeigten (gefilterten) Einträge; sind schon alle gewählt, hebt es die Auswahl auf. */
    fun selectAllVisible() {
        val visible = entries.value.map { it.entry.id }.toSet()
        selected.value = if (selected.value.containsAll(visible)) emptySet() else visible
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val cards = entries.value.filter { it.entry.id in ids }.sumOf { it.entry.quantity }
            repository.delete(ids)
            selected.value = emptySet()
            notice.value = "${ids.size} Einträge ($cards Karten) gelöscht."
        }
    }

    // --- Export / Import ---------------------------------------------------------------

    /** Meldung nach Export oder Import. */
    val notice = MutableStateFlow<String?>(null)

    data class PendingImport(val rows: List<CollectionExport.ImportRow>, val existingEntries: Int)

    /** CSV gelesen, wartet auf „Ersetzen“ oder „Ergänzen“. */
    val pendingImport = MutableStateFlow<PendingImport?>(null)

    /** Schreibt sammlung.txt und sammlung.csv nach [dir] und gibt beide Dateien zurück. */
    suspend fun writeExport(dir: File): List<File> {
        val all = repository.entries.first()
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            listOf(
                File(dir, "sammlung.txt").apply { writeText(CollectionExport.namesFile(all, LocalDate.now())) },
                File(dir, "sammlung.csv").apply { writeText(CollectionExport.csv(all)) },
            )
        }
    }

    fun readImport(text: String) {
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.Default) { CollectionExport.parseCsv(text) }
                pendingImport.value = PendingImport(rows, repository.entryCount())
            } catch (e: CollectionExport.ImportException) {
                notice.value = "Import abgebrochen: ${e.message}"
            }
        }
    }

    fun confirmImport(replace: Boolean) {
        val pending = pendingImport.value ?: return
        pendingImport.value = null
        viewModelScope.launch {
            val count = repository.import(pending.rows, replace)
            notice.value = "$count Karten importiert" + if (replace) " (Sammlung ersetzt)." else " (ergänzt)."
        }
    }

    private fun fail(message: String) {
        error.value = message
    }

    private fun EntryWithLocation.matches(q: String): Boolean {
        if (q.isBlank()) return true
        val e = entry
        return q.trim().split(Regex("\\s+")).all { word ->
            listOfNotNull(e.name, e.printedName, e.setCode, e.setName, location?.name)
                .any { it.contains(word, ignoreCase = true) }
        }
    }
}
