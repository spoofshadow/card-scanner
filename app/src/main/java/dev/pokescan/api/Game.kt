package dev.pokescan.api

/** The trading card games the app can scan. [id] is what's stored in the database and CSV files. */
enum class Game(val id: String, val label: String) {
    POKEMON("pokemon", "Pokémon"),
    MTG("mtg", "Magic"),
    ;

    companion object {
        /** Looks up a stored id or a CSV label ("Pokémon", "Pokemon", "Magic"); unknown means Pokémon. */
        fun from(value: String?): Game = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true) ||
                it.label.equals(value, ignoreCase = true) ||
                it.label.replace('é', 'e').equals(value, ignoreCase = true)
        } ?: POKEMON
    }
}
