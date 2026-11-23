package app.cardscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cardscanner.ScryfallCard
import app.cardscanner.ScryfallClient
import app.cardscanner.data.CollectionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Manuelle Suche in zwei Schritten: erst die Karte über den Namen (englisch oder gedruckt),
 * dann die Druckversion aus allen deutschen und englischen Drucken.
 */
class SearchViewModel(
    private val repository: CollectionRepository,
    private val scryfall: ScryfallClient,
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val cards: List<ScryfallCard> = emptyList(),
        /** Gewählte Karte; solange gesetzt, zeigt die Suche ihre Druckversionen. */
        val selected: ScryfallCard? = null,
        val prints: List<ScryfallCard> = emptyList(),
        val message: String? = null,
    )

    val state = MutableStateFlow(State())

    val locations = repository.locations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun search(text: String) {
        if (text.isBlank()) return
        load {
            val cards = scryfall.searchCards(text.trim())
            state.value = State(cards = cards, message = if (cards.isEmpty()) "Keine Karte zu „$text“ gefunden." else null)
        }
    }

    fun select(card: ScryfallCard) {
        val oracleId = card.oracleId ?: return
        state.value = state.value.copy(selected = card, prints = emptyList())
        load { state.value = state.value.copy(prints = scryfall.prints(oracleId)) }
    }

    fun back() {
        state.value = state.value.copy(selected = null, prints = emptyList())
    }

    fun add(card: ScryfallCard, count: Int, foil: Boolean, location: LocationChoice) {
        viewModelScope.launch {
            repository.addCopies(card, foil, repository.resolve(location), count)
            val name = card.printedName ?: card.name
            state.value = state.value.copy(message = "$count × $name hinzugefügt (${location.label})")
        }
    }

    fun clearMessage() {
        state.value = state.value.copy(message = null)
    }

    private fun load(block: suspend () -> Unit) {
        job?.cancel()
        state.value = state.value.copy(loading = true, message = null)
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                state.value = state.value.copy(message = "Scryfall nicht erreichbar: ${e.message}")
            } finally {
                state.value = state.value.copy(loading = false)
            }
        }
    }
}
