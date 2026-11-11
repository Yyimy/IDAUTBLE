package com.example.idautble

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class AppBackground(val label: String, val color: Color?) {
    DEFAULT("Default", null),
    MIST("Mist gray", Color(0xFFD0D4DA)),
    COOL_GRAY("Cool gray", Color(0xFFADB6C1)),
    WARM_GRAY("Warm gray", Color(0xFFADA6A0)),
    SAGE("Sage", Color(0xFF96A69D)),
    SLATE("Slate blue", Color(0xFF778A9E)),
    GRAPHITE("Graphite", Color(0xFF4C535D)),
}

private enum class SettingsPage(val title: String) {
    MAIN("App settings"),
    BACKGROUND("Background"),
    THEME("Theme"),
}

@Composable
internal fun AppSettingsDialog(
    darkMode: Boolean,
    background: AppBackground,
    onDarkModeChange: (Boolean) -> Unit,
    onBackgroundChange: (AppBackground) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    val themeLabel = if (darkMode) "Dark mode" else "Light"

    AlertDialog(
        onDismissRequest = {
            if (page == SettingsPage.MAIN) onDismiss() else page = SettingsPage.MAIN
        },
        title = { Text(page.title) },
        text = {
            when (page) {
                SettingsPage.MAIN -> Column {
                    ListItem(
                        modifier = Modifier.clickable { page = SettingsPage.BACKGROUND },
                        headlineContent = { Text("Background") },
                        supportingContent = { Text(background.label) },
                        leadingContent = { BackgroundSwatch(background) },
                        trailingContent = { SettingsChevron() },
                        colors = colors,
                    )
                    ListItem(
                        modifier = Modifier.clickable { page = SettingsPage.THEME },
                        headlineContent = { Text("Theme") },
                        supportingContent = { Text(themeLabel) },
                        trailingContent = { SettingsChevron() },
                        colors = colors,
                    )
                }
                SettingsPage.BACKGROUND -> Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup()
                ) {
                    AppBackground.entries.forEach { option ->
                        val selected = background == option
                        ListItem(
                            modifier = Modifier.selectable(selected, role = Role.RadioButton,
                                onClick = { onBackgroundChange(option) }),
                            headlineContent = { Text(option.label) },
                            leadingContent = { BackgroundSwatch(option) },
                            trailingContent = { RadioButton(selected, onClick = null) },
                            colors = colors,
                        )
                    }
                }
                SettingsPage.THEME -> Column(Modifier.selectableGroup()) {
                    listOf(false to "Light", true to "Dark mode").forEach { (enabled, label) ->
                        val selected = darkMode == enabled
                        ListItem(
                            modifier = Modifier.selectable(selected, role = Role.RadioButton,
                                onClick = { onDarkModeChange(enabled) }),
                            headlineContent = { Text(label) },
                            trailingContent = { RadioButton(selected, onClick = null) },
                            colors = colors,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (page == SettingsPage.MAIN) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(onClick = { page = SettingsPage.MAIN }) { Text("Back") }
            }
        },
    )
}

@Composable
private fun BackgroundSwatch(background: AppBackground) {
    Surface(
        modifier = Modifier.size(26.dp),
        shape = CircleShape,
        color = background.color ?: MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {}
}

@Composable
private fun SettingsChevron() {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
}
