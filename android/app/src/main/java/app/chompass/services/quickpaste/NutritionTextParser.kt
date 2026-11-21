package app.chompass.services.quickpaste

/**
 * Quick Paste Nutrition Entry — deterministic, on-device parser.
 *
 * Turns one line of free text such as
 * `牛肉饭55蛋白30碳水15膳食1500大卡45脂肪` into the five macro numbers plus a
 * food name. **No AI, no network, no API key** — this is a keyword + number
 * grammar, not a model call. It never invents a value: whatever the user did
 * not type stays `null`.
 *
 * Grammar, in words:
 *  * A nutrient is "keyword + number" (`蛋白30`, `protein 15g`) or
 *    "number + keyword" (`1500大卡`, `40g蛋白`) in any order, any spacing.
 *  * Chinese keywords, English keywords, common abbreviations and unit words
 *    are all accepted (`docs` aside, see [KEYWORDS]).
 *  * `kJ` / `千焦` are converted to kcal (1 kcal = 4.184 kJ). `kcal`, `大卡`,
 *    `千卡`, `卡路里`, `cal` are already kcal.
 *  * Duplicate fields with different numbers are a conflict, not a silent
 *    pick: the field is reported unknown and the UI asks the user to decide.
 *  * Whatever text the parser consumed is removed from the food name, so
 *    `3号牛肉饭 500大卡` keeps `3号牛肉饭` (the `3` is not a nutrient).
 *
 * The parser is deliberately a pure Kotlin object with no Android imports so
 * the whole grammar is unit-testable on the JVM.
 */
object NutritionTextParser {

    /** 1 kcal == 4.184 kJ (thermochemical calorie, the label convention). */
    const val KJ_PER_KCAL: Double = 4.184

    /**
     * Parse one pasted line. Never throws; unparseable input comes back as an
     * empty draft plus [QuickPasteIssue.NoNutritionFound].
     */
    fun parse(raw: String): QuickPasteParseResult {
        // Thousands separators ("1,500") are folded away before parsing; the
        // folded text is also what the name is cut from, so indices stay 1:1.
        val source = foldThousandsSeparators(raw)
        val norm = normalize(source)
        val n = norm.length

        val accepted = findKeywords(norm)
        val keywordStarts = accepted.withIndex().associate { (i, kw) -> kw.start to i }

        val consumed = BooleanArray(n)
        val usedNumbers = mutableListOf<IntRange>()
        val consumedKeywords = mutableSetOf<Int>()
        val values = LinkedHashMap<NutrientField, MutableList<Double>>()
        val issues = mutableListOf<QuickPasteIssue>()

        // P/C/F are only trusted when at least two of the trio appear: a lone
        // "P35" is far more likely a model code or a typo than protein.
        val singleLetterFields = accepted.filter { it.singleLetter }.map { it.field }.toSet()
        val singleLetterTrusted = singleLetterFields.size >= 2

        for ((index, kw) in accepted.withIndex()) {
            if (index in consumedKeywords) continue
            if (kw.singleLetter && !singleLetterTrusted) continue

            // Left first: Chinese nutrition text overwhelmingly writes
            // "number before unit/keyword" (`1500大卡`, `40蛋白`). The right
            // side is the fallback for "protein 15g" style input.
            val candidate = findLeft(kw, norm, consumed, usedNumbers)
                ?: findRight(kw, norm, usedNumbers, keywordStarts, index)
                ?: continue

            // Consume the span even when the unit is wrong: the user clearly
            // meant nutrition, so the digits must not leak into the name.
            for (i in candidate.spanStart until candidate.spanEnd) consumed[i] = true
            usedNumbers += candidate.numberStart until candidate.numberEnd
            if (candidate.unitKeywordIndex >= 0) consumedKeywords += candidate.unitKeywordIndex

            val unitFamily = candidate.unitFamily
            when (kw.field) {
                NutrientField.CALORIES -> when {
                    unitFamily == UnitFamily.MASS || unitFamily == UnitFamily.OTHER -> {
                        issues += QuickPasteIssue.UnsupportedUnit(kw.field, candidate.unitText ?: "")
                    }

                    else -> {
                        val energy = when {
                            unitFamily == UnitFamily.KJ -> UnitFamily.KJ
                            unitFamily == UnitFamily.KCAL -> UnitFamily.KCAL
                            else -> kw.impliedEnergy
                        }
                        val value = if (energy == UnitFamily.KJ) {
                            val kcal = candidate.value / KJ_PER_KCAL
                            issues += QuickPasteIssue.ConvertedFromKilojoules(candidate.value, kcal)
                            kcal
                        } else {
                            candidate.value
                        }
                        values.getOrPut(kw.field) { mutableListOf() } += value
                    }
                }

                else -> when (unitFamily) {
                    UnitFamily.KCAL, UnitFamily.KJ, UnitFamily.OTHER ->
                        issues += QuickPasteIssue.UnsupportedUnit(kw.field, candidate.unitText ?: "")

                    else -> values.getOrPut(kw.field) { mutableListOf() } += candidate.value
                }
            }
        }

        // Untrusted single-letter macros: strip them from the name, say why the
        // value was not filled, never guess.
        if (!singleLetterTrusted) {
            for (kw in accepted.filter { it.singleLetter }) {
                val candidate = findLeft(kw, norm, consumed, usedNumbers)
                    ?: findRight(kw, norm, usedNumbers, keywordStarts, accepted.indexOf(kw))
                    ?: continue
                for (i in candidate.spanStart until candidate.spanEnd) consumed[i] = true
                usedNumbers += candidate.numberStart until candidate.numberEnd
                issues += QuickPasteIssue.AmbiguousAbbreviation(source.substring(kw.start, kw.end))
            }
        }

        // Duplicate field: identical repeats are harmless, different numbers
        // are a contradiction the user has to resolve.
        val resolved = LinkedHashMap<NutrientField, Double>()
        for ((field, found) in values) {
            val distinct = found.distinct()
            if (distinct.size > 1) {
                issues += QuickPasteIssue.ConflictingValues(field, distinct.sorted())
            } else {
                resolved[field] = distinct.first()
            }
        }

        val foodName = extractName(source, consumed)

        val missingRequired = REQUIRED_FIELDS.filter { resolved[it] == null }
        if (resolved.isEmpty() && issues.none { it is QuickPasteIssue.UnsupportedUnit }) {
            issues += QuickPasteIssue.NoNutritionFound
        }
        if (resolved.isNotEmpty() && missingRequired.isNotEmpty()) {
            issues += QuickPasteIssue.MissingFields(missingRequired)
        }
        if (foodName.isBlank()) {
            issues += QuickPasteIssue.NoFoodName
        }

        return QuickPasteParseResult(
            foodName = foodName,
            caloriesKcal = resolved[NutrientField.CALORIES],
            proteinG = resolved[NutrientField.PROTEIN],
            carbsG = resolved[NutrientField.CARBS],
            fatG = resolved[NutrientField.FAT],
            fiberG = resolved[NutrientField.FIBER],
            issues = issues,
        )
    }

    // ---------------------------------------------------------------- grammar

    /** Calorie/protein/carbs/fat are non-null columns in [app.chompass.models.FoodEntry]. */
    val REQUIRED_FIELDS: List<NutrientField> = listOf(
        NutrientField.CALORIES,
        NutrientField.PROTEIN,
        NutrientField.CARBS,
        NutrientField.FAT,
    )

    private val KEYWORDS: List<KeywordSpec> = buildList {
        // Energy. `卡` is guarded against 卡布奇诺-style names in findKeywords.
        add(KeywordSpec(NutrientField.CALORIES, "kilocalories", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "kilocalorie", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "kcalories", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "kcal", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "calories", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "calorie", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "cal", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "千卡", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "大卡", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "卡路里", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "卡", UnitFamily.KCAL))
        add(KeywordSpec(NutrientField.CALORIES, "kilojoules", UnitFamily.KJ))
        add(KeywordSpec(NutrientField.CALORIES, "kilojoule", UnitFamily.KJ))
        add(KeywordSpec(NutrientField.CALORIES, "kj", UnitFamily.KJ))
        add(KeywordSpec(NutrientField.CALORIES, "千焦耳", UnitFamily.KJ))
        add(KeywordSpec(NutrientField.CALORIES, "千焦", UnitFamily.KJ))
        // Ambiguous energy words: unit comes from the explicit unit only.
        add(KeywordSpec(NutrientField.CALORIES, "热量", UnitFamily.NONE))
        add(KeywordSpec(NutrientField.CALORIES, "能量", UnitFamily.NONE))
        add(KeywordSpec(NutrientField.CALORIES, "energy", UnitFamily.NONE))
        // Protein.
        add(KeywordSpec(NutrientField.PROTEIN, "proteins", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.PROTEIN, "protein", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.PROTEIN, "prot", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.PROTEIN, "蛋白质", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.PROTEIN, "蛋白", UnitFamily.MASS))
        // Carbohydrates. Listed before fiber so 碳水/糖类 are never read as fiber.
        add(KeywordSpec(NutrientField.CARBS, "carbohydrates", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "carbohydrate", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "carbs", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "carb", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "碳水化合物", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "碳水", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.CARBS, "糖类", UnitFamily.MASS))
        // Fat.
        add(KeywordSpec(NutrientField.FAT, "fats", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FAT, "fat", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FAT, "脂肪", UnitFamily.MASS))
        // Fiber — must never be read as carbs.
        add(KeywordSpec(NutrientField.FIBER, "dietaryfibre", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "dietaryfiber", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "fibres", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "fibers", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "fibre", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "fiber", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "膳食纤维", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "食物纤维", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "纤维素", UnitFamily.MASS))
        add(KeywordSpec(NutrientField.FIBER, "纤维", UnitFamily.MASS))
        // Spec shorthand: `15膳食` in the documented example means fiber 15 g.
        add(KeywordSpec(NutrientField.FIBER, "膳食", UnitFamily.MASS))
        // Single-letter macros, gated by the "at least two of P/C/F" rule.
        add(KeywordSpec(NutrientField.PROTEIN, "p", singleLetter = true))
        add(KeywordSpec(NutrientField.CARBS, "c", singleLetter = true))
        add(KeywordSpec(NutrientField.FAT, "f", singleLetter = true))
    }

    private val KEYWORD_BY_TEXT: Map<String, KeywordSpec> = KEYWORDS.associateBy { it.text }

    /**
     * One alternation, longest keyword first, so `碳水化合物` wins over `碳水`
     * at the same offset and `calories` wins over `cal`. ASCII keywords get
     * letter boundaries: `fat` never matches inside `fatty`, `p` never inside
     * `protein`.
     */
    private val KEYWORD_REGEX: Regex = run {
        val alternatives = KEYWORDS
            .sortedByDescending { it.text.length }
            .map { spec ->
                val escaped = Regex.escape(spec.text)
                if (spec.text.all { it in 'a'..'z' }) "(?<![a-z])$escaped(?![a-z])" else escaped
            }
        Regex(alternatives.joinToString("|"))
    }

    private val MASS_UNITS = listOf("grams", "gram", "gr", "g", "公克", "克")
    private val KCAL_UNITS = listOf("kilocalories", "kcalories", "kcal", "calories", "calorie", "cal", "千卡", "大卡", "卡路里", "卡")
    private val KJ_UNITS = listOf("kilojoules", "kilojoule", "kj", "千焦耳", "千焦")
    private val OTHER_UNITS = listOf("milligrams", "mg", "毫克", "milliliters", "ml", "毫升", "oz", "盎司", "lbs", "lb", "pounds", "%")

    private fun findKeywords(norm: String): List<KeywordMatch> {
        val matches = mutableListOf<KeywordMatch>()
        for (m in KEYWORD_REGEX.findAll(norm)) {
            val spec = KEYWORD_BY_TEXT[m.value] ?: continue
            // 卡 inside 卡布奇诺 is a food name, not a unit.
            if (spec.text == "卡" && m.range.last + 1 < norm.length && isCjk(norm[m.range.last + 1])) continue
            matches += KeywordMatch(
                field = spec.field,
                start = m.range.first,
                end = m.range.last + 1,
                impliedEnergy = spec.impliedEnergy,
                singleLetter = spec.singleLetter,
            )
        }
        return matches
    }

    // -------------------------------------------------------------- candidate

    /**
     * `NUMBER [unit] KEYWORD`, e.g. `1500大卡`, `40g蛋白`, `500 卡`.
     * Scanning back stops at any already-consumed character, which is what
     * keeps `350kcal 蛋白质60` from stealing the 350 for protein.
     */
    private fun findLeft(
        kw: KeywordMatch,
        norm: String,
        consumed: BooleanArray,
        usedNumbers: List<IntRange>,
    ): Candidate? {
        var i = skipSeparatorsBackward(norm, kw.start - 1)
        if (i < 0 || consumed[i]) return null

        var unitText: String? = null
        var unitFamily = UnitFamily.NONE

        val backwardUnit = readUnitBackward(norm, i)
        if (backwardUnit != null) {
            unitText = backwardUnit.text
            unitFamily = backwardUnit.family
            i = skipSeparatorsBackward(norm, backwardUnit.start - 1)
            if (i < 0 || consumed[i]) return null
        }

        val number = readNumberBackward(norm, i) ?: return null
        if (overlapsUsed(number, usedNumbers)) return null

        return Candidate(
            value = number.value,
            unitText = unitText,
            unitFamily = unitFamily,
            numberStart = number.start,
            numberEnd = number.end,
            spanStart = number.start,
            spanEnd = kw.end,
            unitKeywordIndex = -1,
        )
    }

    /**
     * `KEYWORD NUMBER [unit]`, e.g. `protein 15g`, `热量800`.
     *
     * The `N + keyword` guard: when the number is *glued* to the next keyword
     * (`蛋白 500大卡`), the number belongs to that keyword, not to us — unless
     * our own number is glued to us (`蛋白500大卡` is genuinely ambiguous and
     * the tighter binding wins).
     */
    private fun findRight(
        kw: KeywordMatch,
        norm: String,
        usedNumbers: List<IntRange>,
        keywordStarts: Map<Int, Int>,
        keywordIndex: Int,
    ): Candidate? {
        var i = skipSeparators(norm, kw.end)

        // "蛋白质(g) 30" — a parenthesised unit before the number.
        var unitText: String? = null
        var unitFamily = UnitFamily.NONE
        if (i < norm.length && norm[i] == '(') {
            val close = norm.indexOf(')', i + 1)
            if (close > i + 1) {
                val inside = norm.substring(i + 1, close).trim()
                val family = unitFamilyOf(inside)
                if (family != UnitFamily.NONE) {
                    unitText = inside
                    unitFamily = family
                    i = skipSeparators(norm, close + 1)
                }
            }
        }

        val number = readNumberForward(norm, i) ?: return null
        if (overlapsUsed(number, usedNumbers)) return null

        var unitStart = -1
        var unitEnd = -1
        var unitKeywordIndex = -1
        val afterNumber = skipSpaces(norm, number.end)
        val forwardUnit = readUnitForward(norm, afterNumber)
        if (forwardUnit != null) {
            unitText = forwardUnit.text
            unitFamily = forwardUnit.family
            unitStart = afterNumber
            unitEnd = forwardUnit.end
        }

        val gluedToKeyword = number.start == kw.end
        if (!gluedToKeyword && unitText == null) {
            val nextKeyword = keywordStarts[number.end]
            if (nextKeyword != null && nextKeyword != keywordIndex) return null
        }

        // A unit that is itself a nutrient keyword ("30大卡") is claimed here so
        // the other keyword cannot also spend the same number.
        if (unitStart >= 0) {
            unitKeywordIndex = keywordStarts[unitStart] ?: -1
        }

        return Candidate(
            value = number.value,
            unitText = unitText,
            unitFamily = unitFamily,
            numberStart = number.start,
            numberEnd = number.end,
            spanStart = kw.start,
            spanEnd = maxOf(kw.end, if (unitEnd >= 0) unitEnd else number.end),
            unitKeywordIndex = unitKeywordIndex,
        )
    }

    private fun overlapsUsed(number: NumberSpan, used: List<IntRange>): Boolean =
        used.any { it.first < number.end && number.start < it.last + 1 }

    // ------------------------------------------------------------ name + text

    /**
     * Name = the input minus everything the parser consumed. Separator runs
     * collapse to a single space so `Milk  200 kcal` becomes `Milk`.
     */
    private fun extractName(source: String, consumed: BooleanArray): String {
        val sb = StringBuilder(source.length)
        var pendingSpace = false
        for (i in source.indices) {
            if (consumed[i]) continue
            val c = source[i]
            if (c.isWhitespace() || c in NAME_SEPARATORS) {
                if (sb.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun foldThousandsSeparators(raw: String): String =
        if (raw.contains(',')) raw.replace(THOUSANDS, "") else raw

    /**
     * Full-width digits/letters/punctuation to ASCII, ASCII to lowercase, one
     * char in, one char out — indices stay valid for [extractName].
     */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            val mapped = when {
                c in '０'..'９' -> '0' + (c - '０')
                c in 'Ａ'..'Ｚ' -> 'a' + (c - 'Ａ')
                c in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                c == '：' -> ':'
                c == '，' -> ','
                c == '（' -> '('
                c == '）' -> ')'
                c == '．' -> '.'
                c == '／' -> '/'
                c == '－' -> '-'
                c == '　' -> ' '
                else -> c.lowercaseChar()
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------- primitives

    private fun readNumberForward(text: String, from: Int): NumberSpan? {
        if (from >= text.length || !text[from].isDigit()) return null
        var i = from
        while (i < text.length && text[i].isDigit()) i++
        if (i + 1 < text.length && text[i] == '.' && text[i + 1].isDigit()) {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        val value = text.substring(from, i).toDoubleOrNull() ?: return null
        return NumberSpan(value, from, i)
    }

    private fun readNumberBackward(text: String, from: Int): NumberSpan? {
        if (from < 0 || !text[from].isDigit()) return null
        val end = from + 1
        var start = from
        while (start - 1 >= 0 && text[start - 1].isDigit()) start--
        // "30.5": step over the decimal point and keep scanning the integer
        // digits, so the backward read never collapses to ".5".
        if (start - 2 >= 0 && text[start - 1] == '.' && text[start - 2].isDigit()) {
            start -= 1
            while (start - 1 >= 0 && text[start - 1].isDigit()) start--
        }
        val value = text.substring(start, end).toDoubleOrNull() ?: return null
        return NumberSpan(value, start, end)
    }

    private fun readUnitBackward(text: String, end: Int): UnitMatch? {
        for (unit in ALL_UNITS) {
            val start = end - unit.length + 1
            if (start < 0) continue
            if (!text.startsWith(unit, start)) continue
            if (start + unit.length != end + 1) continue
            if (unit.all { it in 'a'..'z' } && start - 1 >= 0 && text[start - 1] in 'a'..'z') continue
            return UnitMatch(unit, unitFamilyOf(unit), start, end + 1)
        }
        return null
    }

    private fun readUnitForward(text: String, from: Int): UnitMatch? {
        for (unit in ALL_UNITS) {
            if (!text.startsWith(unit, from)) continue
            val end = from + unit.length
            if (unit.all { it in 'a'..'z' } && end < text.length && text[end] in 'a'..'z') continue
            return UnitMatch(unit, unitFamilyOf(unit), from, end)
        }
        return null
    }

    private fun unitFamilyOf(unit: String): UnitFamily = when {
        MASS_UNITS.contains(unit) -> UnitFamily.MASS
        KCAL_UNITS.contains(unit) -> UnitFamily.KCAL
        KJ_UNITS.contains(unit) -> UnitFamily.KJ
        OTHER_UNITS.contains(unit) -> UnitFamily.OTHER
        else -> UnitFamily.NONE
    }

    private fun skipSeparators(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i].isWhitespace() || text[i] in SEPARATORS)) i++
        return i
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipSeparatorsBackward(text: String, from: Int): Int {
        var i = from
        while (i >= 0 && (text[i].isWhitespace() || text[i] in SEPARATORS)) i--
        return i
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    // ------------------------------------------------------------- internals

    private class KeywordSpec(
        val field: NutrientField,
        val text: String,
        val impliedEnergy: UnitFamily = UnitFamily.NONE,
        val singleLetter: Boolean = false,
    )

    private class KeywordMatch(
        val field: NutrientField,
        val start: Int,
        val end: Int,
        val impliedEnergy: UnitFamily,
        val singleLetter: Boolean,
    )

    private class NumberSpan(val value: Double, val start: Int, val end: Int)

    private class UnitMatch(val text: String, val family: UnitFamily, val start: Int, val end: Int)

    private class Candidate(
        val value: Double,
        val unitText: String?,
        val unitFamily: UnitFamily,
        val numberStart: Int,
        val numberEnd: Int,
        val spanStart: Int,
        val spanEnd: Int,
        val unitKeywordIndex: Int,
    )

    private val ALL_UNITS: List<String> =
        (MASS_UNITS + KCAL_UNITS + KJ_UNITS + OTHER_UNITS).sortedByDescending { it.length }

    private val SEPARATORS: Set<Char> = setOf(
        ':', '=', ',', '，', '、', ';', '；', '(', ')', '（', '）', '[', ']', '【', '】',
        '-', '–', '—', '/', '|', '~', '＋', '+', '·', '.', '。', '!', '?', '！', '？',
    )

    private val NAME_SEPARATORS: Set<Char> = SEPARATORS + setOf('"', '\'', '“', '”', '‘', '’')

    private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
}

/** The five macro fields Quick Paste understands. */
enum class NutrientField { CALORIES, PROTEIN, CARBS, FAT, FIBER }

/** What went wrong (or was worth mentioning) during a parse. */
sealed interface QuickPasteIssue {
    /** Nothing in the text looked like a nutrient. */
    data object NoNutritionFound : QuickPasteIssue

    /** Numbers were found but no food name survived. */
    data object NoFoodName : QuickPasteIssue

    /** Required diary columns the text did not provide. */
    data class MissingFields(val fields: List<NutrientField>) : QuickPasteIssue

    /** The same field was given two different numbers. */
    data class ConflictingValues(val field: NutrientField, val values: List<Double>) : QuickPasteIssue

    /** A unit that cannot be applied to the field (e.g. `蛋白 30 kcal`). */
    data class UnsupportedUnit(val field: NutrientField, val unit: String) : QuickPasteIssue

    /** `kJ` was converted to kcal so the diary's kcal column stays correct. */
    data class ConvertedFromKilojoules(val originalKj: Double, val kcal: Double) : QuickPasteIssue

    /** A lone P/C/F abbreviation: recognised, deliberately not trusted. */
    data class AmbiguousAbbreviation(val token: String) : QuickPasteIssue
}

/** Parse outcome for one pasted line. `null` means "the user did not say". */
data class QuickPasteParseResult(
    val foodName: String,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val issues: List<QuickPasteIssue>,
) {
    fun valueOf(field: NutrientField): Double? = when (field) {
        NutrientField.CALORIES -> caloriesKcal
        NutrientField.PROTEIN -> proteinG
        NutrientField.CARBS -> carbsG
        NutrientField.FAT -> fatG
        NutrientField.FIBER -> fiberG
    }

    /** Required columns still unknown — the UI blocks Save until they are typed. */
    val missingRequired: List<NutrientField>
        get() = NutritionTextParser.REQUIRED_FIELDS.filter { valueOf(it) == null }

    val conflicts: List<QuickPasteIssue.ConflictingValues>
        get() = issues.filterIsInstance<QuickPasteIssue.ConflictingValues>()

    val unitErrors: List<QuickPasteIssue.UnsupportedUnit>
        get() = issues.filterIsInstance<QuickPasteIssue.UnsupportedUnit>()

    val hasConflictOrUnitError: Boolean
        get() = conflicts.isNotEmpty() || unitErrors.isNotEmpty()
}

private enum class UnitFamily { NONE, KCAL, KJ, MASS, OTHER }
