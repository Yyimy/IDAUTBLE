package com.example.idautble

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
    PURE_BLACK("Pure black", Color.Black);

    fun resolveColor(darkMode: Boolean): Color? = color ?: if (darkMode) null else Color.Black
}

internal enum class TerminalTextSize(val label: String, val fontSizeSp: Int, val lineHeightSp: Int) {
    NORMAL("Normal", 12, 16),
    LARGE("Large", 15, 20),
    EXTRA_LARGE("Extra large", 18, 24),
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
    BARCODE_ID("Barcode ID"),
    RTC("RTC"),
    MANUAL_TERMINAL("Manual terminal");

    companion object {
        val defaultExpanded = setOf(BLUETOOTH, BINARY_COMMANDS, READINGS)

        fun readExpanded(saved: String?): Set<TerminalSection> {
            if (saved == null) return defaultExpanded
            val ids = saved.split(',').toSet()
            return entries.filter { it.name in ids }.toSet()
        }

        fun readOrder(saved: String?): List<TerminalSection> {
            val savedIds = saved.orEmpty().split(',')
            val savedSections = savedIds.flatMap { id ->
                if (id == "BARCODE_RTC") listOf(BARCODE_ID, RTC)
                else listOfNotNull(entries.firstOrNull { it.name == id })
            }
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
    TERMINAL_ORDER("Section order"),
    EXPANDED_SECTIONS("Expanded sections"),
}

@Composable
internal fun AppSettingsDialog(
    darkMode: Boolean,
    background: AppBackground,
    terminalTextSize: TerminalTextSize,
    logView: TerminalLogView,
    keepScreenOn: Boolean,
    terminalOrder: List<TerminalSection>,
    defaultExpanded: Set<TerminalSection>,
    onDefaultExpandedChange: (Set<TerminalSection>) -> Unit,
    onTerminalOrderChange: (List<TerminalSection>) -> Unit,
    onTerminalTextSizeChange: (TerminalTextSize) -> Unit,
    onLogViewChange: (TerminalLogView) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onBackgroundChange: (AppBackground) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val themeLabel = if (darkMode) "Dark mode" else "Light mode"

    AlertDialog(
        onDismissRequest = {
            if (page == SettingsPage.MAIN) onDismiss() else page = SettingsPage.MAIN
        },
        title = { Text(page.title) },
        text = {
            when (page) {
                SettingsPage.MAIN -> Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                ) {
                    SettingsGroupHeading("Style")
                    SettingsRow("Theme", themeLabel, Modifier.clickable { page = SettingsPage.THEME }) {
                        SettingsChevron()
                    }
                    HorizontalDivider()
                    SettingsRow("Background", background.label, Modifier.clickable { page = SettingsPage.BACKGROUND }) {
                        BackgroundSwatch(background, darkMode, Modifier.size(18.dp))
                        SettingsChevron()
                    }
                    HorizontalDivider()
                    SettingsRow(
                        "Terminal text size",
                        terminalTextSize.label,
                        Modifier.clickable { page = SettingsPage.TERMINAL_TEXT_SIZE },
                    ) { SettingsChevron() }
                    HorizontalDivider()
                    SettingsRow("Log view", logView.label, Modifier.clickable { page = SettingsPage.LOG_VIEW }) {
                        SettingsChevron()
                    }

                    SettingsGroupHeading("Terminal sections")
                    SettingsRow("Section order", modifier = Modifier.clickable { page = SettingsPage.TERMINAL_ORDER }) {
                        SettingsChevron()
                    }
                    HorizontalDivider()
                    SettingsRow("Expanded sections", modifier = Modifier.clickable { page = SettingsPage.EXPANDED_SECTIONS }) {
                        SettingsChevron()
                    }

                    SettingsGroupHeading("Screen")
                    SettingsRow(
                        "Keep screen on",
                        modifier = Modifier.toggleable(keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOnChange),
                    ) { Switch(checked = keepScreenOn, onCheckedChange = null) }
                }
                SettingsPage.BACKGROUND -> Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup()
                ) {
                    AppBackground.entries.forEachIndexed { index, option ->
                        SettingsChoiceRow(
                            label = option.label,
                            selected = background == option,
                            onClick = { onBackgroundChange(option) },
                            divider = index < AppBackground.entries.lastIndex,
                            leading = { BackgroundSwatch(option, darkMode, Modifier.size(18.dp)) },
                        )
                    }
                }
                SettingsPage.TERMINAL_TEXT_SIZE -> Column(Modifier.selectableGroup()) {
                    TerminalTextSize.entries.forEachIndexed { index, option ->
                        SettingsChoiceRow(
                            label = option.label,
                            selected = terminalTextSize == option,
                            onClick = { onTerminalTextSizeChange(option) },
                            divider = index < TerminalTextSize.entries.lastIndex,
                            labelStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = option.fontSizeSp.sp,
                                lineHeight = option.lineHeightSp.sp,
                            ),
                        )
                    }
                }
                SettingsPage.LOG_VIEW -> Column(Modifier.selectableGroup()) {
                    TerminalLogView.entries.forEachIndexed { index, option ->
                        SettingsChoiceRow(
                            label = option.label,
                            selected = logView == option,
                            onClick = { onLogViewChange(option) },
                            divider = index < TerminalLogView.entries.lastIndex,
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
                        SettingsRow(section.label) {
                            IconButton(onClick = { moveTo(index - 1) }, enabled = index > 0) {
                                Icon(Icons.Outlined.KeyboardArrowUp, "Move ${section.label} up")
                            }
                            IconButton(onClick = { moveTo(index + 1) }, enabled = index < terminalOrder.lastIndex) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "Move ${section.label} down")
                            }
                        }
                        if (index < terminalOrder.lastIndex) HorizontalDivider()
                    }
                }
                SettingsPage.EXPANDED_SECTIONS -> Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                ) {
                    val allExpanded = defaultExpanded.size == TerminalSection.entries.size
                    SettingsRow(
                        if (allExpanded) "Collapse all" else "Expand all",
                        modifier = Modifier.clickable {
                            onDefaultExpandedChange(if (allExpanded) emptySet() else TerminalSection.entries.toSet())
                        },
                        labelStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                    ) {}
                    HorizontalDivider()
                    terminalOrder.forEachIndexed { index, section ->
                        val selected = section in defaultExpanded
                        SettingsRow(
                            label = section.label,
                            modifier = Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { expand ->
                                onDefaultExpandedChange(
                                    if (expand) defaultExpanded + section else defaultExpanded - section
                                )
                            }),
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                        }
                        if (index < terminalOrder.lastIndex) HorizontalDivider()
                    }
                }
                SettingsPage.THEME -> Column(Modifier.selectableGroup()) {
                    SettingsChoiceRow("Light mode", !darkMode, { onDarkModeChange(false) }, divider = true)
                    SettingsChoiceRow("Dark mode", darkMode, { onDarkModeChange(true) }, divider = false)
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
private fun SettingsGroupHeading(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    divider: Boolean,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    leading: (@Composable () -> Unit)? = null,
) {
    SettingsRow(
        label = label,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        labelStyle = labelStyle,
        leading = leading,
    ) {
        RadioButton(selected = selected, onClick = null)
    }
    if (divider) HorizontalDivider()
}

@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(label, Modifier.weight(1f).padding(vertical = 4.dp), style = labelStyle)
        if (value != null) {
            Text(
                value,
                modifier = Modifier.widthIn(max = 88.dp).padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        trailing()
    }
}

@Composable
private fun BackgroundSwatch(background: AppBackground, darkMode: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = background.resolveColor(darkMode) ?: MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {}
}

@Composable
private fun SettingsChevron() {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
}
