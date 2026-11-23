package app.cardscanner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cardscanner.ui.CollectionScreen
import app.cardscanner.ui.CollectionViewModel
import app.cardscanner.ui.ManualSearchScreen
import app.cardscanner.ui.SearchViewModel

class MainActivity : ComponentActivity() {

    private lateinit var controller: SpikeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CardScannerApp
        controller = SpikeController(applicationContext, lifecycleScope)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val factory = viewModelFactory {
            initializer { CollectionViewModel(app.repository, app.scryfall) }
            initializer { SearchViewModel(app.repository, app.scryfall) }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot(
                    scan = { PermissionGate { SpikeScreen(controller, this@MainActivity) } },
                    collectionViewModel = viewModel(factory = factory),
                    searchViewModel = viewModel(factory = factory),
                )
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

private enum class Tab(val label: String) { SCAN("Scannen"), COLLECTION("Sammlung") }

@Composable
private fun AppRoot(
    scan: @Composable () -> Unit,
    collectionViewModel: CollectionViewModel,
    searchViewModel: SearchViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN) }
    var searching by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            if (!searching) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (t == Tab.SCAN) Icons.Filled.Search else Icons.Filled.List, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                searching -> ManualSearchScreen(searchViewModel, onClose = { searching = false })
                tab == Tab.SCAN -> scan()
                else -> CollectionScreen(collectionViewModel, onAddManually = { searching = true })
            }
        }
    }
}
