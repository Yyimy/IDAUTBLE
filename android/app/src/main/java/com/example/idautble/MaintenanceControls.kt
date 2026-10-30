package com.example.idautble

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MaintenanceActionTone(val colour: Color) {
    SUCCESS(Color(0xFF2E7D32)),
    WARNING(Color(0xFFBF5600)),
    DANGER(Color(0xFFC62828)),
    INFO(Color(0xFF1976D2)),
}

@Composable
fun MaintenanceActionButton(
    text: String,
    onClick: () -> Unit,
    tone: MaintenanceActionTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = tone.colour,
                contentColor = Color.White,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

@Composable
fun MaintenanceAccessBadge(authorised: Boolean, label: String) {
    Surface(
        color = if (authorised) Color(0xFFE8F5E9) else Color(0xFFECEFF1),
        contentColor = if (authorised) Color(0xFF1B5E20) else Color(0xFF455A64),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
