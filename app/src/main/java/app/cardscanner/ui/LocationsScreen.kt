package app.cardscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.cardscanner.data.LocationKind
import app.cardscanner.data.LocationStats
import app.cardscanner.data.StorageLocation

/**
 * Lagerorte anlegen, umbenennen (auch Art ändern) und löschen.
 *
 * @param activeSessionLocationId Lagerort der laufenden Scan-Sitzung; darf nicht gelöscht werden
 */
@Composable
fun LocationsScreen(viewModel: CollectionViewModel, activeSessionLocationId: Long?, onClose: () -> Unit) {
    val stats by viewModel.locationStats.collectAsState()
    var editing by remember { mutableStateOf<LocationStats?>(null) }
    var creating by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                TextButton(onClick = onClose) { Text("← Zurück") }
                Text("Lagerorte", style = MaterialTheme.typography.titleMedium)
            }
            if (stats.isEmpty()) {
                Text("Noch keine Lagerorte. Lege einen mit + an.", modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(stats, key = { it.location.id }) { s ->
                    Column(
                        Modifier.fillMaxWidth().clickable { editing = s }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(s.location.label, fontWeight = FontWeight.Bold)
                        Text(
                            "${s.cardCount} Karten · ${s.entryCount} Einträge" +
                                if (s.location.id == activeSessionLocationId) " · laufende Scan-Sitzung" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Lagerort anlegen") }
    }

    if (creating) {
        LocationNameDialog(
            title = "Neuer Lagerort",
            initialKind = LocationKind.BULK,
            initialName = "",
            confirmLabel = "Anlegen",
            onConfirm = { kind, name ->
                viewModel.createLocation(kind, name)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { s ->
        EditLocationDialog(
            stats = s,
            others = stats.map { it.location }.filter { it.id != s.location.id },
            deletable = s.location.id != activeSessionLocationId,
            viewModel = viewModel,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EditLocationDialog(
    stats: LocationStats,
    others: List<StorageLocation>,
    deletable: Boolean,
    viewModel: CollectionViewModel,
    onDismiss: () -> Unit,
) {
    val location = stats.location
    var mode by remember { mutableStateOf(Mode.EDIT) }
    var kind by remember { mutableStateOf(location.kind) }
    var name by remember { mutableStateOf(location.name) }
    var conflict by remember { mutableStateOf<StorageLocation?>(null) }
    // Ziel für die Karten beim Löschen; null = ohne Lagerort.
    var target by remember { mutableStateOf<StorageLocation?>(null) }

    fun rename(merge: Boolean) = viewModel.renameLocation(
        location.id, kind, name, merge,
        onConflict = { conflict = it },
        onDone = onDismiss,
    )

    when {
        conflict != null -> AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Zusammenführen?") },
            text = {
                Text(
                    "„${conflict!!.label}“ gibt es schon. Alle ${stats.cardCount} Karten aus „${location.label}“ wandern " +
                        "dorthin, gleiche Einträge werden addiert, und „${location.label}“ verschwindet.",
                )
            },
            confirmButton = { TextButton(onClick = { rename(merge = true) }) { Text("Zusammenführen") } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Abbrechen") } },
        )

        mode == Mode.DELETE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("„${location.label}“ löschen?") },
            text = {
                Column {
                    if (stats.cardCount == 0) {
                        Text("Der Lagerort ist leer.")
                    } else {
                        Text("Wohin mit den ${stats.cardCount} Karten?")
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(listOf<StorageLocation?>(null) + others) { option ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { target = option },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = target == option, onClick = { target = option })
                                    Text(option?.label ?: LocationChoice.None.label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteLocation(location.id, target, onDismiss) }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { mode = Mode.EDIT }) { Text("Zurück") } },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Lagerort bearbeiten") },
            text = {
                Column {
                    KindAndName(kind, name, onKind = { kind = it }, onName = { name = it })
                    TextButton(
                        enabled = deletable,
                        onClick = { mode = Mode.DELETE },
                    ) {
                        Text(if (deletable) "Lagerort löschen …" else "Löschen nicht möglich: laufende Scan-Sitzung")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && (kind != location.kind || name.trim() != location.name),
                    onClick = { rename(merge = false) },
                ) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        )
    }
}

private enum class Mode { EDIT, DELETE }

@Composable
private fun LocationNameDialog(
    title: String,
    initialKind: LocationKind,
    initialName: String,
    confirmLabel: String,
    onConfirm: (LocationKind, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { KindAndName(kind, name, onKind = { kind = it }, onName = { name = it }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(kind, name) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun KindAndName(kind: LocationKind, name: String, onKind: (LocationKind) -> Unit, onName: (String) -> Unit) {
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LocationKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, LocationKind.entries.size),
                ) { Text(k.label) }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            label = { Text(if (kind == LocationKind.DECK) "Deck-Ordner, z. B. vivi-ornitier" else "Name, z. B. Box 1") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
