package dev.pokescan.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.pokescan.api.ApiCard
import dev.pokescan.api.marketPrice
import dev.pokescan.api.tcgplayerUrl

val CONDITIONS = listOf("Near Mint", "Lightly Played", "Moderately Played", "Heavily Played", "Damaged")

/** Created on first launch; cards saved before collections existed live here. */
const val DEFAULT_COLLECTION_ID = 1L
const val DEFAULT_COLLECTION_NAME = "My Collection"

@Entity(tableName = "collections")
data class CardCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "collection")
data class CollectionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val collectionId: Long = DEFAULT_COLLECTION_ID,
    val cardId: String,
    val name: String,
    val setName: String,
    val setId: String,
    val number: String,
    val printedTotal: Int?,
    val rarity: String?,
    /** Comma-separated, e.g. "Fire" or "Grass,Darkness"; null until fetched from TCGdex. */
    val types: String? = null,
    /** National Pokédex number; null for Trainers/Energy, or until fetched from TCGdex. */
    val dexNumber: Int? = null,
    val variant: String,
    val condition: String = CONDITIONS.first(),
    val quantity: Int = 1,
    val marketPrice: Double?,
    val tcgplayerUrl: String?,
    val imageUrl: String?,
    val addedAt: Long = System.currentTimeMillis(),
)

fun ApiCard.toEntry(variant: String, collectionId: Long) = CollectionEntry(
    collectionId = collectionId,
    cardId = id,
    name = name,
    setName = set.name,
    setId = set.id,
    number = number,
    printedTotal = set.printedTotal,
    rarity = rarity,
    types = types.joinToString(",").ifEmpty { null },
    dexNumber = dexNumber,
    variant = variant,
    marketPrice = marketPrice(variant),
    tcgplayerUrl = tcgplayerUrl(variant),
    imageUrl = images?.small,
)

/** Refreshes card data and prices, also re-pointing entries saved under an older card id. */
fun CollectionEntry.updatedFrom(card: ApiCard) = copy(
    cardId = card.id,
    name = card.name,
    setName = card.set.name,
    setId = card.set.id,
    number = card.number,
    printedTotal = card.set.printedTotal ?: printedTotal,
    rarity = card.rarity ?: rarity,
    types = card.types.joinToString(",").ifEmpty { types },
    dexNumber = card.dexNumber ?: dexNumber,
    marketPrice = card.marketPrice(variant),
    tcgplayerUrl = card.tcgplayerUrl(variant),
    imageUrl = card.images?.small ?: imageUrl,
)

val CollectionEntry.typeList: List<String>
    get() = types?.split(',')?.filter { it.isNotBlank() }.orEmpty()
