package app.chompass.services.quickpaste

/**
 * One confirmed Quick Paste row, ready for the normal diary save path.
 *
 * Calories are an `Int` and the macros a `Double` to match
 * [app.chompass.models.FoodEntry]'s non-null columns; fiber is nullable there
 * and stays nullable here. A missing number is never filled in by the parser —
 * the UI blocks Save until the user types it.
 */
data class QuickPasteDraft(
    val name: String,
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double?,
)
