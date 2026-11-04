package dev.pokescan.scan

import com.google.mlkit.vision.text.Text

/**
 * Pulls a card name, collector number and set code out of OCR text from a Magic: The Gathering card.
 * The name is the title line at the top; the bottom-left corner reads "0123/0281 R" on 2015–2022
 * cards, "0123 R" on newer ones, with the set code and language beside it ("MOM • EN").
 */
object MtgTextParser {

    private val numberWithTotal = Regex("""(?<![0-9])(\d{1,4})\s?/\s?(\d{2,4})(?![0-9])""")
    // Newer cards print just the number, next to a rarity letter. Requiring the letter keeps the
    // copyright year and power/toughness from matching.
    private val numberWithRarity =
        Regex("""(?<![0-9A-Z])(?:[CURMSLTP]\s+(\d{3,4})|(\d{3,4})\s+[CURMSLTP])(?![0-9A-Z])""")
    private val setCodeBeforeLanguage = Regex("""(?<![A-Z0-9])([A-Z0-9]{3,5})\s*[•·*.\-]?\s*EN(?![A-Z])""")
    // Mana symbols at the end of the title line, which OCR reads as stray letters ("Lightning Bolt R").
    private val trailingMana = Regex("""(\s+[WUBRGCX]){1,6}$""")

    // Typed searches.
    private val setAndNumberQuery = Regex("""^([A-Za-z0-9]{3,5})\s+#?(\d{1,4}[a-z]?)$""")
    private val trailingNumberQuery = Regex("""^(.+?)\s+#?(\d{1,4})(?:\s?/\s?\d{2,4})?$""")

    fun parse(text: Text): ScanResult {
        val lines = text.textBlocks.flatMap { it.lines }
        val upperLines = lines.map { it.text.uppercase() }

        val number = upperLines.firstNotNullOfOrNull { line ->
            numberWithTotal.find(line)?.groupValues?.get(1)
                ?: numberWithRarity.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }?.let(::stripZeros)
        val setCode = upperLines.firstNotNullOfOrNull { line ->
            setCodeBeforeLanguage.find(line)?.groupValues?.get(1)?.takeUnless { code -> code.all(Char::isDigit) }
        }

        // The title is the top-most line that looks like a name.
        val name = lines
            .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
            .firstNotNullOfOrNull { cleanName(it.text) }

        return ScanResult(name, number, null, setCode = setCode)
    }

    /** Parses typed searches like "Lightning Bolt", "MOM 123" or "Lightning Bolt 141". */
    fun parseQuery(query: String): ScanResult {
        val trimmed = query.trim()
        setAndNumberQuery.matchEntire(trimmed)?.let { match ->
            val (code, number) = match.destructured
            // A word plus a number could also be a short name ("Bolt 141"), so keep it as the name
            // too: if no such set exists, the lookup falls back to searching by name.
            return ScanResult(code, stripZeros(number), null, setCode = code.uppercase())
        }
        trailingNumberQuery.matchEntire(trimmed)?.let { match ->
            val (name, number) = match.destructured
            return ScanResult(name.trim(), stripZeros(number), null)
        }
        return ScanResult(trimmed.ifBlank { null }, null, null)
    }

    private fun stripZeros(number: String) =
        if (number.all(Char::isDigit)) number.trimStart('0').ifEmpty { "0" } else number

    private fun cleanName(raw: String): String? {
        val cleaned = raw
            .replace(Regex("""[{}()\d]"""), " ")
            .replace(Regex("""[^\p{L}',\- ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(trailingMana, "")
        if (cleaned.length !in 3..40) return null
        if (cleaned.split(' ').size > 7) return null
        if (cleaned.count(Char::isLetter) < 3) return null
        return cleaned
    }
}
