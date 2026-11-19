package app.cardscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage

class MainActivity : ComponentActivity() {

    private lateinit var controller: SpikeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SpikeController(applicationContext, lifecycleScope)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    PermissionGate { SpikeScreen(controller, this@MainActivity) }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Die App braucht die Kamera, um Karten zu lesen.", color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Kamera erlauben") }
        }
    }
}

@Composable
private fun SpikeScreen(controller: SpikeController, owner: LifecycleOwner) {
    val state by controller.state.collectAsState()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var showRaw by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        bindCamera(ctx, owner, view, controller) { camera = it }
                    }
                },
            )
            Text(
                "OCR ${state.ocrMs} ms · Bild ${state.frames}",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(6.dp),
            )
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF121212)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = torch,
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    label = { Text("Licht") },
                )
                Spacer(Modifier.width(8.dp))
                Text("Zoom", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setLinearZoom(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            val p = state.parsed
            Text(
                if (p == null) "Gelesen: –" else "Gelesen: ${p.setCode} · ${p.number} · ${p.language ?: "?"}",
                color = if (p == null) Color.Gray else Color.White,
                fontWeight = FontWeight.Bold,
            )
            state.lookingUp?.let { Text("Scryfall: suche ${it.setCode} ${it.number} …", color = Color.Gray) }
            state.message?.let { Text(it, color = Color(0xFF80CBC4), fontSize = 12.sp) }

            Row(Modifier.padding(vertical = 6.dp)) {
                OutlinedButton(onClick = controller::requestCapture) { Text("Nicht erkannt: Bild speichern") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Treffer" else "Rohtext") }
            }

            Box(Modifier.fillMaxWidth().height(260.dp)) {
                if (showRaw) {
                    LazyColumn { items(state.rawLines) { Text(it, color = Color.LightGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
                } else {
                    LazyColumn { items(state.hits) { HitRow(it) } }
                }
            }
            Text("Treffer: ${state.hits.count { it.result is LookupResult.Found }} / ${state.hits.size}", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HitRow(hit: Hit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val r = hit.result) {
            is LookupResult.Found -> {
                AsyncImage(model = r.card.imageUrl, contentDescription = r.card.name, modifier = Modifier.size(width = 48.dp, height = 67.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(r.card.name, color = Color.White, fontWeight = FontWeight.Bold)
                    r.card.printedName?.let { Text(it, color = Color.LightGray, fontSize = 12.sp) }
                    Text(
                        "${r.card.setName} · ${r.card.setCode.uppercase()} ${r.card.collectorNumber} · ${r.card.lang.uppercase()} · ${hit.ocrToHitMs} ms",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                }
            }
            LookupResult.NotFound -> Text("Nicht bei Scryfall: ${hit.parsed.setCode} ${hit.parsed.number}", color = Color(0xFFFFAB91))
            is LookupResult.Failed -> Text("Fehler: ${r.message}", color = Color(0xFFEF9A9A))
        }
    }
}

private fun bindCamera(
    context: android.content.Context,
    owner: LifecycleOwner,
    view: PreviewView,
    controller: SpikeController,
    onBound: (Camera) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
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
        onBound(provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
    }, ContextCompat.getMainExecutor(context))
}
