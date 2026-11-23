package app.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardscanner.data.EntryWithLocation
import app.cardscanner.data.StorageLocation

@Composable
fun CollectionScreen(viewModel: CollectionViewModel, onAddManually: () -> Unit, onManageLocations: () -> Unit) {
    val entries by viewModel.entries.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val query by viewModel.query.collectAsState()
    var editing by remember { mutableStateOf<EntryWithLocation?>(null) }
    val selected by viewModel.selected.collectAsState()
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onSelectAll = viewModel::selectAllVisible,
                    onDelete = { confirmDelete = true },
                    onCancel = viewModel::clearSelection,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder = { Text("Suchen: Name, Set, Lagerort") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    )
                    CollectionMenu(viewModel, onManageLocations)
                }
            }
            val notice by viewModel.notice.collectAsState()
            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.notice.value = null }.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                "${entries.sumOf { it.entry.quantity }} Karten · ${entries.size} Einträge",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    if (query.isBlank()) "Die Sammlung ist leer. Scanne Karten oder füge sie über + hinzu." else "Keine Treffer.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.entry.id }) { item ->
                    val id = item.entry.id
                    EntryRow(
                        item = item,
                        selecting = selecting,
                        selected = id in selected,
                        onClick = { if (selecting) viewModel.toggleSelected(id) else editing = item },
                        onLongClick = { viewModel.toggleSelected(id) },
                    )
                    HorizontalDivider()
                }
            }
        }
        if (!selecting) {
            FloatingActionButton(
                onClick = onAddManually,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Karte suchen und hinzufügen") }
        }
    }

    if (confirmDelete) {
        val chosen = entries.filter { it.entry.id in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Einträge löschen?") },
            text = {
                Text(
                    "${selected.size} Einträge mit ${chosen.sumOf { it.entry.quantity }} Karten werden aus der Sammlung " +
                        "gelöscht. Das lässt sich nicht rückgängig machen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    editing?.let { item ->
        EditEntrySheet(
            item = item,
            locations = locations,
            viewModel = viewModel,
            onDismiss = {
                editing = null
                viewModel.error.value = null
            },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onSelectAll: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("✕") }
        Text("$count ausgewählt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text("Alle") }
        Button(onClick = onDelete, modifier = Modifier.padding(end = 8.dp)) { Text("Löschen") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    item: EntryWithLocation,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val e = item.entry
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        CardThumb(e.imageUrl, e.name)
        SpacerW(12.dp)
        Column(Modifier.weight(1f)) {
            Text(e.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.printedName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            Text(
                listOfNotNull(
                    "${e.setCode.uppercase()} ${e.collectorNumber}",
                    e.lang.uppercase(),
                    "Foil".takeIf { e.foil },
                    item.location?.label,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        Text("×${e.quantity}", style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditEntrySheet(
    item: EntryWithLocation,
    locations: List<StorageLocation>,
    viewModel: CollectionViewModel,
    onDismiss: () -> Unit,
) {
    val e = item.entry
    var quantity by remember { mutableStateOf(e.quantity) }
    var foil by remember { mutableStateOf(e.foil) }
    var lang by remember { mutableStateOf(e.lang) }
    var location by remember { mutableStateOf(LocationChoice.of(item.location)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val error by viewModel.error.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardThumb(e.imageUrl, e.name, width = 80.dp)
                SpacerW(16.dp)
                Column {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    e.printedName?.let { Text(it) }
                    Text("${e.setName} · ${e.setCode.uppercase()} ${e.collectorNumber}", color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Anzahl", Modifier.weight(1f))
                QuantityStepper(quantity, { quantity = it }, min = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Foil", Modifier.weight(1f))
                Switch(checked = foil, onCheckedChange = { foil = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Sprache", Modifier.weight(1f))
                LanguageChoice(lang) { lang = it }
            }
            LocationField(location, locations) { location = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmDelete) {
                    Button(onClick = { viewModel.delete(item, onDismiss) }) { Text("Wirklich löschen") }
                    OutlinedButton(onClick = { confirmDelete = false }) { Text("Nein") }
                } else {
                    Button(onClick = { viewModel.save(item, quantity, foil, lang, location, onDismiss) }) { Text("Speichern") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Löschen") }
                }
            }
        }
    }
}
