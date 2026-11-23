package app.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.cardscanner.ScryfallCard
import app.cardscanner.data.StorageLocation

@Composable
fun ManualSearchScreen(viewModel: SearchViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val locations by viewModel.locations.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf<ScryfallCard?>(null) }
    // Der Lagerort bleibt für mehrere Karten hintereinander gewählt.
    var location by remember { mutableStateOf<LocationChoice>(LocationChoice.None) }

    BackHandler { if (state.selected != null) viewModel.back() else onClose() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            TextButton(onClick = { if (state.selected != null) viewModel.back() else onClose() }) { Text("← Zurück") }
            Text(
                state.selected?.let { "Druckversion wählen" } ?: "Karte suchen",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (state.selected == null) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Name, englisch oder deutsch") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(text) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        state.message?.let {
            Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
        }

        val selected = state.selected
        LazyColumn(Modifier.fillMaxSize()) {
            if (selected == null) {
                items(state.cards, key = { it.id }) { card ->
                    CardRow(card, detail = card.printedName) { viewModel.select(card) }
                    HorizontalDivider()
                }
            } else {
                items(state.prints, key = { it.id }) { print ->
                    CardRow(print, detail = "${print.setName} · ${print.setCode.uppercase()} ${print.collectorNumber} · ${print.lang.uppercase()}") {
                        adding = print
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    adding?.let { card ->
        AddSheet(
            card = card,
            locations = locations,
            location = location,
            onLocationChange = { location = it },
            onAdd = { count, foil ->
                viewModel.add(card, count, foil, location)
                adding = null
            },
            onDismiss = { adding = null },
        )
    }
}

@Composable
private fun CardRow(card: ScryfallCard, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(card.imageUrl, card.name, width = 56.dp)
        SpacerW(12.dp)
        Column {
            Text(card.printedName ?: card.name, fontWeight = FontWeight.Bold)
            if (card.printedName != null) Text(card.name, style = MaterialTheme.typography.bodySmall)
            detail?.takeIf { it != card.printedName }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    card: ScryfallCard,
    locations: List<StorageLocation>,
    location: LocationChoice,
    onLocationChange: (LocationChoice) -> Unit,
    onAdd: (count: Int, foil: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableStateOf(1) }
    var foil by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardThumb(card.imageUrl, card.name, width = 80.dp)
                SpacerW(16.dp)
                Column {
                    Text(card.printedName ?: card.name, style = MaterialTheme.typography.titleMedium)
                    Text("${card.setName} · ${card.setCode.uppercase()} ${card.collectorNumber} · ${card.lang.uppercase()}", color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anzahl", Modifier.weight(1f))
                QuantityStepper(count, { count = it }, min = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Foil", Modifier.weight(1f))
                Switch(checked = foil, onCheckedChange = { foil = it })
            }
            LocationField(location, locations, onLocationChange)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onAdd(count, foil) }, modifier = Modifier.fillMaxWidth()) { Text("Hinzufügen") }
        }
    }
}
