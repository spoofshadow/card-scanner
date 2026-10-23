package dev.pokescan.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.pokescan.MainViewModel
import dev.pokescan.SortOrder
import dev.pokescan.api.variantLabel
import dev.pokescan.data.CardCollection
import dev.pokescan.data.CollectionEntry
import dev.pokescan.data.typeList
import java.time.LocalDate

private sealed interface CollectionDialog {
    data object New : CollectionDialog
    data class Rename(val collection: CardCollection) : CollectionDialog
    data class Delete(val collection: CardCollection) : CollectionDialog
    data class Move(val entry: CollectionEntry) : CollectionDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(vm: MainViewModel, onScan: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val current by vm.currentCollection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }
    var exportAll by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportCsv(it, exportAll) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importCsv) }

    val allEntries by vm.allEntries.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var rarityFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val searchAllActive = searching && searchAll && collections.size > 1
    val source = if (searchAllActive) allEntries else entries
    // Offer only the types and rarities present; Trainer and Energy after the Pokémon types.
    val typeOptions = source.flatMap { it.typeList }.distinct()
        .sortedWith(compareBy({ it == "Trainer" || it == "Energy" }, { it }))
    val rarityOptions = source.mapNotNull { it.rarity }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val filtering = searching && (query.isNotBlank() || typeFilter != null || rarityFilter != null)
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val filtered = if (!filtering) source else source.filter { entry ->
        val q = query.trim()
        (entry.name.contains(q, ignoreCase = true) || entry.setName.contains(q, ignoreCase = true)) &&
            (typeFilter == null || typeFilter in entry.typeList) &&
            (rarityFilter == null || entry.rarity.equals(rarityFilter, ignoreCase = true))
    }
    val shown = sorted(filtered, sortOrder)
    val collectionNames = collections.associate { it.id to it.name }
    val closeSearch = {
        searching = false
        query = ""
        typeFilter = null
        rarityFilter = null
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val collectionCards = entries.sumOf { it.quantity }
    val totalCards = shown.sumOf { it.quantity }
    val totalValue = shown.sumOf { (it.marketPrice ?: 0.0) * it.quantity }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    CollectionPicker(
                        current = current,
                        collections = collections,
                        onSelect = vm::selectCollection,
                        onNew = { dialog = CollectionDialog.New },
                        onRename = { current?.let { dialog = CollectionDialog.Rename(it) } },
                        onDelete = { current?.let { dialog = CollectionDialog.Delete(it) } },
                    )
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) {
                            closeSearch()
                        } else {
                            searching = true
                        }
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search cards",
                        )
                    }
                    IconButton(onClick = vm::refreshPrices, enabled = !refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh prices")
                    }
                    CsvMenu(
                        hasEntries = entries.isNotEmpty(),
                        onImport = { importLauncher.launch(CSV_MIME_TYPES) },
                        onExportCurrent = {
                            exportAll = false
                            exportLauncher.launch("${fileName(current?.name)}-${LocalDate.now()}.csv")
                        },
                        onExportAll = {
                            exportAll = true
                            exportLauncher.launch("tcg-card-scanner-all-${LocalDate.now()}.csv")
                        },
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Scan cards") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) {
                CardSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    showAllToggle = collections.size > 1,
                    searchAll = searchAll,
                    onSearchAllChange = { searchAll = it },
                    typeFilter = typeFilter,
                    typeOptions = typeOptions,
                    onTypeChange = { typeFilter = it },
                    rarityFilter = rarityFilter,
                    rarityOptions = rarityOptions,
                    onRarityChange = { rarityFilter = it },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (filtering) "${shown.size} match${if (shown.size == 1) "" else "es"} · $totalCards cards"
                        else "$totalCards cards · ${shown.size} unique",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Market value ${formatPrice(totalValue)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                SortMenu(sortOrder, onSelect = vm::setSortOrder)
            }
            HorizontalDivider()

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (filtering) "No cards match your search."
                        else "No cards in ${current?.name ?: "this collection"} yet. " +
                            "Tap Scan cards and point your camera at a card.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            collectionName = if (searchAllActive) collectionNames[entry.collectionId] else null,
                            canMove = collections.size > 1,
                            onOpen = {
                                entry.tcgplayerUrl?.let {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                }
                            },
                            onQuantity = { vm.setQuantity(entry, it) },
                            onCondition = { vm.cycleCondition(entry) },
                            onMove = { dialog = CollectionDialog.Move(entry) },
                            onDelete = { vm.delete(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        CollectionDialog.New -> NameDialog(
            title = "New collection",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.createCollection(it)
                dialog = null
            },
        )
        is CollectionDialog.Rename -> NameDialog(
            title = "Rename collection",
            initial = d.collection.name,
            confirmLabel = "Rename",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.renameCollection(d.collection, it)
                dialog = null
            },
        )
        is CollectionDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete “${d.collection.name}”?") },
            text = { Text("This removes the collection and the $collectionCards cards in it. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCollection(d.collection)
                    dialog = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is CollectionDialog.Move -> MoveDialog(
            entry = d.entry,
            targets = collections.filter { it.id != d.entry.collectionId },
            onDismiss = { dialog = null },
            onMove = { target, quantity ->
                vm.moveEntry(d.entry, target, quantity)
                dialog = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun CollectionPicker(
    current: CardCollection?,
    collections: List<CardCollection>,
    onSelect: (CardCollection) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable { expanded = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current?.name ?: "Collection",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch collection")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            collections.forEach { collection ->
                DropdownMenuItem(
                    text = { Text(collection.name) },
                    leadingIcon = {
                        if (collection.id == current?.id) Icon(Icons.Default.Check, contentDescription = "Current")
                    },
                    onClick = {
                        expanded = false
                        onSelect(collection)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New collection") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
            DropdownMenuItem(
                text = { Text("Rename collection") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                enabled = current != null,
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete collection") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                enabled = collections.size > 1,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** File types file pickers may report for a .csv, depending on the app that saved it. */
private val CSV_MIME_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "text/plain",
    "application/vnd.ms-excel", "application/octet-stream",
)

@Composable
private fun CsvMenu(
    hasEntries: Boolean,
    onImport: () -> Unit,
    onExportCurrent: () -> Unit,
    onExportAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("CSV") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Import from CSV…") },
                onClick = {
                    expanded = false
                    onImport()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Export this collection") },
                enabled = hasEntries,
                onClick = {
                    expanded = false
                    onExportCurrent()
                },
            )
            DropdownMenuItem(
                text = { Text("Export all collections") },
                onClick = {
                    expanded = false
                    onExportAll()
                },
            )
        }
    }
}

@Composable
private fun CardSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    showAllToggle: Boolean,
    searchAll: Boolean,
    onSearchAllChange: (Boolean) -> Unit,
    typeFilter: String?,
    typeOptions: List<String>,
    onTypeChange: (String?) -> Unit,
    rarityFilter: String?,
    rarityOptions: List<String>,
    onRarityChange: (String?) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text("Pokémon or set name") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showAllToggle) {
                FilterChip(
                    selected = searchAll,
                    onClick = { onSearchAllChange(!searchAll) },
                    label = { Text("All collections") },
                    leadingIcon = if (searchAll) {
                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
            FilterDropdownChip("Type", typeFilter, typeOptions, onTypeChange)
            FilterDropdownChip("Rarity", rarityFilter, rarityOptions, onRarityChange)
        }
    }
}

@Composable
private fun SortMenu(current: SortOrder, onSelect: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Change sort order")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    leadingIcon = {
                        if (order == current) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                )
            }
        }
    }
}

/**
 * Orders cards for display. Lists arrive newest first, so [SortOrder.RECENT] keeps them as is.
 * Pokédex order puts Trainers and Energy (no number) last; ties fall back to name, then set.
 */
private fun sorted(entries: List<CollectionEntry>, order: SortOrder): List<CollectionEntry> {
    val byName = compareBy<CollectionEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.setName }
    return when (order) {
        SortOrder.RECENT -> entries
        SortOrder.NAME -> entries.sortedWith(byName)
        SortOrder.DEX -> entries.sortedWith(
            compareBy<CollectionEntry>({ it.dexNumber == null }, { it.dexNumber }).then(byName)
        )
    }
}

/** A chip that opens a menu of [options]; shows the chosen option, or [label] when none is set. */
@Composable
private fun FilterDropdownChip(
    label: String,
    selected: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            enabled = options.isNotEmpty() || selected != null,
            label = { Text(selected ?: label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Any ${label.lowercase()}") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    leadingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: CollectionEntry,
    collectionName: String?,
    canMove: Boolean,
    onOpen: () -> Unit,
    onQuantity: (Int) -> Unit,
    onCondition: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.tcgplayerUrl != null, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.imageUrl,
            contentDescription = entry.name,
            modifier = Modifier.width(56.dp).aspectRatio(63f / 88f),
        )
        Column(Modifier.weight(1f)) {
            collectionName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            val number = entry.printedTotal?.let { "${entry.number}/$it" } ?: entry.number
            Text("${entry.setName} · $number", style = MaterialTheme.typography.bodySmall)
            Text(
                (listOfNotNull(entry.typeList.joinToString("/").ifEmpty { null }, variantLabel(entry.variant)) +
                    formatPrice(entry.marketPrice)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            AssistChip(onClick = onCondition, label = { Text(entry.condition) })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onQuantity(entry.quantity - 1) }, enabled = entry.quantity > 1) { Text("−") }
                Text("${entry.quantity}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onQuantity(entry.quantity + 1) }) { Text("+") }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options for ${entry.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (canMove) "Move to collection…" else "Move (create another collection first)") },
                        enabled = canMove,
                        onClick = {
                            menuOpen = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveDialog(
    entry: CollectionEntry,
    targets: List<CardCollection>,
    onDismiss: () -> Unit,
    onMove: (CardCollection, Int) -> Unit,
) {
    var target by remember { mutableStateOf(targets.firstOrNull()) }
    var quantity by remember { mutableIntStateOf(entry.quantity) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${entry.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                targets.forEach { collection ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = collection.id == target?.id,
                                onClick = { target = collection },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = collection.id == target?.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(collection.name)
                    }
                }
                if (entry.quantity > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("How many", Modifier.weight(1f))
                        TextButton(onClick = { quantity-- }, enabled = quantity > 1) { Text("−") }
                        Text("$quantity of ${entry.quantity}")
                        TextButton(onClick = { quantity++ }, enabled = quantity < entry.quantity) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let { onMove(it, quantity) } }, enabled = target != null) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fileName(collectionName: String?): String =
    collectionName.orEmpty()
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")
        .lowercase()
        .ifBlank { "collection" }
