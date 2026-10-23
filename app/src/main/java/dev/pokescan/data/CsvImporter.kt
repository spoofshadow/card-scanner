package dev.pokescan.data

import dev.pokescan.api.variantFromLabel
import java.io.Reader
import java.time.LocalDate
import java.time.ZoneId

/** A card read from a CSV, with the name of the collection it belongs to (if the file has one). */
data class ImportedCard(val collectionName: String?, val entry: CollectionEntry)

/** Reads files written by [CsvExporter], including exports from before collections existed. */
object CsvImporter {

    fun read(reader: Reader): List<ImportedCard> {
        val rows = parse(reader.readText())
        val header = rows.firstOrNull()?.map { it.trim().removePrefix("﻿") }
            ?: error("The file is empty")
        val columns = header.withIndex().associate { (i, name) -> name.lowercase() to i }
        if ("name" !in columns || "card id" !in columns) {
            error("This doesn't look like a PokeCard Scan export (no Name or Card ID column)")
        }

        return rows.drop(1).mapNotNull { row ->
            fun get(column: String) = columns[column.lowercase()]?.let { row.getOrNull(it) }?.trim().orEmpty()

            val cardId = get("Card ID")
            val name = get("Name")
            if (cardId.isEmpty() || name.isEmpty()) return@mapNotNull null

            ImportedCard(
                collectionName = get("Collection").ifBlank { null },
                entry = CollectionEntry(
                    cardId = cardId,
                    name = name,
                    setName = get("Set"),
                    setId = get("Set Code"),
                    number = get("Card Number"),
                    printedTotal = get("Set Total").toIntOrNull(),
                    rarity = get("Rarity").ifBlank { null },
                    types = get("Type").split('/').map(String::trim).filter(String::isNotEmpty)
                        .joinToString(",").ifEmpty { null },
                    dexNumber = get("Dex Number").toIntOrNull(),
                    variant = variantFromLabel(get("Variant")),
                    condition = get("Condition").takeIf { it in CONDITIONS } ?: CONDITIONS.first(),
                    quantity = get("Quantity").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    marketPrice = get("Market Price (USD)").toDoubleOrNull(),
                    tcgplayerUrl = get("TCGplayer URL").ifBlank { null },
                    imageUrl = null,
                    addedAt = parseDate(get("Date Added")) ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun parseDate(value: String): Long? = runCatching {
        LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** Minimal RFC 4180 parser: quoted fields, doubled quotes, and newlines inside quotes. */
    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (text.getOrNull(i + 1) == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString())
                        field.clear()
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.clear()
                        rows.add(row)
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
