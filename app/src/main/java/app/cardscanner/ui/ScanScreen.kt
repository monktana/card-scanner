package app.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.cardscanner.data.CollectionEntry
import app.cardscanner.data.LocationKind
import app.cardscanner.data.StorageLocation
import androidx.compose.material3.LinearProgressIndicator
import app.cardscanner.scan.ScanController
import app.cardscanner.scan.ScanPhase
import app.cardscanner.scan.ScanSession

/** Reiter „Scannen“: Startbildschirm oder laufende Sitzung. */
@Composable
fun ScanTab(controller: ScanController, locations: List<StorageLocation>, owner: LifecycleOwner) {
    val state by controller.state.collectAsState()
    val session = state.session
    if (session == null) {
        SessionStartScreen(locations) { location, foil, replace -> controller.start(location, foil, replace) }
    } else {
        PermissionGate { ScanSessionScreen(controller, session, owner) }
    }
}

@Composable
private fun SessionStartScreen(
    locations: List<StorageLocation>,
    onStart: (LocationChoice, foil: Boolean, replaceDeck: Boolean) -> Unit,
) {
    var location by remember { mutableStateOf<LocationChoice>(LocationChoice.None) }
    var foil by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    val existingDeck = (location as? LocationChoice.Existing)?.location?.kind == LocationKind.DECK

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Neue Scan-Sitzung", style = MaterialTheme.typography.titleLarge)
        LocationField(location, locations) {
            location = it
            replace = false
        }
        if (existingDeck) {
            Text("Dieses Deck gibt es schon.", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Ergänzen", true to "Ersetzen").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = replace == value,
                        onClick = { replace = value },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            if (replace) {
                Text(
                    "Beim Beenden bleibt im Deck nur, was du jetzt scannst. Was vorher drin war und nicht " +
                        "mehr gescannt wird, wandert nach Bulk „Aus Decks“.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Foil-Modus")
                Text("Alles in dieser Sitzung gilt als Foil", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = foil, onCheckedChange = { foil = it })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onStart(location, foil, replace && existingDeck) }, modifier = Modifier.fillMaxWidth()) {
            Text("Scannen starten")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanSessionScreen(controller: ScanController, session: ScanSession, owner: LifecycleOwner) {
    val state by controller.state.collectAsState()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var diagnostics by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }

    val recentFlow = remember(session.recent) { controller.recentEntries(session.recent) }
    val recentEntries by recentFlow.collectAsState(emptyList())
    val byId = recentEntries.associateBy { it.id }
    val rows = session.recent.mapNotNull { byId[it] }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            CameraPreview(controller, owner) { camera = it }
            StatusBar(state.phase, Modifier.align(Alignment.TopCenter))
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF121212)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { diagnostics = !diagnostics }),
                ) {
                    Text(session.locationLabel, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (session.replaceDeck) "Ersetzen · " else "") + "${session.total} Karten in dieser Sitzung",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Button(onClick = { if (session.replaceDeck) confirmFinish = true else controller.finish() }) { Text("Beenden") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = session.foil, onClick = { controller.setFoil(!session.foil) }, label = { Text("Foil") })
                SpacerW(8.dp)
                FilterChip(
                    selected = torch,
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    label = { Text("Licht") },
                )
                SpacerW(8.dp)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setLinearZoom(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            state.message?.let { Text(it, color = Color(0xFFFFAB91), fontSize = 13.sp) }
            if (diagnostics) Diagnostics(controller)

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(rows, key = { it.id }) { entry ->
                    RecentRow(
                        entry = entry,
                        count = session.scanned[entry.id] ?: 0,
                        onPlus = { controller.increment(entry.id) },
                        onMinus = { controller.decrement(entry.id) },
                        onFoil = { controller.toggleFoil(entry) },
                        onDiscard = { controller.discard(entry.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Deck ersetzen?") },
            text = {
                Text("Im Deck bleiben nur die ${session.total} gescannten Karten. Alles andere aus dem Deck wandert nach Bulk „Aus Decks“.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    controller.finish()
                }) { Text("Ersetzen") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Weiter scannen") } },
        )
    }
}

/** Zeigt immer, was der Scanner gerade tut. */
@Composable
private fun StatusBar(phase: ScanPhase, modifier: Modifier = Modifier) {
    val (text, color) = when (phase) {
        ScanPhase.Waiting -> "Bereit – Karte auflegen" to Color(0xCC424242)
        is ScanPhase.Reading -> "Lese Karte …" to Color(0xCC1565C0)
        is ScanPhase.Checking -> "Erkannt: ${phase.key.setCode} ${phase.key.number} – prüfe …" to Color(0xCCEF6C00)
        is ScanPhase.Counted -> "✓ ${phase.name} gezählt – nächste Karte auflegen" to Color(0xCC2E7D32)
        ScanPhase.NotRecognized -> "Nicht erkannt – beiseitelegen" to Color(0xCCB71C1C)
        is ScanPhase.Problem -> phase.message to Color(0xCCB71C1C)
    }
    Column(modifier.fillMaxWidth().background(color)) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        when (phase) {
            is ScanPhase.Reading -> LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
            is ScanPhase.Counted -> Text(
                "Weiteres Exemplar derselben Karte: kurz wegnehmen und neu auflegen",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            else -> {}
        }
    }
}

@Composable
private fun Diagnostics(controller: ScanController) {
    val state by controller.state.collectAsState()
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp)) {
        val p = state.parsed
        Text(
            "Diagnose · OCR ${state.ocrMs} ms · gelesen: " + (p?.let { "${it.setCode} ${it.number} ${it.language ?: "?"}" } ?: "–"),
            fontSize = 12.sp, color = Color.LightGray,
        )
        OutlinedButton(onClick = controller::requestCapture) { Text("Nicht erkannt: Bild speichern") }
        LazyColumn(Modifier.heightIn(max = 120.dp)) {
            items(state.rawLines) { Text(it, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun RecentRow(
    entry: CollectionEntry,
    count: Int,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onFoil: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CardThumb(entry.imageUrl, entry.name, width = 40.dp)
        SpacerW(8.dp)
        Column(Modifier.weight(1f)) {
            Text(entry.printedName ?: entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.setCode.uppercase()} ${entry.collectorNumber} · ${entry.lang.uppercase()}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        FilterChip(selected = entry.foil, onClick = onFoil, label = { Text("Foil", fontSize = 12.sp) })
        TextButton(onClick = onMinus) { Text("−") }
        Text("$count")
        TextButton(onClick = onPlus) { Text("+") }
        TextButton(onClick = onDiscard) { Text("✕") }
    }
}

@Composable
private fun CameraPreview(controller: ScanController, owner: LifecycleOwner, onCamera: (Camera?) -> Unit) {
    val context = LocalContext.current
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    // Kleine Schrift unten links braucht Auflösung; 4:3 nutzt den ganzen Sensor.
                    val selector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(selector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(controller.analysisExecutor, controller.analyzer) }
                    provider.unbindAll()
                    onCamera(provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
    // Kamera freigeben, sobald die Sitzung endet oder der Reiter gewechselt wird.
    DisposableEffect(Unit) {
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            onCamera(null)
        }
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Die App braucht die Kamera, um Karten zu lesen.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Kamera erlauben") }
        }
    }
}
