package com.spotifree.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spotifree.app.ui.theme.LocalSpotifreeColors

@Composable
fun SettingsHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LocalSpotifreeColors.current.accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
fun SettingItem(title: String, subtitle: String? = null, icon: ImageVector? = null, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val c = LocalSpotifreeColors.current
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Color.White else c.textSecondary)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalSpotifreeColors.current
    SettingItem(title, subtitle, icon, enabled, trailing = {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }, onClick = { onChange(!checked) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoice(title: String, subtitle: String? = null, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalSpotifreeColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            options.forEach { (v, label) ->
                FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = Color.Black))
            }
        }
    }
}

@Composable
fun SettingSlider(title: String, valueLabel: (Float) -> String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, subtitle: String? = null, onChange: (Float) -> Unit) {
    val c = LocalSpotifreeColors.current
    // Local value while dragging; saved once when the finger lifts.
    var local by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel(local), color = c.accent)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}
