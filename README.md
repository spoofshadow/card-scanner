# TCG Card Scanner

Android app for scanning Pokémon cards into collections, pricing them with TCGplayer market
prices, and exporting them to CSV.

## How it works

1. **Scan**: the camera feed is OCR'd on-device (ML Kit). The app reads the card **name** (the
   largest text) and the **collector number** (e.g. `025/165`).
2. **Identify**: the number + set size (+ name) are looked up in [TCGdex](https://tcgdex.dev), a
   free, open-source card database (no API key) that carries each card's TCGplayer product ID and
   TCGplayer prices. TCG Pocket (mobile game) cards are filtered out.
3. **Confirm**: tap **Identify this card** (or type a search like `Charizard ex 199/165`), then tap
   a variant chip (Normal / Holo / Reverse Holo…) to add it to the current collection. Turn on
   **Auto-identify** to look cards up as soon as the same collector number is read 3 frames in a row.
4. **Collections**: tap the collection name at the top to switch, create, rename or delete
   collections. Each card's ⋮ menu can move some or all copies to another collection. Adjust
   quantity, tap the condition chip to cycle NM → LP → MP → HP → DMG, tap a card to open its
   tcgplayer.com product page, and refresh prices.
5. **CSV**: export this collection or all of them, or **Import from CSV** to restore an export
   (e.g. when moving to a new phone). Imports recreate the collections named in the file and add
   to existing quantities, so importing the same file twice doubles the counts.

CSV columns: Collection, Quantity, Name, Set, Set Code, Card Number, Set Total, Rarity, Variant,
Condition, Market Price (USD), Total Value (USD), TCGplayer URL, Card ID, Date Added.

The app originally used the Pokémon TCG API (pokemontcg.io), which is deprecated. Cards saved
back then are matched to TCGdex automatically the next time the app starts.

## Building

1. Install [Android Studio](https://developer.android.com/studio) (it brings the Android SDK and a
   compatible JDK).
2. **File → Open** this `PokeScan` folder and let Gradle sync.
3. Plug in your phone with USB debugging enabled and press **Run**.

## Scanning tips

- Good, even light; avoid glare on holo cards.
- Fill the on-screen frame with the card so the small collector number is legible.
- Promo and older cards without a `NNN/NNN` number: use the search box.
