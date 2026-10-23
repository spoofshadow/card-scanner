package dev.pokescan.api

import dev.pokescan.scan.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ApiCard(
    val id: String,
    val name: String,
    val number: String,
    val rarity: String?,
    /** "Fire", "Water", … for Pokémon; "Trainer" or "Energy" for those cards. */
    val types: List<String>,
    /** National Pokédex number (the first one for tag teams); null for Trainers and Energy. */
    val dexNumber: Int?,
    val set: ApiSet,
    val images: ApiImages?,
    val tcgplayer: ApiTcgplayer?,
)

data class ApiSet(val id: String, val name: String, val printedTotal: Int?)

data class ApiImages(val small: String?, val large: String?)

/** TCGplayer prices keyed by variant (normal, holofoil, reverseHolofoil, …). */
data class ApiTcgplayer(val prices: Map<String, ApiPrice>)

data class ApiPrice(
    val productId: Long?,
    val low: Double?,
    val mid: Double?,
    val high: Double?,
    val market: Double?,
    val directLow: Double?,
)

val ApiCard.displayNumber: String
    get() = set.printedTotal?.let { "$number/$it" } ?: number

fun ApiCard.marketPrice(variant: String): Double? =
    tcgplayer?.prices?.get(variant)?.let { it.market ?: it.mid }

/** Direct tcgplayer.com product page for the variant, falling back to any listed product. */
fun ApiCard.tcgplayerUrl(variant: String): String? {
    val prices = tcgplayer?.prices ?: return null
    val productId = prices[variant]?.productId ?: prices.values.firstNotNullOfOrNull { it.productId }
    return productId?.let { "https://www.tcgplayer.com/product/$it" }
}

/** Variants this card is sold as on TCGplayer, in a sensible display order. */
val ApiCard.variants: List<String>
    get() = tcgplayer?.prices?.keys
        ?.sortedBy { VARIANT_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .orEmpty()
        .ifEmpty { listOf("normal") }

private val VARIANT_ORDER = listOf(
    "normal", "holofoil", "reverseHolofoil",
    "1stEdition", "1stEditionNormal", "1stEditionHolofoil", "unlimited", "unlimitedHolofoil",
)

fun variantLabel(variant: String): String = when (variant) {
    "normal" -> "Normal"
    "holofoil" -> "Holo"
    "reverseHolofoil" -> "Reverse Holo"
    "1stEdition", "1stEditionNormal" -> "1st Edition"
    "1stEditionHolofoil" -> "1st Edition Holo"
    "unlimited" -> "Unlimited"
    "unlimitedHolofoil" -> "Unlimited Holo"
    else -> variant
}

/** Reverses [variantLabel], e.g. for labels read back from an exported CSV. */
fun variantFromLabel(label: String): String =
    VARIANT_ORDER.firstOrNull { variantLabel(it).equals(label, ignoreCase = true) }
        ?: label.ifBlank { "normal" }

@Serializable
private data class TcgdexBrief(val id: String)

@Serializable
private data class TcgdexCard(
    val id: String,
    val localId: String = "",
    val name: String,
    val rarity: String? = null,
    val category: String? = null,
    val types: List<String> = emptyList(),
    val dexId: List<Int> = emptyList(),
    val image: String? = null,
    val set: TcgdexSet,
    val pricing: TcgdexPricing? = null,
)

@Serializable
private data class TcgdexSet(val id: String, val name: String, val cardCount: TcgdexCardCount? = null)

@Serializable
private data class TcgdexCardCount(val official: Int? = null)

/** The tcgplayer object mixes "unit"/"updated" strings with one object per variant. */
@Serializable
private data class TcgdexPricing(val tcgplayer: JsonObject? = null)

@Serializable
private data class TcgdexSerie(val sets: List<TcgdexBrief> = emptyList())

/** Client for TCGdex (tcgdex.dev): free, keyless, and carries TCGplayer prices and product IDs. */
class TcgdexApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val detailPermits = Semaphore(MAX_PARALLEL_REQUESTS)
    @Volatile private var pocketSetIds: Set<String>? = null

    /** Returns the matches for the most specific search that finds any physical card. */
    suspend fun identify(scan: ScanResult): List<ApiCard> {
        for (filters in scan.filterSets()) {
            val cards = getCards(searchIds(filters).take(MAX_MATCHES))
            if (cards.isNotEmpty()) return cards
        }
        return emptyList()
    }

    /** Fetches full card details (with prices) in parallel; unknown ids are skipped. */
    suspend fun getCards(ids: List<String>): List<ApiCard> = coroutineScope {
        ids.distinct()
            .map { id -> async { detailPermits.withPermit { getCard(id) } } }
            .awaitAll()
            .filterNotNull()
    }

    suspend fun getCard(id: String): ApiCard? {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("cards").addPathSegment(id).build()
        val body = fetch(url) ?: return null
        return json.decodeFromString<TcgdexCard>(body).toApiCard()
    }

    /** Finds the TCGdex card for an entry saved under another database's id. */
    suspend fun findCard(name: String, number: String, printedTotal: Int?, setName: String): ApiCard? {
        val filters = buildMap {
            put("name", name)
            put("localId", numberFilter(number))
            printedTotal?.let { put("set.cardCount.official", "eq:$it") }
        }
        val cards = getCards(searchIds(filters).take(MAX_MATCHES))
            .filter { it.name.equals(name, ignoreCase = true) }
        return cards.singleOrNull() ?: cards.firstOrNull { it.set.name.equals(setName, ignoreCase = true) }
    }

    private suspend fun searchIds(filters: Map<String, String>): List<String> {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("cards")
            .apply { filters.forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("pagination:itemsPerPage", SEARCH_PAGE_SIZE.toString())
            .build()
        val body = fetch(url) ?: return emptyList()
        val pocket = pocketSets()
        return json.decodeFromString<List<TcgdexBrief>>(body)
            .map { it.id }
            .filterNot { it.substringBeforeLast('-') in pocket }
    }

    /** Set ids from TCG Pocket (the mobile game), which has no physical cards to collect. */
    private suspend fun pocketSets(): Set<String> {
        pocketSetIds?.let { return it }
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegment("series").addPathSegment("tcgp").build()
        val ids = fetch(url)?.let { body -> json.decodeFromString<TcgdexSerie>(body).sets.map { it.id }.toSet() }
        return ids.orEmpty().also { if (ids != null) pocketSetIds = it }
    }

    /** GETs the url, returning null on 404 and retrying server errors with backoff. */
    private suspend fun fetch(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        var lastError = IOException("Card lookup failed")
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay((RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(RETRY_MAX_MS))
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                continue
            }
            response.use {
                if (it.isSuccessful) return@withContext it.body!!.string()
                if (it.code == 404) return@withContext null
                val retryable = it.code >= 500 || it.code == 429
                lastError = IOException(
                    if (retryable) "The card database is having trouble (HTTP ${it.code}). Try again in a moment."
                    else "Card lookup failed (HTTP ${it.code})"
                )
                if (!retryable) throw lastError
            }
        }
        throw lastError
    }

    private companion object {
        const val BASE_URL = "https://api.tcgdex.net/v2/en"
        const val SEARCH_PAGE_SIZE = 50
        const val MAX_MATCHES = 12
        const val MAX_PARALLEL_REQUESTS = 6
        const val MAX_ATTEMPTS = 4
        const val RETRY_BASE_MS = 300L
        const val RETRY_MAX_MS = 2_000L
    }
}

/**
 * TCGdex searches to try, most specific first. OCR is imperfect, so later searches drop
 * whichever field is most likely to be misread. A bare `name` filter is a case-insensitive
 * substring match.
 */
private fun ScanResult.filterSets(): List<Map<String, String>> {
    val nameF = name?.let { "name" to it }
    val numberF = number?.let { "localId" to numberFilter(it) }
    val totalF = printedTotal?.let { "set.cardCount.official" to "eq:$it" }
    val firstWordF = name?.split(' ')?.firstOrNull()?.takeIf { it.length >= 3 }?.let { "name" to it }

    return listOfNotNull(
        listOfNotNull(nameF, numberF, totalF).takeIf { it.size == 3 },
        listOfNotNull(numberF, totalF).takeIf { it.size == 2 },
        listOfNotNull(nameF, numberF).takeIf { it.size == 2 },
        listOfNotNull(firstWordF, numberF).takeIf { it.size == 2 },
        listOfNotNull(nameF).takeIf { it.size == 1 },
        listOfNotNull(firstWordF).takeIf { it.size == 1 },
    ).map { it.toMap() }.distinct()
}

/**
 * Exact match on a collector number. Sets store it either padded ("025") or not ("25"), so
 * match both. TCGdex only does a string comparison when the filter lists several values,
 * hence "eq:199|199" rather than "eq:199".
 */
private fun numberFilter(number: String): String {
    val plain = if (number.all(Char::isDigit)) number.trimStart('0').ifEmpty { "0" } else number
    val padded = if (plain.all(Char::isDigit)) plain.padStart(3, '0') else plain
    return "eq:$plain|$padded"
}

private fun TcgdexCard.toApiCard() = ApiCard(
    id = id,
    name = name,
    number = localId,
    rarity = rarity,
    // Energy cards list their energy's type too; group them as Energy rather than as Fire, Water, etc.
    types = if (category == "Pokemon") types else listOfNotNull(category),
    dexNumber = dexId.firstOrNull(),
    set = ApiSet(id = set.id, name = set.name, printedTotal = set.cardCount?.official?.takeIf { it > 0 }),
    images = image?.let { ApiImages(small = "$it/low.webp", large = "$it/high.webp") },
    tcgplayer = pricing?.tcgplayer?.let { ApiTcgplayer(it.toPrices()) },
)

private fun JsonObject.toPrices(): Map<String, ApiPrice> = entries.mapNotNull { (key, value) ->
    val price = value as? JsonObject ?: return@mapNotNull null
    fun number(field: String) = price[field]?.jsonPrimitive?.doubleOrNull
    variantKey(key) to ApiPrice(
        productId = price["productId"]?.jsonPrimitive?.longOrNull,
        low = number("lowPrice"),
        mid = number("midPrice"),
        high = number("highPrice"),
        market = number("marketPrice"),
        directLow = number("directLowPrice"),
    )
}.toMap()

/** "reverse-holofoil" -> "reverseHolofoil", matching the variant keys already saved in collections. */
private fun variantKey(tcgdexKey: String): String {
    val parts = tcgdexKey.split('-')
    return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
