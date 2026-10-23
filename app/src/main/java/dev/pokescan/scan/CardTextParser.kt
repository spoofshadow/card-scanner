package dev.pokescan.scan

import com.google.mlkit.vision.text.Text

/** Pulls a card name and collector number out of OCR text. */
object CardTextParser {

    // "025/165", "201/198", "TG05/TG30", "GG01/GG70". Letters glued to the number only belong to it
    // when the total has them too; otherwise OCR has merged the set/language code ("MEG EN122/086").
    private val numberRegex =
        Regex("""(?<![0-9])([A-Z]{1,3})?(\d{1,3})\s?/\s?([A-Z]{1,3})?(\d{2,3})(?![0-9])""")
    // A collector number possibly misread with O for 0 or I/l/| for 1, common on foil and gold cards.
    private val lookalikeNumber = Regex("""[0-9OoIl|]{1,3}\s?/\s?[0-9OoIl|]{2,3}""")
    private val hpRegex = Regex("""(HP\s*\d{2,3}|\d{2,3}\s*HP)""", RegexOption.IGNORE_CASE)
    private val stagePrefix = Regex("""^(BASIC|STAGE\s*[12]?)\s+""", RegexOption.IGNORE_CASE)

    // First words of lines that are never a card name.
    private val nonNameWords = setOf(
        "basic", "stage", "evolves", "hp", "trainer", "item", "supporter", "stadium", "tool",
        "energy", "pokemon", "pokémon", "weakness", "resistance", "retreat", "illus", "illus.",
        "ability", "rule", "put", "when", "if", "this", "your", "the", "flip", "discard",
        "draw", "search", "attach", "choose", "once", "during", "each", "heal", "does",
    )

    fun parse(text: Text): ScanResult {
        val lines = text.textBlocks.flatMap { it.lines }

        // The collector number sits near the bottom, so prefer the last match.
        val numberMatch = lines
            .mapNotNull { numberRegex.find(fixDigitLookalikes(it.text).uppercase()) }
            .lastOrNull()
        val (number, total) = numberMatch?.let(::normalize) ?: (null to null)

        // The card name is the largest text on the card that isn't HP or a header.
        val name = lines
            .mapNotNull { line -> cleanName(line.text)?.let { it to (line.boundingBox?.height() ?: 0) } }
            .maxByOrNull { it.second }
            ?.first

        return ScanResult(name, number, total)
    }

    /** Parses typed searches like "Pikachu", "Pikachu 25", "25/165" or "Charizard ex 199/165". */
    fun parseQuery(query: String): ScanResult {
        val trimmed = query.trim()
        numberRegex.find(trimmed.uppercase())?.let { match ->
            val (number, total) = normalize(match)
            // Keep letters typed against the number ("pikachu25/165") as part of the name.
            val numberStart = if (total == null) match.range.first else match.groups[2]!!.range.first
            val name = trimmed.removeRange(numberStart..match.range.last).trim().ifBlank { null }
            return ScanResult(name, number, total)
        }
        val tokens = trimmed.split(Regex("\\s+"))
        val last = tokens.last().removePrefix("#")
        if (tokens.size > 1 && last.length in 1..3 && last.all(Char::isDigit)) {
            return ScanResult(tokens.dropLast(1).joinToString(" "), stripZeros(last), null)
        }
        return ScanResult(trimmed.ifBlank { null }, null, null)
    }

    private fun normalize(match: MatchResult): Pair<String, Int?> {
        val (numberPrefix, numberDigits, totalPrefix, totalDigits) = match.destructured
        // Subset numbering like TG05/TG30: keep the prefix; the total isn't the set's card count.
        if (numberPrefix.isNotEmpty() && totalPrefix.isNotEmpty()) return "$numberPrefix$numberDigits" to null
        return stripZeros(numberDigits) to totalDigits.toInt().takeIf { it > 0 }
    }

    private fun fixDigitLookalikes(line: String): String = lookalikeNumber.replace(line) { match ->
        if (match.value.count(Char::isDigit) < 2) match.value
        else match.value.map {
            when (it) {
                'O', 'o' -> '0'
                'I', 'l', '|' -> '1'
                else -> it
            }
        }.joinToString("")
    }

    private fun stripZeros(digits: String) = digits.trimStart('0').ifEmpty { "0" }

    private fun cleanName(raw: String): String? {
        if ('/' in raw) return null
        val cleaned = hpRegex.replace(raw, " ")
            .replace(Regex("""\d+"""), " ")
            .replace(Regex("""[^\p{L}'.:\- &]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(stagePrefix, "")
        if (cleaned.length !in 3..28) return null
        val words = cleaned.split(' ')
        if (words.size > 4) return null
        if (words.first().lowercase() in nonNameWords) return null
        if (cleaned.count(Char::isLetter) < 3) return null
        return cleaned
    }
}
