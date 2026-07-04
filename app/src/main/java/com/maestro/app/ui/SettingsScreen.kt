package com.maestro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maestro.app.ReaderViewModel
import com.maestro.app.face.GestureConfig
import com.maestro.app.face.GestureType
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    // Working copy; re-seeded whenever the persisted config actually changes.
    var draft by remember(config) { mutableStateOf(config) }

    fun commit(updated: GestureConfig) {
        draft = updated
        viewModel.saveConfig(updated)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.fillMaxWidth(0f))
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Gesture mapping")
        Text(
            "Assign a gesture to each direction. If left/right feel reversed on your device, just swap them here. Set a direction to \"None\" to disable it (no gesture and no edge tap).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        GestureDropdown(
            label = "Next page",
            selected = draft.nextGesture,
            onSelect = { commit(draft.copy(nextGesture = it)) }
        )
        Spacer(Modifier.height(12.dp))
        GestureDropdown(
            label = "Previous page",
            selected = draft.prevGesture,
            onSelect = { commit(draft.copy(prevGesture = it)) }
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        SectionTitle("Sensitivity")

        SliderRow(
            label = "Head-turn angle",
            value = draft.yawThresholdDeg,
            valueRange = 10f..40f,
            display = "${draft.yawThresholdDeg.roundToInt()}°",
            onChange = { draft = draft.copy(yawThresholdDeg = it) },
            onCommit = { viewModel.saveConfig(draft) }
        )
        SliderRow(
            label = "Eye-closed threshold",
            value = draft.eyeClosedThreshold,
            valueRange = 0.05f..0.5f,
            display = String.format("%.2f", draft.eyeClosedThreshold),
            onChange = { draft = draft.copy(eyeClosedThreshold = it) },
            onCommit = { viewModel.saveConfig(draft) }
        )
        SliderRow(
            label = "Smile threshold",
            value = draft.smileThreshold,
            valueRange = 0.3f..0.9f,
            display = String.format("%.2f", draft.smileThreshold),
            onChange = { draft = draft.copy(smileThreshold = it) },
            onCommit = { viewModel.saveConfig(draft) }
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        SectionTitle("Timing (anti-misfire)")

        SliderRow(
            label = "Hold time (head turn, wink, smile)",
            value = draft.dwellMs.toFloat(),
            valueRange = 150f..800f,
            display = "${draft.dwellMs} ms",
            onChange = { draft = draft.copy(dwellMs = it.roundToLong()) },
            onCommit = { viewModel.saveConfig(draft) }
        )
        SliderRow(
            label = "Long-blink duration",
            value = draft.longBlinkMs.toFloat(),
            valueRange = 400f..1500f,
            display = "${draft.longBlinkMs} ms",
            onChange = { draft = draft.copy(longBlinkMs = it.roundToLong()) },
            onCommit = { viewModel.saveConfig(draft) }
        )
        SliderRow(
            label = "Cooldown after a turn",
            value = draft.cooldownMs.toFloat(),
            valueRange = 500f..2500f,
            display = "${draft.cooldownMs} ms",
            onChange = { draft = draft.copy(cooldownMs = it.roundToLong()) },
            onCommit = { viewModel.saveConfig(draft) }
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Start with gestures on", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = draft.enabledByDefault,
                onCheckedChange = { commit(draft.copy(enabledByDefault = it)) }
            )
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { commit(GestureConfig()) }) { Text("Reset to defaults") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureDropdown(
    label: String,
    selected: GestureType,
    onSelect: (GestureType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GestureType.entries.forEach { gesture ->
                DropdownMenuItem(
                    text = { Text(gesture.label) },
                    onClick = {
                        onSelect(gesture)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            onValueChangeFinished = onCommit
        )
    }
}
