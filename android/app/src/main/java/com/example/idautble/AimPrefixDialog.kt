package com.example.idautble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ExpandLess

// ============================
// DATA MODEL
// ============================

data class AimPrefix(
    val code: String,
    val description: String,
    val family: String
)

// ============================
// DATA LIST
// ============================

private val aimPrefixList = listOf(

    // CODE 128
    AimPrefix("]C0", "Standard", "CODE 128"),
    AimPrefix("]C1", "GS1-128 (FNC1 first pos)", "CODE 128"),
    AimPrefix("]C2", "FNC1 second pos", "CODE 128"),

    // CODE 39
    AimPrefix("]A0", "Standard", "CODE 39"),
    AimPrefix("]A1", "Check validated", "CODE 39"),
    AimPrefix("]A3", "Check validated + stripped", "CODE 39"),
    AimPrefix("]A4", "Full ASCII", "CODE 39"),
    AimPrefix("]A5", "Full ASCII + check", "CODE 39"),
    AimPrefix("]A7", "Full ASCII + check + stripped", "CODE 39"),

    // INTERLEAVED 2 OF 5
    AimPrefix("]I0", "No check digit", "INTERLEAVED 2 OF 5"),
    AimPrefix("]I1", "Check validated", "INTERLEAVED 2 OF 5"),
    AimPrefix("]I3", "Check validated + stripped", "INTERLEAVED 2 OF 5"),

    // EAN
    AimPrefix("]E0", "No supplement", "EAN-13 / EAN-8"),
    AimPrefix("]E1", "+ 2-digit supplement", "EAN-13 / EAN-8"),
    AimPrefix("]E2", "+ 5-digit supplement", "EAN-13 / EAN-8"),
    AimPrefix("]E3", "Combined", "EAN-13 / EAN-8"),

    // DATA MATRIX
    AimPrefix("]d1", "ECC200", "DATA MATRIX"),
    AimPrefix("]d2", "GS1 (FNC1 1st/5th)", "DATA MATRIX"),
    AimPrefix("]d3", "FNC1 2nd/6th", "DATA MATRIX"),
    AimPrefix("]d4", "ECI", "DATA MATRIX"),
    AimPrefix("]d5", "FNC1 + ECI", "DATA MATRIX"),
    AimPrefix("]d6", "FNC1(2nd/6th) + ECI", "DATA MATRIX"),

    // QR
    AimPrefix("]Q0", "Model 1", "QR CODE"),
    AimPrefix("]Q1", "Model 2 / MicroQR", "QR CODE"),
    AimPrefix("]Q2", "Model 2 + ECI", "QR CODE"),
    AimPrefix("]Q3", "GS1 QR (FNC1)", "QR CODE"),
    AimPrefix("]Q4", "ECI + FNC1", "QR CODE"),
    AimPrefix("]Q5", "FNC1 second pos", "QR CODE"),
    AimPrefix("]Q6", "ECI + FNC1 second pos", "QR CODE"),
)

// ============================
// DIALOG
// ============================

@Composable
fun AimPrefixDialog(
    onDismiss: () -> Unit,
    onPrefixSelected: (String) -> Unit
) {

    val grouped = aimPrefixList.groupBy { it.family }
    var expandedFamily by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

                grouped.forEach { (family, itemsList) ->

                    item {

                        val isExpanded = expandedFamily == family

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedFamily =
                                        if (isExpanded) null else family
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = family,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )

                            Icon(
                                imageVector = if (isExpanded)
                                    Icons.Default.ExpandLess
                                else
                                    Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }


                        if (isExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                itemsList.forEach { prefix ->

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {

                                        OutlinedButton(
                                            onClick = {
                                                onPrefixSelected(prefix.code)
                                                onDismiss()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                            border = ButtonDefaults.outlinedButtonBorder
                                        ) {
                                            Text(
                                                text = prefix.code,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }

                                        Spacer(Modifier.width(8.dp))

                                        Text(
                                            text = prefix.description,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Divider()
                    }
                }
            }
        }
    )
}