package com.ghostmago.app.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.ui.theme.*

@Composable
fun SettingsSection(title: String) {
    Spacer(Modifier.height(6.dp))
    Text(title, color = Primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = Border,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Primary,
    focusedContainerColor = CardDark,
    unfocusedContainerColor = CardDark,
    focusedLabelColor = Primary,
    unfocusedLabelColor = TextSecondary
)
