package app.chompass.ui.home

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.chompass.services.quickpaste.QuickPasteDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real Quick Paste sheet body: paste text, read the preview, edit a
 * field, save once, cancel without saving. This is the UI half of the feature —
 * the grammar itself is covered by NutritionTextParserTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickPasteSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val saved = mutableListOf<QuickPasteDraft>()
    private var dismissed = 0

    private fun render(isSaving: Boolean = false) {
        composeRule.setContent {
            val state = remember { QuickPasteState() }
            QuickPasteSheetContent(
                state = state,
                isSaving = isSaving,
                onDismiss = { dismissed++ },
                onSave = { saved += it },
            )
        }
    }

    private fun type(text: String) {
        composeRule.onNodeWithTag(QuickPasteTestTags.INPUT).performTextReplacement(text)
        composeRule.waitForIdle()
    }

    private fun saveButton() = composeRule.onNodeWithTag(QuickPasteTestTags.SAVE)

    private fun fieldText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    }

    @Test
    fun `pasting a line fills the editable preview`() {
        render()
        type("牛肉饭55蛋白30碳水15膳食1500大卡45脂肪")

        assertEquals("牛肉饭", fieldText(QuickPasteTestTags.NAME))
        assertEquals("1500", fieldText(QuickPasteTestTags.KCAL))
        assertEquals("55", fieldText(QuickPasteTestTags.PROTEIN))
        assertEquals("30", fieldText(QuickPasteTestTags.CARBS))
        assertEquals("45", fieldText(QuickPasteTestTags.FAT))
        assertEquals("15", fieldText(QuickPasteTestTags.FIBER))
    }

    @Test
    fun `save starts disabled until the text parses`() {
        render()
        saveButton().assertIsNotEnabled()

        type("鸡胸肉 350kcal 蛋白质60g 脂肪8g 碳水0g")
        saveButton().assertIsEnabled()
    }

    @Test
    fun `save emits one draft with the parsed values and never repeats`() {
        render()
        type("三文鱼饭 650大卡 P35 C70 F25 纤维4")

        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()
        // A second tap (double-click, or a tap after the first one closed the
        // sheet) must not create another diary row.
        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, saved.size)
        val draft = saved.single()
        assertEquals("三文鱼饭", draft.name)
        assertEquals(650, draft.caloriesKcal)
        assertEquals(35.0, draft.proteinG, 0.001)
        assertEquals(70.0, draft.carbsG, 0.001)
        assertEquals(25.0, draft.fatG, 0.001)
        assertEquals(4.0, draft.fiberG!!, 0.001)
    }

    @Test
    fun `cancel dismisses without saving`() {
        render()
        type("鸡胸肉 350kcal 蛋白质60g 脂肪8g 碳水0g")

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissed)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `manual edit overrides the parsed number`() {
        render()
        type("鸡胸肉 350kcal 蛋白质60g 脂肪8g 碳水0g")
        composeRule.onNodeWithTag(QuickPasteTestTags.KCAL).performTextReplacement("900")
        composeRule.waitForIdle()

        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(900, saved.single().caloriesKcal)
    }

    @Test
    fun `conflicting duplicate values block save and explain why`() {
        render()
        type("鸡胸肉 300大卡 500大卡 30蛋白 20碳水 10脂肪")

        assertEquals("", fieldText(QuickPasteTestTags.KCAL))
        saveButton().assertIsNotEnabled()
        composeRule.onNodeWithText("Calories appears twice (300, 500). Type one value.").assertExists()
    }

    @Test
    fun `kilojoules are converted before saving`() {
        render()
        type("鸡胸肉 1500千焦 30蛋白 20碳水 10脂肪")

        assertEquals("358.51", fieldText(QuickPasteTestTags.KCAL))
        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(359, saved.single().caloriesKcal)
    }

    @Test
    fun `missing required field keeps save disabled`() {
        render()
        type("鸡胸肉 350大卡 蛋白质60g")

        assertEquals("", fieldText(QuickPasteTestTags.CARBS))
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `wrong unit is surfaced instead of guessed`() {
        render()
        type("鸡胸肉 500g 热量 30蛋白 20碳水 10脂肪")

        assertEquals("", fieldText(QuickPasteTestTags.KCAL))
        composeRule.onNodeWithText("Calories cannot be given in g.").assertExists()
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `unparseable text reports nothing found`() {
        render()
        type("今天吃了点东西")

        saveButton().assertIsNotEnabled()
        composeRule.onNodeWithText("No nutrition numbers found. Try “Chicken 350 kcal 30 protein”.")
            .assertExists()
    }

    @Test
    fun `fiber is optional so a complete entry can be saved without it`() {
        render()
        type("鸡胸肉 350kcal 蛋白质60g 脂肪8g 碳水0g")

        assertEquals("", fieldText(QuickPasteTestTags.FIBER))
        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, saved.size)
        assertNull(saved.single().fiberG)
    }

    // --------------------------------------------- live validation feedback

    @Test
    fun `missing field message disappears once the user fills it`() {
        render()
        type("鸡胸肉 350大卡 蛋白质60g")
        composeRule
            .onNodeWithText("Not found: Carbs, Fat. The diary needs these; type a value (0 is fine).")
            .assertExists()

        composeRule.onNodeWithTag(QuickPasteTestTags.CARBS).performTextReplacement("20")
        composeRule.onNodeWithTag(QuickPasteTestTags.FAT).performTextReplacement("10")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Not found: Carbs, Fat. The diary needs these; type a value (0 is fine).")
            .assertCountEquals(0)
        saveButton().assertIsEnabled()
    }

    @Test
    fun `conflict message disappears when the user resolves the field`() {
        render()
        type("鸡胸肉 300大卡 500大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithText("Calories appears twice (300, 500). Type one value.").assertExists()
        saveButton().assertIsNotEnabled()

        composeRule.onNodeWithTag(QuickPasteTestTags.KCAL).performTextReplacement("400")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Calories appears twice (300, 500). Type one value.")
            .assertCountEquals(0)
        saveButton().assertIsEnabled()
    }

    @Test
    fun `typed value outside the diary range blocks save`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithTag(QuickPasteTestTags.KCAL).performTextReplacement("999999999")
        composeRule.waitForIdle()

        saveButton().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Calories is outside the range one diary row can hold.")
            .assertExists()
    }

    @Test
    fun `explicit zero is kept and saved as zero`() {
        render()
        type("苏打水 0大卡 0蛋白 0碳水 0脂肪")

        assertEquals("0", fieldText(QuickPasteTestTags.KCAL))
        assertEquals("0", fieldText(QuickPasteTestTags.CARBS))

        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0, saved.single().caloriesKcal)
        assertEquals(0.0, saved.single().carbsG, 0.001)
    }

    // --------------------------------- optional-field parity (review round 3)

    @Test
    fun `invalid fiber text blocks save instead of saving as not provided`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        saveButton().assertIsEnabled()

        composeRule.onNodeWithTag(QuickPasteTestTags.FIBER).performTextReplacement("abc")
        composeRule.waitForIdle()

        saveButton().assertIsNotEnabled()
        composeRule.onNodeWithText("Enter a number for Fiber.").assertExists()
    }

    @Test
    fun `blank fiber still saves as not provided`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")

        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, saved.size)
        assertNull(saved.single().fiberG)
    }

    @Test
    fun `fiber zero is saved as zero`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithTag(QuickPasteTestTags.FIBER).performTextReplacement("0")
        composeRule.waitForIdle()

        saveButton().performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0.0, saved.single().fiberG!!, 0.001)
    }

    @Test
    fun `non finite fiber text blocks save`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithTag(QuickPasteTestTags.FIBER).performTextReplacement("Infinity")
        composeRule.waitForIdle()

        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `negative fiber blocks save`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithTag(QuickPasteTestTags.FIBER).performTextReplacement("-5")
        composeRule.waitForIdle()

        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `invalid required field asks for a number instead of claiming it is missing`() {
        render()
        type("鸡胸肉 350大卡 30蛋白 20碳水 10脂肪")
        composeRule.onNodeWithTag(QuickPasteTestTags.PROTEIN).performTextReplacement("abc")
        composeRule.waitForIdle()

        saveButton().assertIsNotEnabled()
        composeRule.onNodeWithText("Enter a number for Protein.").assertExists()
        composeRule.onAllNodesWithText("Not found: Protein. The diary needs these; type a value (0 is fine).")
            .assertCountEquals(0)
    }
}
