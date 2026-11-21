package app.chompass.services.quickpaste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grammar coverage for the deterministic Quick Paste parser. Every case here is
 * a real shape a user can paste: the five documented examples first, then the
 * awkward ones (missing fields, duplicates, bad units, digits inside names).
 */
class NutritionTextParserTest {

    private fun assertClose(expected: Double?, actual: Double?, delta: Double = 0.01) {
        if (expected == null) {
            assertNull("expected no value, got $actual", actual)
        } else {
            assertEquals(expected, actual ?: Double.NaN, delta)
        }
    }

    private fun assertAll(
        result: QuickPasteParseResult,
        name: String? = null,
        kcal: Double? = null,
        protein: Double? = null,
        carbs: Double? = null,
        fat: Double? = null,
        fiber: Double? = null,
    ) {
        if (name != null) assertEquals("name", name, result.foodName)
        assertClose(kcal, result.caloriesKcal)
        assertClose(protein, result.proteinG)
        assertClose(carbs, result.carbsG)
        assertClose(fat, result.fatG)
        assertClose(fiber, result.fiberG)
    }

    // ------------------------------------------------- document examples

    @Test
    fun `example 1 - packed chinese with no separators`() {
        val r = NutritionTextParser.parse("牛肉饭55蛋白30碳水15膳食1500大卡45脂肪")
        assertAll(r, name = "牛肉饭", kcal = 1500.0, protein = 55.0, carbs = 30.0, fat = 45.0, fiber = 15.0)
        assertTrue("no issues expected, got ${r.issues}", r.issues.isEmpty())
    }

    @Test
    fun `example 2 - kcal unit and g units in mixed order`() {
        val r = NutritionTextParser.parse("鸡胸肉 350kcal 蛋白质60g 脂肪8g 碳水0g")
        assertAll(r, name = "鸡胸肉", kcal = 350.0, protein = 60.0, carbs = 0.0, fat = 8.0, fiber = null)
    }

    @Test
    fun `example 3 - P C F abbreviations and chinese fiber`() {
        val r = NutritionTextParser.parse("三文鱼饭 650大卡 P35 C70 F25 纤维4")
        assertAll(r, name = "三文鱼饭", kcal = 650.0, protein = 35.0, carbs = 70.0, fat = 25.0, fiber = 4.0)
    }

    @Test
    fun `example 4 - calories keyword and full fiber keyword`() {
        val r = NutritionTextParser.parse("牛肉面 热量800 蛋白40 碳水95 脂肪25 膳食纤维6")
        assertAll(r, name = "牛肉面", kcal = 800.0, protein = 40.0, carbs = 95.0, fat = 25.0, fiber = 6.0)
    }

    @Test
    fun `example 5 - english with units`() {
        val r = NutritionTextParser.parse("Milk 200 kcal protein 15g carbs 20g fat 7g fiber 0g")
        assertAll(r, name = "Milk", kcal = 200.0, protein = 15.0, carbs = 20.0, fat = 7.0, fiber = 0.0)
    }

    @Test
    fun `example 6 - number first then name at the end`() {
        val r = NutritionTextParser.parse("500卡 40蛋白 50碳水 15脂肪 鸡肉饭")
        assertAll(r, name = "鸡肉饭", kcal = 500.0, protein = 40.0, carbs = 50.0, fat = 15.0, fiber = null)
    }

    // ------------------------------------------------------- unit handling

    @Test
    fun `kilojoules are converted to kcal`() {
        val r = NutritionTextParser.parse("鸡胸肉 1500千焦 30蛋白")
        assertClose(1500.0 / 4.184, r.caloriesKcal)
        assertTrue(r.issues.any { it is QuickPasteIssue.ConvertedFromKilojoules })
    }

    @Test
    fun `english kj conversion`() {
        val r = NutritionTextParser.parse("Chicken 627 kJ protein 30g")
        assertClose(627.0 / 4.184, r.caloriesKcal)
    }

    @Test
    fun `kcal and calories and cal are all kcal`() {
        assertClose(300.0, NutritionTextParser.parse("x 300 kcal 1蛋白").caloriesKcal)
        assertClose(300.0, NutritionTextParser.parse("x 300 calories 1蛋白").caloriesKcal)
        assertClose(300.0, NutritionTextParser.parse("x 300 cal 1蛋白").caloriesKcal)
        assertClose(300.0, NutritionTextParser.parse("x 300千卡 1蛋白").caloriesKcal)
        assertClose(300.0, NutritionTextParser.parse("x 300卡路里 1蛋白").caloriesKcal)
    }

    @Test
    fun `grams spelled out and in chinese`() {
        assertEquals(30.0, NutritionTextParser.parse("鸡肉 30grams 蛋白 1大卡").proteinG!!, 0.01)
        assertEquals(30.0, NutritionTextParser.parse("鸡肉 30克 蛋白 1大卡").proteinG!!, 0.01)
        assertEquals(30.0, NutritionTextParser.parse("鸡肉 30公克 蛋白 1大卡").proteinG!!, 0.01)
    }

    @Test
    fun `weight unit on a calorie field is reported not silently accepted`() {
        val r = NutritionTextParser.parse("鸡胸肉 500g 热量 30蛋白 20碳水 10脂肪")
        assertNull(r.caloriesKcal)
        val issue = r.issues.filterIsInstance<QuickPasteIssue.UnsupportedUnit>().first()
        assertEquals(NutrientField.CALORIES, issue.field)
        assertEquals("g", issue.unit)
    }

    @Test
    fun `a serving weight with no nutrient keyword stays in the name`() {
        // 100 g of chicken is a serving size, not a nutrient: the parser keeps
        // it in the name instead of inventing a calorie value from it.
        val r = NutritionTextParser.parse("100g 鸡胸肉 350kcal 30蛋白 20碳水 10脂肪")
        assertEquals(350.0, r.caloriesKcal!!, 0.01)
        assertTrue(r.foodName.contains("100g"))
    }

    @Test
    fun `energy unit on a macro field is reported`() {
        val r = NutritionTextParser.parse("鸡胸肉 200mg 蛋白质 20碳水 10脂肪")
        assertNull(r.proteinG)
        val issue = r.issues.filterIsInstance<QuickPasteIssue.UnsupportedUnit>().first()
        assertEquals(NutrientField.PROTEIN, issue.field)
        assertEquals("mg", issue.unit)
    }

    // ------------------------------------------------------ missing fields

    @Test
    fun `missing macros stay unknown and are reported`() {
        val r = NutritionTextParser.parse("鸡胸肉 350大卡 蛋白质60g")
        assertAll(r, name = "鸡胸肉", kcal = 350.0, protein = 60.0, carbs = null, fat = null, fiber = null)
        val missing = r.issues.filterIsInstance<QuickPasteIssue.MissingFields>().single()
        assertEquals(listOf(NutrientField.CARBS, NutrientField.FAT), missing.fields)
    }

    @Test
    fun `fiber is optional and never inferred`() {
        val r = NutritionTextParser.parse("鸡胸肉 350大卡 60蛋白 0碳水 8脂肪")
        assertNull(r.fiberG)
        assertTrue(r.issues.filterIsInstance<QuickPasteIssue.MissingFields>().isEmpty())
    }

    // ---------------------------------------------------------- duplicates

    @Test
    fun `repeated field with different numbers is a conflict`() {
        val r = NutritionTextParser.parse("鸡胸肉 300大卡 500大卡 30蛋白 20碳水 10脂肪")
        assertNull(r.caloriesKcal)
        val conflict = r.conflicts.single()
        assertEquals(NutrientField.CALORIES, conflict.field)
        assertEquals(listOf(300.0, 500.0), conflict.values)
    }

    @Test
    fun `repeated field with the same number is not a conflict`() {
        val r = NutritionTextParser.parse("鸡胸肉 300大卡 300大卡 30蛋白 20碳水 10脂肪")
        assertEquals(300.0, r.caloriesKcal!!, 0.01)
        assertTrue(r.conflicts.isEmpty())
    }

    // --------------------------------------------------- names vs numbers

    @Test
    fun `digits inside the food name are not nutrition`() {
        val r = NutritionTextParser.parse("3号牛肉饭 500大卡 30蛋白 20碳水 10脂肪")
        assertEquals("3号牛肉饭", r.foodName)
        assertEquals(500.0, r.caloriesKcal!!, 0.01)
    }

    @Test
    fun `卡 inside a food name is not a calorie unit`() {
        val r = NutritionTextParser.parse("卡布奇诺 200大卡 5蛋白 20碳水 6脂肪")
        assertEquals("卡布奇诺", r.foodName)
        assertEquals(200.0, r.caloriesKcal!!, 0.01)
    }

    @Test
    fun `protein powder keeps its name when the keyword has no number`() {
        val r = NutritionTextParser.parse("蛋白粉 400大卡 30蛋白 10碳水 5脂肪")
        assertEquals("蛋白粉", r.foodName)
        assertEquals(30.0, r.proteinG!!, 0.01)
    }

    @Test
    fun `lone abbreviation is recognised but not trusted`() {
        val r = NutritionTextParser.parse("鸡胸肉 500大卡 P35 20碳水 10脂肪")
        assertNull(r.proteinG)
        assertTrue(r.issues.any { it is QuickPasteIssue.AmbiguousAbbreviation })
        assertEquals("鸡胸肉", r.foodName)
    }

    @Test
    fun `two of three abbreviations are trusted`() {
        val r = NutritionTextParser.parse("鸡胸肉 500大卡 P35 C20 10脂肪")
        assertEquals(35.0, r.proteinG!!, 0.01)
        assertEquals(20.0, r.carbsG!!, 0.01)
    }

    @Test
    fun `fiber is never counted as carbs`() {
        val r = NutritionTextParser.parse("鸡胸肉 300大卡 30蛋白 20碳水 10脂肪 膳食纤维5")
        assertEquals(20.0, r.carbsG!!, 0.01)
        assertEquals(5.0, r.fiberG!!, 0.01)
    }

    @Test
    fun `fibre spelling`() {
        val r = NutritionTextParser.parse("Chicken 300 kcal protein 30g carbs 20g fat 10g fibre 5g")
        assertEquals(5.0, r.fiberG!!, 0.01)
        assertEquals(20.0, r.carbsG!!, 0.01)
    }

    // ------------------------------------------------------- hostile input

    @Test
    fun `unparseable text keeps the name but reports nothing found`() {
        val r = NutritionTextParser.parse("今天吃了点东西")
        assertEquals("今天吃了点东西", r.foodName)
        assertNull(r.caloriesKcal)
        assertTrue(r.issues.any { it is QuickPasteIssue.NoNutritionFound })
    }

    @Test
    fun `blank input reports both nothing found and no name`() {
        val r = NutritionTextParser.parse("   ")
        assertEquals("", r.foodName)
        assertTrue(r.issues.any { it is QuickPasteIssue.NoNutritionFound })
        assertTrue(r.issues.any { it is QuickPasteIssue.NoFoodName })
    }

    @Test
    fun `numbers without a name report no food name`() {
        val r = NutritionTextParser.parse("500大卡 40蛋白 50碳水 15脂肪")
        assertEquals("", r.foodName)
        assertTrue(r.issues.any { it is QuickPasteIssue.NoFoodName })
        assertEquals(500.0, r.caloriesKcal!!, 0.01)
    }

    @Test
    fun `no nutrition keywords but numbers present`() {
        val r = NutritionTextParser.parse("Table 3 row 4")
        assertEquals("Table 3 row 4", r.foodName)
        assertTrue(r.issues.any { it is QuickPasteIssue.NoNutritionFound })
    }

    // --------------------------------------------------------- formatting

    @Test
    fun `decimals are preserved`() {
        val r = NutritionTextParser.parse("牛肉饭 350.5大卡 30.5蛋白 20.25碳水 10.75脂肪")
        assertEquals(350.5, r.caloriesKcal!!, 0.001)
        assertEquals(30.5, r.proteinG!!, 0.001)
        assertEquals(20.25, r.carbsG!!, 0.001)
        assertEquals(10.75, r.fatG!!, 0.001)
    }

    @Test
    fun `full width digits and letters`() {
        val r = NutritionTextParser.parse("牛肉饭 ５００大卡 ３０蛋白 ２０碳水 １０脂肪")
        assertEquals(500.0, r.caloriesKcal!!, 0.01)
        assertEquals(30.0, r.proteinG!!, 0.01)
    }

    @Test
    fun `newlines commas and punctuation are separators`() {
        val r = NutritionTextParser.parse("牛肉饭\n500大卡，30蛋白；20碳水/10脂肪")
        assertEquals("牛肉饭", r.foodName)
        assertEquals(500.0, r.caloriesKcal!!, 0.01)
        assertEquals(10.0, r.fatG!!, 0.01)
    }

    @Test
    fun `thousands separators are folded`() {
        val r = NutritionTextParser.parse("Pizza 1,500大卡 30蛋白 20碳水 10脂肪")
        assertEquals(1500.0, r.caloriesKcal!!, 0.01)
    }

    @Test
    fun `colon separated form`() {
        val r = NutritionTextParser.parse("鸡肉: 蛋白质: 30 碳水: 20 脂肪: 10 热量: 300")
        assertEquals(300.0, r.caloriesKcal!!, 0.01)
        assertEquals(30.0, r.proteinG!!, 0.01)
    }

    @Test
    fun `mixed chinese and english`() {
        val r = NutritionTextParser.parse("Chicken 鸡胸肉 300 kcal 蛋白质 30g carbs 20g 脂肪 10g")
        assertEquals(300.0, r.caloriesKcal!!, 0.01)
        assertEquals(30.0, r.proteinG!!, 0.01)
        assertEquals(20.0, r.carbsG!!, 0.01)
        assertEquals(10.0, r.fatG!!, 0.01)
    }
}
