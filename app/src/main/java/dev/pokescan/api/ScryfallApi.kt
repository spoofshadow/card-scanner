package dev.pokescan.api

import android.os.SystemClock
import dev.pokescan.scan.ScanResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
private data class ScryfallCard(
    val id: String,
    val name: String,
    @SerialName("oracle_id") val oracleId: String? = null,
    val set: String,
    @SerialName("set_name") val setName: String,
    @SerialName("collector_number") val collectorNumber: String,
    val rarity: String? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("image_uris") val imageUris: ScryfallImages? = null,
    /** Double-faced and similar cards keep their images and type lines per face. */
    @SerialName("card_faces") val cardFaces: List<ScryfallFace> = emptyList(),
    /** "nonfoil", "foil" and/or "etched". */
    val finishes: List<String> = emptyList(),
    val prices: ScryfallPrices = ScryfallPrices(),
    @SerialName("tcgplayer_id") val tcgplayerId: Long? = null,
    @SerialName("tcgplayer_etched_id") val tcgplayerEtchedId: Long? = null,
)

@Serializable
private data class ScryfallFace(
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("image_uris") val imageUris: ScryfallImages? = null,
)

@Serializable
private data class ScryfallImages(val small: String? = null, val normal: String? = null)

/** TCGplayer market prices in USD, as strings. */
@Serializable
private data class ScryfallPrices(
    val usd: String? = null,
    @SerialName("usd_foil") val usdFoil: String? = null,
    @SerialName("usd_etched") val usdEtched: String? = null,
)

@Serializable
private data class ScryfallList(
    val data: List<ScryfallCard> = emptyList(),
    @SerialName("total_cards") val totalCards: Int? = null,
)

/**
 * Client for Scryfall (scryfall.com), the free Magic: The Gathering card database. It carries
 * TCGplayer prices and product IDs, and its fuzzy name search forgives OCR mistakes.
 * Scryfall asks clients to identify themselves and keep to about 10 requests a second.
 */
class ScryfallApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val rateGate = Mutex()
    private var lastRequestAt = 0L

    /**
     * The exact printing when the set code and collector number were read; otherwise the card
     * found by name, with all its paper printings (newest first) so the right one can be picked.
     */
    suspend fun identify(scan: ScanResult): IdentifyResult {
        val setCode = scan.setCode
        val number = scan.number
        if (setCode != null && number != null) {
            // One misread digit lands on a different card, so trust the printing only if its name
            // agrees with the title read off the card. (Typed "MOM 123" carries the code as its name.)
            getPrinting(setCode, number)
                ?.takeIf { scan.name == null || scan.name.equals(setCode, ignoreCase = true) || namesAgree(it.name, scan.name) }
                ?.let { return IdentifyResult(listOf(it.toApiCard()), 1) }
        }
        val name = scan.name ?: return IdentifyResult(emptyList(), 0)
        val named = getNamed(name)
        val query = when {
            named?.oracleId != null -> "oracleid:${named.oracleId}"
            named != null -> "!\"${named.name}\""
            // Fuzzy lookup failed (no match, or several): loose words search card names.
            else -> name
        }
        val prints = search("$query game:paper")
        // Printings matching the number or set read off the card first, then ones TCGplayer prices
        // (the newest are often unpriced promos), each group newest first.
        val ranked = prints.data.sortedWith(
            compareBy<ScryfallCard>({ card ->
                val numberMatch = number != null && card.collectorNumber.equals(number, ignoreCase = true)
                val setMatch = setCode != null && card.set.equals(setCode, ignoreCase = true)
                when {
                    numberMatch && setMatch -> 0
                    numberMatch -> 1
                    setMatch -> 2
                    else -> 3
                }
            }, { card -> with(card.prices) { usd == null && usdFoil == null && usdEtched == null } })
        )
        return IdentifyResult(ranked.take(MAX_MATCHES).map { it.toApiCard() }, prints.totalCards ?: prints.data.size)
    }

    /** Current data and prices for saved cards, by Scryfall id; unknown ids are skipped. */
    suspend fun getCards(ids: List<String>): List<ApiCard> = ids.distinct().chunked(COLLECTION_BATCH).flatMap { chunk ->
        val body = buildJsonObject {
            putJsonArray("identifiers") { chunk.forEach { id -> addJsonObject { put("id", id) } } }
        }.toString()
        val request = requestFor("$BASE_URL/cards/collection".toHttpUrl())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        fetch(request)?.let { json.decodeFromString<ScryfallList>(it).data.map { card -> card.toApiCard() } }.orEmpty()
    }

    private suspend fun getPrinting(setCode: String, number: String): ScryfallCard? {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("cards")
            .addPathSegment(setCode.lowercase())
            .addPathSegment(number)
            .build()
        return fetch(requestFor(url).build())?.let { json.decodeFromString<ScryfallCard>(it) }
    }

    private suspend fun getNamed(name: String): ScryfallCard? {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("cards/named")
            .addQueryParameter("fuzzy", name)
            .build()
        return fetch(requestFor(url).build())?.let { json.decodeFromString<ScryfallCard>(it) }
    }

    /** One page of results (up to 175 cards); Scryfall answers 404 when nothing matches. */
    private suspend fun search(query: String): ScryfallList {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("cards/search")
            .addQueryParameter("q", query)
            .addQueryParameter("unique", "prints")
            .addQueryParameter("order", "released")
            .addQueryParameter("dir", "desc")
            .build()
        return fetch(requestFor(url).build())?.let { json.decodeFromString<ScryfallList>(it) } ?: ScryfallList()
    }

    private fun requestFor(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")

    /** Spaces requests out as Scryfall asks, then runs them with the usual retries. */
    private suspend fun fetch(request: Request): String? {
        rateGate.withLock {
            val wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            lastRequestAt = SystemClock.elapsedRealtime()
        }
        return client.fetchWithRetry(request)
    }

    private companion object {
        const val BASE_URL = "https://api.scryfall.com"
        const val USER_AGENT = "TCGCardScanner/1.1 (Android)"
        const val MIN_REQUEST_INTERVAL_MS = 100L
        const val MAX_MATCHES = 40
        /** Scryfall's limit for one /cards/collection request. */
        const val COLLECTION_BATCH = 75
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Loose check that an OCR'd title belongs to a card: the first four letters match, ignoring case,
 * spaces and punctuation. Double-faced cards are compared by their front face.
 */
private fun namesAgree(cardName: String, readName: String): Boolean {
    fun letters(s: String) = s.lowercase().filter(Char::isLetter).take(4)
    val read = letters(readName)
    return read.length >= 3 && letters(cardName.substringBefore("//")).startsWith(read)
}

/** Main card types, as they appear before the dash in a type line ("Legendary Creature — Elf"). */
private val CARD_TYPES = setOf(
    "Artifact", "Battle", "Creature", "Enchantment", "Instant", "Kindred", "Land", "Planeswalker",
    "Sorcery", "Tribal",
)

private fun ScryfallCard.toApiCard(): ApiCard {
    val front = cardFaces.firstOrNull()
    val images = imageUris ?: front?.imageUris
    fun price(productId: Long?, usd: String?) =
        ApiPrice(productId = productId, low = null, mid = null, high = null, market = usd?.toDoubleOrNull(), directLow = null)
    // One variant per finish the card was printed in, priced or not.
    val variants = buildMap {
        val printed = finishes.ifEmpty { listOf("nonfoil") }
        if ("nonfoil" in printed) put("normal", price(tcgplayerId, prices.usd))
        if ("foil" in printed) put("foil", price(tcgplayerId, prices.usdFoil))
        if ("etched" in printed) put("etched", price(tcgplayerEtchedId ?: tcgplayerId, prices.usdEtched))
    }
    return ApiCard(
        game = Game.MTG,
        id = id,
        name = name,
        number = collectorNumber,
        rarity = rarity?.replaceFirstChar(Char::uppercaseChar),
        types = (typeLine ?: front?.typeLine).orEmpty()
            .substringBefore("//")
            .substringBefore("—")
            .split(' ')
            .filter { it in CARD_TYPES },
        dexNumber = null,
        set = ApiSet(id = set.uppercase(), name = setName, printedTotal = null),
        images = images?.let { ApiImages(small = it.small ?: it.normal, large = it.normal ?: it.small) },
        tcgplayer = ApiTcgplayer(variants),
    )
}
