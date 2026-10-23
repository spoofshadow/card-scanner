package dev.pokescan

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pokescan.ui.CollectionScreen
import dev.pokescan.ui.PokeScanTheme
import dev.pokescan.ui.ScanScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PokeScanTheme {
                PokeScanApp()
            }
        }
    }
}

@Composable
private fun PokeScanApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    BackHandler(enabled = scanning) { scanning = false }

    if (scanning) {
        ScanScreen(vm, onClose = { scanning = false })
    } else {
        CollectionScreen(vm, onScan = { scanning = true })
    }
}
