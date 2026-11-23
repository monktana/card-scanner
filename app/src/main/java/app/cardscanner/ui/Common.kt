package app.cardscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cardscanner.data.LocationKind
import app.cardscanner.data.StorageLocation
import coil3.compose.AsyncImage

@Composable
fun CardThumb(url: String?, description: String, width: Dp = 48.dp) {
    AsyncImage(
        model = url,
        contentDescription = description,
        modifier = Modifier
            .size(width = width, height = width * 1.395f)
            .clip(RoundedCornerShape(width / 16)),
    )
}

@Composable
fun QuantityStepper(value: Int, onChange: (Int) -> Unit, min: Int = 0) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > min) { Text("−") }
        Text(
            "$value",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

/** Zeigt den gewählten Lagerort; ein Tipp öffnet die Auswahl. */
@Composable
fun LocationField(
    choice: LocationChoice,
    locations: List<StorageLocation>,
    onChange: (LocationChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 8.dp),
    ) {
        Text("Lagerort", style = MaterialTheme.typography.labelMedium)
        Text(choice.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
    if (open) {
        LocationDialog(
            current = choice,
            locations = locations,
            onPick = {
                onChange(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun LocationDialog(
    current: LocationChoice,
    locations: List<StorageLocation>,
    onPick: (LocationChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(LocationKind.BULK) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lagerort") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    val options = listOf<LocationChoice>(LocationChoice.None) + locations.map { LocationChoice.Existing(it) }
                    items(options) { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(option) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == current, onClick = { onPick(option) })
                            Text(option.label)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Neuer Lagerort", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LocationKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, LocationKind.entries.size),
                        ) { Text(k.label) }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(if (kind == LocationKind.DECK) "Deck-Ordner, z. B. vivi-ornitier" else "Name, z. B. Box 1") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onPick(LocationChoice.New(kind, name)) }) {
                Text("Neu anlegen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun LanguageChoice(lang: String, onChange: (String) -> Unit) {
    val options = listOf("en" to "EN", "de" to "DE")
    // Andere Sprachen (z. B. JA) bleiben wählbar angezeigt, wenn der Eintrag sie schon hat.
    val all = if (options.any { it.first == lang }) options else options + (lang to lang.uppercase())
    SingleChoiceSegmentedButtonRow {
        all.forEachIndexed { i, (code, label) ->
            SegmentedButton(
                selected = lang == code,
                onClick = { onChange(code) },
                shape = SegmentedButtonDefaults.itemShape(i, all.size),
            ) { Text(label) }
        }
    }
}

@Composable
fun SpacerW(width: Dp) = Spacer(Modifier.width(width))
