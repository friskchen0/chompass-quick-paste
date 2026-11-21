package app.chompass.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.chompass.R
import app.chompass.services.quickpaste.NutrientField
import app.chompass.services.quickpaste.NutritionTextParser
import app.chompass.services.quickpaste.QuickPasteDraft
import app.chompass.services.quickpaste.QuickPasteIssue
import app.chompass.ui.components.ChompassBottomSheet
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.ui.components.ChompassTextField
import app.chompass.ui.components.rememberChompassSheetState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quick Paste Nutrition Entry (prototype).
 *
 * One text box, a deterministic local parse (see
 * [app.chompass.services.quickpaste.NutritionTextParser]), six editable fields,
 * and a Save that goes through the same manual-entry path as the existing
 * "Manual entry" dialog. Nothing is written to the diary until the user taps
 * Save, and nothing here touches an AI provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickPasteSheet(
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (QuickPasteDraft) -> Unit,
) {
    val state = rememberSaveable(saver = QuickPasteState.Saver) { QuickPasteState() }
    val scrollState = rememberScrollState()
    ChompassBottomSheet(
        onDismiss = { if (!isSaving) onDismiss() },
        sheetState = rememberChompassSheetState(busy = isSaving),
    ) {
        QuickPasteSheetContent(
            state = state,
            isSaving = isSaving,
            onDismiss = onDismiss,
            onSave = onSave,
            scrollState = scrollState,
        )
    }
}

/**
 * Sheet body without the modal host, so Robolectric UI tests can drive it the
 * way `HomeAddFoodScreenshotContent` is driven.
 */
@Composable
internal fun QuickPasteSheetContent(
    state: QuickPasteState,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (QuickPasteDraft) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    Column(modifier.fillMaxWidth().imePadding()) {
        SheetReviewToolbar(
            title = stringResource(R.string.quick_paste_title),
            onCancel = { if (!isSaving) onDismiss() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChompassTextField(
                value = state.text,
                onValueChange = state::onTextChange,
                placeholder = stringResource(R.string.quick_paste_input_placeholder),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(QuickPasteTestTags.INPUT),
            )

            if (state.text.isNotBlank()) {
                QuickPasteIssueLines(state)
            }

            Column {
                Text(
                    stringResource(R.string.quick_paste_name_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val nameLabel = stringResource(R.string.quick_paste_name_label)
                ChompassTextField(
                    value = state.name,
                    onValueChange = state::onNameChange,
                    placeholder = stringResource(R.string.quick_paste_name_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(QuickPasteTestTags.NAME)
                        // The visible caption is a sibling Text, so the field
                        // needs its own accessible name for screen readers.
                        .semantics { contentDescription = nameLabel },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField(
                    label = stringResource(R.string.nutrition_label_calories),
                    unit = stringResource(R.string.unit_kcal),
                    value = state.kcalText,
                    onValueChange = { state.onKcalChange(it) },
                    tag = QuickPasteTestTags.KCAL,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = stringResource(R.string.nutrition_label_protein),
                    unit = stringResource(R.string.unit_g),
                    value = state.proteinText,
                    onValueChange = { state.onProteinChange(it) },
                    tag = QuickPasteTestTags.PROTEIN,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField(
                    label = stringResource(R.string.nutrition_label_carbs),
                    unit = stringResource(R.string.unit_g),
                    value = state.carbsText,
                    onValueChange = { state.onCarbsChange(it) },
                    tag = QuickPasteTestTags.CARBS,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = stringResource(R.string.nutrition_label_fat),
                    unit = stringResource(R.string.unit_g),
                    value = state.fatText,
                    onValueChange = { state.onFatChange(it) },
                    tag = QuickPasteTestTags.FAT,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = stringResource(R.string.nutrition_label_fiber),
                    unit = stringResource(R.string.unit_g),
                    value = state.fiberText,
                    onValueChange = { state.onFiberChange(it) },
                    tag = QuickPasteTestTags.FIBER,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }

            ChompassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { state.requestSave(isSaving)?.let(onSave) },
                enabled = state.canSave && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(QuickPasteTestTags.SAVE),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    unit: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.quick_paste_field_label, label, unit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        val accessibleName = stringResource(R.string.quick_paste_field_label, label, unit)
        ChompassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = unit,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { contentDescription = accessibleName },
        )
    }
}

/**
 * Human-readable parse feedback. Errors (conflict, bad unit, missing required
 * column) are painted in the error colour; the kJ conversion and the untrusted
 * P/C/F note are neutral.
 */
@Composable
private fun QuickPasteIssueLines(state: QuickPasteState) {
    val lines = mutableListOf<Pair<String, Boolean>>()
    // Out-of-range is computed from what the fields hold right now, because the
    // user can type a value the parser never saw.
    for (field in state.outOfRangeFields) {
        lines += stringResource(R.string.quick_paste_out_of_range, fieldLabel(field)) to true
    }
    for (issue in state.visibleIssues()) {
        when (issue) {
            QuickPasteIssue.NoNutritionFound ->
                lines += stringResource(R.string.quick_paste_no_nutrition) to true

            QuickPasteIssue.NoFoodName ->
                lines += stringResource(R.string.quick_paste_no_name) to true

            is QuickPasteIssue.MissingFields -> if (issue.fields.isNotEmpty()) {
                // Composable labels, so build the list with a plain loop: a
                // lambda inside joinToString would not be a composable context.
                var labels = ""
                for ((i, field) in issue.fields.withIndex()) {
                    if (i > 0) labels += ", "
                    labels += fieldLabel(field)
                }
                lines += stringResource(R.string.quick_paste_missing, labels) to true
            }

            is QuickPasteIssue.ConflictingValues -> lines += stringResource(
                R.string.quick_paste_conflict,
                fieldLabel(issue.field),
                issue.values.joinToString(", ") { formatQuantity(it) },
            ) to true

            is QuickPasteIssue.UnsupportedUnit -> lines += stringResource(
                R.string.quick_paste_unit_error,
                fieldLabel(issue.field),
                issue.unit,
            ) to true

            is QuickPasteIssue.ConvertedFromKilojoules -> lines += stringResource(
                R.string.quick_paste_converted_kj,
                formatQuantity(issue.originalKj),
                formatQuantity(issue.kcal),
            ) to false

            is QuickPasteIssue.AmbiguousAbbreviation -> lines += stringResource(
                R.string.quick_paste_abbrev,
                issue.token,
            ) to false

            is QuickPasteIssue.NegativeValue -> lines += stringResource(
                R.string.quick_paste_negative,
                fieldLabel(issue.field),
            ) to true

            is QuickPasteIssue.ImplausibleValue -> lines += stringResource(
                R.string.quick_paste_implausible,
                fieldLabel(issue.field),
                formatQuantity(issue.value),
            ) to true
        }
    }
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((text, isError) in lines) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun fieldLabel(field: NutrientField): String = stringResource(
    when (field) {
        NutrientField.CALORIES -> R.string.nutrition_label_calories
        NutrientField.PROTEIN -> R.string.nutrition_label_protein
        NutrientField.CARBS -> R.string.nutrition_label_carbs
        NutrientField.FAT -> R.string.nutrition_label_fat
        NutrientField.FIBER -> R.string.nutrition_label_fiber
    },
)

/**
 * `350.0` reads as `350`, `12.5` stays `12.5`, and a kJ conversion shows
 * `358.51` instead of a 17-digit double. Dot decimals, `Locale.US` — the field
 * text has to re-parse with [String.toDoubleOrNull].
 */
internal fun formatQuantity(value: Double?): String {
    if (value == null) return ""
    val whole = value.roundToInt()
    if (abs(value - whole) < 1e-9) return whole.toString()
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}

/** Test tags for the Quick Paste sheet. */
internal object QuickPasteTestTags {
    const val INPUT = "quick-paste-input"
    const val NAME = "quick-paste-name"
    const val KCAL = "quick-paste-kcal"
    const val PROTEIN = "quick-paste-protein"
    const val CARBS = "quick-paste-carbs"
    const val FAT = "quick-paste-fat"
    const val FIBER = "quick-paste-fiber"
    const val SAVE = "quick-paste-save"
}

/**
 * Editable state behind the sheet. Parsing is pure; this class only decides
 * what lands in the fields, which issues are shown, and when a save may fire.
 */
@Stable
internal class QuickPasteState {
    var text by mutableStateOf("")
        private set
    var name by mutableStateOf("")
        private set
    var kcalText by mutableStateOf("")
        private set
    var proteinText by mutableStateOf("")
        private set
    var carbsText by mutableStateOf("")
        private set
    var fatText by mutableStateOf("")
        private set
    var fiberText by mutableStateOf("")
        private set
    var issues by mutableStateOf<List<QuickPasteIssue>>(emptyList())
        private set

    /** Fields whose duplicate values made the parse ambiguous. */
    var conflictFields by mutableStateOf<Set<NutrientField>>(emptySet())
        private set

    /** True once Save fired, so a double tap cannot create two diary rows. */
    var saveRequested by mutableStateOf(false)
        private set

    fun onTextChange(value: String) {
        text = value
        saveRequested = false
        applyParse(NutritionTextParser.parse(value))
    }

    fun onNameChange(value: String) {
        name = value
    }

    fun onKcalChange(value: String) {
        kcalText = value
        clearConflict(NutrientField.CALORIES)
    }

    fun onProteinChange(value: String) {
        proteinText = value
        clearConflict(NutrientField.PROTEIN)
    }

    fun onCarbsChange(value: String) {
        carbsText = value
        clearConflict(NutrientField.CARBS)
    }

    fun onFatChange(value: String) {
        fatText = value
        clearConflict(NutrientField.FAT)
    }

    fun onFiberChange(value: String) {
        fiberText = value
        clearConflict(NutrientField.FIBER)
    }

    private fun textOf(field: NutrientField): String = when (field) {
        NutrientField.CALORIES -> kcalText
        NutrientField.PROTEIN -> proteinText
        NutrientField.CARBS -> carbsText
        NutrientField.FAT -> fatText
        NutrientField.FIBER -> fiberText
    }

    private fun valueOf(field: NutrientField): Double? = textOf(field).trim().toDoubleOrNull()

    private fun maxOf(field: NutrientField): Double =
        if (field == NutrientField.CALORIES) {
            NutritionTextParser.MAX_CALORIES_KCAL
        } else {
            NutritionTextParser.MAX_GRAMS
        }

    /** Required columns that are still blank (or not a number) right now. */
    val missingRequiredFields: List<NutrientField>
        get() = NutritionTextParser.REQUIRED_FIELDS.filter { valueOf(it) == null }

    /** Fields holding a number a single diary row cannot take. Save stays off. */
    val outOfRangeFields: List<NutrientField>
        get() = NutrientField.entries.filter { field ->
            val value = valueOf(field) ?: return@filter false
            !value.isFinite() || value < 0 || value > maxOf(field)
        }

    /**
     * What the sheet should say right now. Re-derived from the fields instead
     * of replaying the parse, so a message disappears as soon as the user fixes
     * the thing it complained about (typing the missing carb, resolving a
     * duplicate, naming the food).
     */
    fun visibleIssues(): List<QuickPasteIssue> {
        val visible = mutableListOf<QuickPasteIssue>()
        for (issue in issues) {
            when (issue) {
                is QuickPasteIssue.ConflictingValues -> if (issue.field in conflictFields) visible += issue
                // Recomputed below from the live fields.
                is QuickPasteIssue.MissingFields, QuickPasteIssue.NoFoodName -> Unit
                else -> visible += issue
            }
        }
        val missing = missingRequiredFields
        val hasAnyNumber = NutrientField.entries.any { !textOf(it).isBlank() }
        if (hasAnyNumber && missing.isNotEmpty()) {
            visible += QuickPasteIssue.MissingFields(missing)
        }
        if (name.isBlank() && text.isNotBlank()) {
            visible += QuickPasteIssue.NoFoodName
        }
        return visible
    }

    /** The row that would be saved, or null while a required number is unusable. */
    val draft: QuickPasteDraft?
        get() {
            if (name.isBlank()) return null
            if (missingRequiredFields.isNotEmpty()) return null
            if (outOfRangeFields.isNotEmpty()) return null
            val kcal = valueOf(NutrientField.CALORIES) ?: return null
            val protein = valueOf(NutrientField.PROTEIN) ?: return null
            val carbs = valueOf(NutrientField.CARBS) ?: return null
            val fat = valueOf(NutrientField.FAT) ?: return null
            // Blank fiber means "not provided" (the diary column is nullable);
            // an explicit 0 is kept as 0.
            val fiber = valueOf(NutrientField.FIBER)
            return QuickPasteDraft(
                name = name.trim(),
                caloriesKcal = kcal.roundToInt(),
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                fiberG = fiber,
            )
        }

    val canSave: Boolean get() = draft != null && !saveRequested

    /**
     * Claim the one save this sheet is allowed to perform. Returns the draft on
     * the first call after a successful parse, null for every later call.
     */
    fun requestSave(isSaving: Boolean): QuickPasteDraft? {
        if (isSaving || saveRequested) return null
        val d = draft ?: return null
        saveRequested = true
        return d
    }

    private fun clearConflict(field: NutrientField) {
        if (field in conflictFields) conflictFields = conflictFields - field
    }

    private fun applyParse(result: app.chompass.services.quickpaste.QuickPasteParseResult) {
        name = result.foodName
        kcalText = formatQuantity(result.caloriesKcal)
        proteinText = formatQuantity(result.proteinG)
        carbsText = formatQuantity(result.carbsG)
        fatText = formatQuantity(result.fatG)
        fiberText = formatQuantity(result.fiberG)
        issues = result.issues
        conflictFields = result.conflicts.map { it.field }.toSet()
    }

    companion object {
        val Saver: Saver<QuickPasteState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.text,
                    state.name,
                    state.kcalText,
                    state.proteinText,
                    state.carbsText,
                    state.fatText,
                    state.fiberText,
                    if (state.saveRequested) "1" else "0",
                )
            },
            restore = { saved -> restore(saved) },
        )

        private fun restore(saved: List<String>): QuickPasteState = QuickPasteState().apply {
            text = saved[0]
            name = saved[1]
            kcalText = saved[2]
            proteinText = saved[3]
            carbsText = saved[4]
            fatText = saved[5]
            fiberText = saved[6]
            saveRequested = saved[7] == "1"
            // Re-derive the advice lines; the field values the user may have
            // edited are restored verbatim above.
            val parsed = NutritionTextParser.parse(text)
            issues = parsed.issues
            conflictFields = parsed.conflicts.map { it.field }.toSet()
        }
    }
}
