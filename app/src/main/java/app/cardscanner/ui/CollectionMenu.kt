package app.cardscanner.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Menü der Sammlung: Export über das Teilen-Menü, Import einer CSV. */
@Composable
fun CollectionMenu(viewModel: CollectionViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val pending by viewModel.pendingImport.collectAsState()

    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            if (text == null) viewModel.notice.value = "Datei konnte nicht gelesen werden." else viewModel.readImport(text)
        }
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Exportieren (teilen)") },
                onClick = {
                    open = false
                    scope.launch {
                        val files = viewModel.writeExport(File(context.cacheDir, "export"))
                        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "app.cardscanner.fileprovider", it) })
                        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "text/plain"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            // Leserechte für alle Dateien an die Ziel-App weitergeben.
                            clipData = ClipData.newUri(context.contentResolver, "sammlung", uris.first()).apply {
                                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                            }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Sammlung exportieren"))
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Importieren (CSV)") },
                onClick = {
                    open = false
                    pickCsv.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                },
            )
        }
    }

    pending?.let { p ->
        val cards = p.rows.sumOf { it.quantity }
        AlertDialog(
            onDismissRequest = { viewModel.pendingImport.value = null },
            title = { Text("CSV importieren") },
            text = {
                Text(
                    if (p.existingEntries == 0) {
                        "$cards Karten in ${p.rows.size} Einträgen werden importiert."
                    } else {
                        "$cards Karten in ${p.rows.size} Einträgen. Die Sammlung hat schon ${p.existingEntries} Einträge.\n\n" +
                            "Ersetzen: Die Sammlung wird gelöscht und durch die Datei ersetzt (Wiederherstellen).\n" +
                            "Ergänzen: Die Karten kommen zur Sammlung dazu."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport(replace = true) }) {
                    Text(if (p.existingEntries == 0) "Importieren" else "Ersetzen")
                }
            },
            dismissButton = {
                if (p.existingEntries > 0) {
                    TextButton(onClick = { viewModel.confirmImport(replace = false) }) { Text("Ergänzen") }
                } else {
                    TextButton(onClick = { viewModel.pendingImport.value = null }) { Text("Abbrechen") }
                }
            },
        )
    }
}
