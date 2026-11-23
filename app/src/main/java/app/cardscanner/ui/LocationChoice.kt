package app.cardscanner.ui

import app.cardscanner.data.CollectionRepository
import app.cardscanner.data.LocationKind
import app.cardscanner.data.StorageLocation

/** Auswahl im Lagerort-Dialog. Neue Lagerorte werden erst beim Speichern angelegt. */
sealed interface LocationChoice {
    val label: String

    data object None : LocationChoice {
        override val label = "Ohne Lagerort"
    }

    data class Existing(val location: StorageLocation) : LocationChoice {
        override val label get() = location.label
    }

    data class New(val kind: LocationKind, val name: String) : LocationChoice {
        override val label get() = "${kind.label}: ${name.trim()}"
    }

    companion object {
        fun of(location: StorageLocation?): LocationChoice = location?.let(::Existing) ?: None
    }
}

suspend fun CollectionRepository.resolve(choice: LocationChoice): Long? = when (choice) {
    LocationChoice.None -> null
    is LocationChoice.Existing -> choice.location.id
    is LocationChoice.New -> location(choice.kind, choice.name).id
}
