package dev.pokescan

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pokescan.api.ApiCard
import dev.pokescan.api.TcgdexApi
import dev.pokescan.data.AppDatabase
import dev.pokescan.data.CONDITIONS
import dev.pokescan.data.CardCollection
import dev.pokescan.data.CollectionEntry
import dev.pokescan.data.CsvExporter
import dev.pokescan.data.CsvImporter
import dev.pokescan.data.DEFAULT_COLLECTION_ID
import dev.pokescan.data.toEntry
import dev.pokescan.data.updatedFrom
import dev.pokescan.scan.CardTextParser
import dev.pokescan.scan.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    /** [totalFound] exceeds [cards].size when the matches were capped. */
    data class Results(val query: String, val cards: List<ApiCard>, val totalFound: Int) : LookupState
    data class Error(val message: String) : LookupState
}

/** Frames in a row that must agree before auto-scan looks a card up. */
private const val STABLE_FRAMES = 3

private const val KEY_SELECTED_COLLECTION = "selected_collection"
private const val KEY_SORT_ORDER = "sort_order"
private const val KEY_CARD_DATA_VERSION = "card_data_version"

/**
 * Bump when saved cards gain a field that only a refresh from TCGdex can fill in
 * (2: types, 3: Pokédex numbers), so existing collections get refreshed once.
 */
private const val CARD_DATA_VERSION = 3

enum class SortOrder(val label: String) {
    RECENT("Recently added"),
    NAME("Name (A–Z)"),
    DEX("Pokédex number"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).collectionDao()
    private val api = TcgdexApi()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val collections: StateFlow<List<CardCollection>> = dao.observeCollections()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedCollectionId =
        MutableStateFlow(prefs.getLong(KEY_SELECTED_COLLECTION, DEFAULT_COLLECTION_ID))

    /** The selected collection, falling back to the first one if it was deleted. */
    val currentCollection: StateFlow<CardCollection?> =
        combine(collections, selectedCollectionId) { all, id -> all.firstOrNull { it.id == id } ?: all.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Cards in the current collection. */
    val entries: StateFlow<List<CollectionEntry>> = currentCollection
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeEntries(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cards in every collection, for searching across all of them. */
    val allEntries: StateFlow<List<CollectionEntry>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _live = MutableStateFlow(ScanResult.EMPTY)
    val live = _live.asStateFlow()

    private val _lookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookup = _lookup.asStateFlow()

    val autoScan = MutableStateFlow(false)

    private val _sortOrder = MutableStateFlow(
        SortOrder.entries.firstOrNull { it.name == prefs.getString(KEY_SORT_ORDER, null) } ?: SortOrder.RECENT
    )
    val sortOrder = _sortOrder.asStateFlow()

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        prefs.edit().putString(KEY_SORT_ORDER, order.name).apply()
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private var lookupJob: Job? = null
    private var lastKey: String? = null
    private var stableCount = 0
    private var lastLookupKey: String? = null

    init {
        viewModelScope.launch {
            val all = dao.getAll()
            // Cards saved before the switch to TCGdex link to the retired pokemontcg.io, and older
            // cards lack fields added since (see CARD_DATA_VERSION); one refresh fixes both.
            val legacyLinks = all.any { it.tcgplayerUrl?.contains("pokemontcg.io") == true }
            val outdated = prefs.getInt(KEY_CARD_DATA_VERSION, 1) < CARD_DATA_VERSION && all.isNotEmpty()
            if (legacyLinks || outdated) refreshPrices()
            prefs.edit().putInt(KEY_CARD_DATA_VERSION, CARD_DATA_VERSION).apply()
        }
    }

    /** Called on the main thread for every OCR'd camera frame. */
    fun onFrameParsed(result: ScanResult) {
        _live.value = result
        if (result.key == lastKey) stableCount++ else {
            lastKey = result.key
            stableCount = 1
        }
        val busy = _lookup.value is LookupState.Loading || _lookup.value is LookupState.Results
        // Auto-scan only on a collector number, since a name alone matches too many printings.
        // Basic Energy is the exception: it often has no number, and its lookup lists every printing.
        if (autoScan.value && !busy && (result.number != null || result.basicEnergy) &&
            stableCount >= STABLE_FRAMES && result.key != lastLookupKey
        ) {
            lookup(result)
        }
    }

    fun identifyNow() {
        _live.value.takeIf { it.isUseful }?.let(::lookup)
    }

    fun searchManual(query: String) {
        CardTextParser.parseQuery(query).takeIf { it.isUseful }?.let(::lookup)
    }

    private fun lookup(scan: ScanResult) {
        lastLookupKey = scan.key
        lookupJob?.cancel()
        _lookup.value = LookupState.Loading
        lookupJob = viewModelScope.launch {
            _lookup.value = try {
                val result = api.identify(scan)
                if (result.cards.isEmpty()) LookupState.Error("No match for ${scan.describe()}")
                else LookupState.Results(scan.describe(), result.cards, result.totalFound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LookupState.Error(e.message ?: "Lookup failed")
            }
        }
    }

    fun dismissResults() {
        _lookup.value = LookupState.Idle
    }

    fun addCard(card: ApiCard, variant: String) {
        _lookup.value = LookupState.Idle
        val collection = currentCollection.value ?: return
        viewModelScope.launch {
            dao.addCopies(card.toEntry(variant, collection.id))
            _messages.tryEmit("Added ${card.name} to ${collection.name}")
        }
    }

    fun selectCollection(collection: CardCollection) {
        selectedCollectionId.value = collection.id
        prefs.edit().putLong(KEY_SELECTED_COLLECTION, collection.id).apply()
    }

    fun createCollection(name: String) {
        viewModelScope.launch {
            val id = dao.insertCollection(CardCollection(name = name.trim()))
            selectCollection(CardCollection(id = id, name = name.trim()))
        }
    }

    fun renameCollection(collection: CardCollection, name: String) {
        viewModelScope.launch { dao.updateCollection(collection.copy(name = name.trim())) }
    }

    /** Deletes a collection and its cards; the last remaining collection can't be deleted. */
    fun deleteCollection(collection: CardCollection) {
        if (collections.value.size <= 1) return
        viewModelScope.launch {
            dao.deleteCollection(collection.id)
            _messages.tryEmit("Deleted ${collection.name}")
        }
    }

    fun moveEntry(entry: CollectionEntry, target: CardCollection, quantity: Int) {
        viewModelScope.launch {
            dao.move(entry, target.id, quantity)
            _messages.tryEmit("Moved ${quantity.coerceAtMost(entry.quantity)} × ${entry.name} to ${target.name}")
        }
    }

    fun setQuantity(entry: CollectionEntry, quantity: Int) {
        if (quantity < 1) return
        viewModelScope.launch { dao.update(entry.copy(quantity = quantity)) }
    }

    fun cycleCondition(entry: CollectionEntry) {
        val next = CONDITIONS[(CONDITIONS.indexOf(entry.condition) + 1) % CONDITIONS.size]
        viewModelScope.launch { dao.update(entry.copy(condition = next)) }
    }

    fun delete(entry: CollectionEntry) {
        viewModelScope.launch { dao.delete(entry) }
    }

    fun refreshPrices() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val allEntries = dao.getAll()
                val cards = api.getCards(allEntries.map { it.cardId }).associateBy { it.id }.toMutableMap()
                var unmatched = 0
                for (entry in allEntries) {
                    // Ids from the old pokemontcg.io database differ, so find those cards by name and number.
                    val card = cards[entry.cardId]
                        ?: api.findCard(entry.name, entry.number, entry.printedTotal, entry.setName)
                            ?.also { cards[entry.cardId] = it }
                    if (card == null) {
                        unmatched++
                        continue
                    }
                    dao.update(entry.updatedFrom(card))
                }
                _messages.tryEmit(
                    if (unmatched == 0) "Prices updated"
                    else "Prices updated; $unmatched card(s) couldn't be found"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Price refresh failed")
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Adds the cards from an exported CSV, recreating any collections it names (cards without a
     * collection go into the current one). Matching cards stack onto existing quantities.
     */
    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val cards = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use(CsvImporter::read)
                        ?: error("Couldn't open the file")
                }
                if (cards.isEmpty()) error("No cards found in the file")
                val fallback = currentCollection.value ?: error("No collection selected")
                val idsByName = collections.value.associate { it.name.lowercase() to it.id }.toMutableMap()
                val entries = cards.map { card ->
                    val name = card.collectionName
                    val id = if (name == null) fallback.id
                    else idsByName.getOrPut(name.lowercase()) { dao.insertCollection(CardCollection(name = name)) }
                    card.entry.copy(collectionId = id)
                }
                dao.addAll(entries)
                _messages.tryEmit("Imported ${entries.sumOf { it.quantity }} cards")
                // The CSV has no images, and its prices may be stale.
                refreshPrices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Import failed: ${e.message}")
            }
        }
    }

    /** Exports the current collection, or every collection when [allCollections] is set. */
    fun exportCsv(uri: Uri, allCollections: Boolean) {
        viewModelScope.launch {
            try {
                val rows = if (allCollections) dao.getAll()
                else dao.getEntries(currentCollection.value?.id ?: return@launch)
                val names = collections.value.associate { it.id to it.name }
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("Couldn't open the file")
                    stream.bufferedWriter().use { CsvExporter.write(rows, names, it) }
                }
                _messages.tryEmit("Exported ${rows.size} rows")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Export failed: ${e.message}")
            }
        }
    }
}
