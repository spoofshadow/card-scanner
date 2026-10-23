package dev.pokescan.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.pokescan.LookupState
import dev.pokescan.MainViewModel
import dev.pokescan.api.ApiCard
import dev.pokescan.api.displayNumber
import dev.pokescan.api.marketPrice
import dev.pokescan.api.variantLabel
import dev.pokescan.api.variants
import dev.pokescan.scan.CameraPreview
import dev.pokescan.scan.CardTextParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val live by vm.live.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val autoScan by vm.autoScan.collectAsStateWithLifecycle()
    val collection by vm.currentCollection.collectAsStateWithLifecycle()
    var manualQuery by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(Modifier.fillMaxSize()) { vm.onFrameParsed(CardTextParser.parse(it)) }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 120.dp)
                    .fillMaxWidth(0.72f)
                    .aspectRatio(63f / 88f)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            )
        } else {
            Text(
                "Camera permission is needed to scan. You can still search by name below.",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text("Auto-identify", color = Color.White)
            Spacer(Modifier.width(8.dp))
            Switch(checked = autoScan, onCheckedChange = { vm.autoScan.value = it })
        }

        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                Modifier.navigationBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                collection?.let {
                    Text(
                        "Adding to ${it.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (live.isUseful) "Reading: ${live.describe()}" else "Fit the card inside the frame · tap to focus",
                    style = MaterialTheme.typography.titleSmall,
                )
                when (val state = lookup) {
                    LookupState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Looking up…")
                    }
                    is LookupState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualQuery,
                        onValueChange = { manualQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search, e.g. Pikachu 25/165") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchManual(manualQuery) }),
                    )
                    IconButton(onClick = { vm.searchManual(manualQuery) }, enabled = manualQuery.isNotBlank()) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
                Button(
                    onClick = vm::identifyNow,
                    enabled = live.isUseful && lookup != LookupState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Identify this card") }
            }
        }

        (lookup as? LookupState.Results)?.let { results ->
            ModalBottomSheet(onDismissRequest = vm::dismissResults) {
                MatchList(results, onAdd = vm::addCard)
            }
        }
    }
}

@Composable
private fun MatchList(results: LookupState.Results, onAdd: (ApiCard, String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "${results.cards.size} match${if (results.cards.size == 1) "" else "es"} for ${results.query}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Tap a variant to add it to your collection", style = MaterialTheme.typography.bodySmall)
        LazyColumn(
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(results.cards, key = { it.id }) { card -> MatchRow(card, onAdd) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchRow(card: ApiCard, onAdd: (ApiCard, String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = card.images?.small,
            contentDescription = card.name,
            modifier = Modifier.width(80.dp).aspectRatio(63f / 88f),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium)
            Text("${card.set.name} · ${card.displayNumber}", style = MaterialTheme.typography.bodySmall)
            card.rarity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.variants.forEach { variant ->
                    AssistChip(
                        onClick = { onAdd(card, variant) },
                        label = { Text("${variantLabel(variant)} ${formatPrice(card.marketPrice(variant))}") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}
