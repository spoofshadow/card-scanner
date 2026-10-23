package dev.pokescan.data

import dev.pokescan.api.variantLabel
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object CsvExporter {
    private val header = listOf(
        "Collection", "Quantity", "Name", "Set", "Set Code", "Card Number", "Set Total", "Rarity",
        "Type", "Dex Number", "Variant", "Condition", "Market Price (USD)", "Total Value (USD)", "TCGplayer URL", "Card ID",
        "Date Added",
    )

    fun write(entries: List<CollectionEntry>, collectionNames: Map<Long, String>, out: Writer) {
        out.appendLine(header.joinToString(","))
        for (e in entries) {
            val row = listOf(
                collectionNames[e.collectionId].orEmpty(),
                e.quantity.toString(),
                e.name,
                e.setName,
                e.setId,
                e.number,
                e.printedTotal?.toString().orEmpty(),
                e.rarity.orEmpty(),
                e.typeList.joinToString("/"),
                e.dexNumber?.toString().orEmpty(),
                variantLabel(e.variant),
                e.condition,
                e.marketPrice?.let(::money).orEmpty(),
                e.marketPrice?.let { money(it * e.quantity) }.orEmpty(),
                e.tcgplayerUrl.orEmpty(),
                e.cardId,
                Instant.ofEpochMilli(e.addedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            )
            out.appendLine(row.joinToString(",", transform = ::escape))
        }
    }

    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
