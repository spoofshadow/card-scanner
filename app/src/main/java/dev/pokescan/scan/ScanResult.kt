package dev.pokescan.scan

/** What we could read off a card: its name and/or collector number (e.g. "25" of "025/165"). */
data class ScanResult(
    val name: String?,
    val number: String?,
    val printedTotal: Int?,
    /** True when [name] is a basic Energy ("Fire Energy"), which has dozens of printings. */
    val basicEnergy: Boolean = false,
) {
    val isUseful: Boolean get() = name != null || number != null

    /** Used to tell whether consecutive frames are seeing the same card. */
    val key: String get() = "${name?.lowercase()}|$number|$printedTotal"

    fun describe(): String = buildString {
        name?.let { append(it) }
        number?.let {
            if (isNotEmpty()) append(' ')
            append('#').append(it)
            printedTotal?.let { total -> append('/').append(total) }
        }
    }

    companion object {
        val EMPTY = ScanResult(null, null, null)
    }
}
