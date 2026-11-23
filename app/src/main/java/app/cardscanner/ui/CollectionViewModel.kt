package app.cardscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cardscanner.ScryfallClient
import app.cardscanner.data.CollectionRepository
import app.cardscanner.data.EntryWithLocation
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

    fun delete(item: EntryWithLocation, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(item.entry.id)
            onDone()
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
