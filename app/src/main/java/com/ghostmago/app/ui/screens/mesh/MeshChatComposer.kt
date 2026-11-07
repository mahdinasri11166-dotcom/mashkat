package com.ghostmago.app.ui.screens.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ghostmago.app.ui.theme.BgDark
import com.ghostmago.app.ui.theme.CardDark
import com.ghostmago.app.ui.theme.Danger
import com.ghostmago.app.ui.theme.Primary
import com.ghostmago.app.ui.theme.Secondary
import com.ghostmago.app.ui.theme.TextPrimary
import com.ghostmago.app.ui.theme.TextSecondary

@Composable
fun MeshChatComposer(
    inputText: String,
    onInputChange: (String) -> Unit,
    isRecording: Boolean,
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onAttachFile,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(Icons.Default.AttachFile, null, tint = Primary)
        }
        IconButton(
            onClick = onPickImage,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(Icons.Default.Image, null, tint = Primary)
        }
        IconButton(
            onClick = onMicClick,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isRecording) Danger else CardDark)
        ) {
            Icon(
                if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                null,
                tint = if (isRecording) TextPrimary else Secondary
            )
        }
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("پیام…", color = TextSecondary) },
            colors = meshFieldColors(),
            shape = RoundedCornerShape(24.dp),
            maxLines = 3
        )
        IconButton(
            onClick = onSendClick,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Primary)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = BgDark)
        }
    }
}
