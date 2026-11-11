package com.example.idautble

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp

internal enum class AppBackground(val label: String, val color: Color?) {
    DEFAULT("Default", null),
    MIST("Mist gray", Color(0xFFD0D4DA)),
    COOL_GRAY("Cool gray", Color(0xFFADB6C1)),
    WARM_GRAY("Warm gray", Color(0xFFADA6A0)),
    SAGE("Sage", Color(0xFF96A69D)),
    SLATE("Slate blue", Color(0xFF778A9E)),
    GRAPHITE("Graphite", Color(0xFF4C535D)),
    PURE_BLACK("Pure black", Color.Black),
}

internal enum class TerminalTextSize(val label: String, val fontSizeSp: Int) {
    NORMAL("Normal", 12),
    LARGE("Large", 15),
    EXTRA_LARGE("Extra large", 18),
}

internal enum class TerminalLogView(val label: String, val showAscii: Boolean, val showHex: Boolean) {
    ASCII("ASCII", true, false),
    HEX("HEX", false, true),
    BOTH("ASCII + HEX", true, true),
}

internal enum class TerminalSection(val label: String) {
    BLUETOOTH("Bluetooth connection"),
    BINARY_COMMANDS("Binary commands"),
    READINGS("Readings"),
    DEVICE_STATUS("Device status"),
    PIN_ACCESS("PIN / Access"),
    HID_RECEIVER("HID receiver"),
    HID_OUTPUT("HID output"),
    DEVICE_ID("Device ID"),
    HEIGHT_MEASUREMENT("Height measurement"),
    BARCODE_RTC("Barcode ID / RTC"),
    MANUAL_TERMINAL("Manual terminal");

    companion object {
        fun readOrder(saved: String?): List<TerminalSection> {
            val savedIds = saved.orEmpty().split(',')
            val savedSections = savedIds.mapNotNull { id -> entries.firstOrNull { it.name == id } }
            return (savedSections + entries).distinct()
        }
    }
}

private enum class SettingsPage(val title: String) {
    MAIN("App settings"),
    BACKGROUND("Background"),
    THEME("Theme"),
    TERMINAL_TEXT_SIZE("Terminal text size"),
    LOG_VIEW("Log view"),
    TERMINAL_ORDER("Terminal section order"),
}

@Composable
internal fun AppSettingsDialog(
    darkMode: Boolean,
    background: AppBackground,
    terminalTextSize: TerminalTextSize,
    logView: TerminalLogView,
    keepScreenOn: Boolean,
    terminalOrder: List<TerminalSection>,
    onTerminalOrderChange: (List<TerminalSection>) -> Unit,
    onTerminalTextSizeChange: (TerminalTextSize) -> Unit,
    onLogViewChange: (TerminalLogView) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
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
                SettingsPage.MAIN -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
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
                    ListItem(
                        modifier = Modifier.clickable { page = SettingsPage.TERMINAL_TEXT_SIZE },
                        headlineContent = { Text("Terminal text size") },
                        supportingContent = { Text(terminalTextSize.label) },
                        trailingContent = { SettingsChevron() },
                        colors = colors,
                    )
                    ListItem(
                        modifier = Modifier.clickable { page = SettingsPage.LOG_VIEW },
                        headlineContent = { Text("Log view") },
                        supportingContent = { Text(logView.label) },
                        trailingContent = { SettingsChevron() },
                        colors = colors,
                    )
                    ListItem(
                        modifier = Modifier.clickable { page = SettingsPage.TERMINAL_ORDER },
                        headlineContent = { Text("Terminal section order") },
                        trailingContent = { SettingsChevron() },
                        colors = colors,
                    )
                    ListItem(
                        modifier = Modifier.toggleable(keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOnChange),
                        headlineContent = { Text("Keep screen on") },
                        supportingContent = { Text("While this app is on screen") },
                        trailingContent = { Switch(checked = keepScreenOn, onCheckedChange = null) },
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
                SettingsPage.TERMINAL_TEXT_SIZE -> Column(Modifier.selectableGroup()) {
                    TerminalTextSize.entries.forEach { option ->
                        val selected = terminalTextSize == option
                        ListItem(
                            modifier = Modifier.selectable(selected, role = Role.RadioButton,
                                onClick = { onTerminalTextSizeChange(option) }),
                            headlineContent = { Text(option.label, fontSize = option.fontSizeSp.sp) },
                            trailingContent = { RadioButton(selected, onClick = null) },
                            colors = colors,
                        )
                    }
                }
                SettingsPage.LOG_VIEW -> Column(Modifier.selectableGroup()) {
                    TerminalLogView.entries.forEach { option ->
                        val selected = logView == option
                        ListItem(
                            modifier = Modifier.selectable(selected, role = Role.RadioButton,
                                onClick = { onLogViewChange(option) }),
                            headlineContent = { Text(option.label) },
                            trailingContent = { RadioButton(selected, onClick = null) },
                            colors = colors,
                        )
                    }
                }
                SettingsPage.TERMINAL_ORDER -> Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                ) {
                    terminalOrder.forEachIndexed { index, section ->
                        fun moveTo(destination: Int) {
                            val updated = terminalOrder.toMutableList()
                            updated.add(destination, updated.removeAt(index))
                            onTerminalOrderChange(updated)
                        }
                        ListItem(
                            headlineContent = { Text(section.label) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { moveTo(index - 1) }, enabled = index > 0) {
                                        Icon(Icons.Outlined.KeyboardArrowUp, "Move ${section.label} up")
                                    }
                                    IconButton(onClick = { moveTo(index + 1) }, enabled = index < terminalOrder.lastIndex) {
                                        Icon(Icons.Outlined.KeyboardArrowDown, "Move ${section.label} down")
                                    }
                                }
                            },
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
        dismissButton = {
            if (page == SettingsPage.TERMINAL_ORDER) {
                TextButton(
                    onClick = { onTerminalOrderChange(TerminalSection.entries) },
                    enabled = terminalOrder != TerminalSection.entries,
                ) { Text("Reset order") }
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
