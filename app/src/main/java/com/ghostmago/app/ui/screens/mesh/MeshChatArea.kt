package com.ghostmago.app.ui.screens.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghostmago.app.ui.theme.SurfaceDark
import com.ghostmago.app.util.VoiceManager
import java.text.SimpleDateFormat

/**
 * Message list + composer for an active mesh session.
 * Extracted from MeshScreen to shrink the main screen file.
 */
@Composable
fun MeshChatArea(
    messages: List<ChatMessage>,
    chatTab: Int,
    listState: LazyListState,
    timeFmt: SimpleDateFormat,
    voiceManager: VoiceManager,
    inputText: String,
    onInputChange: (String) -> Unit,
    isRecording: Boolean,
    onAttachFile: () -> Unit,
    onPickImage: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    listModifier: Modifier = Modifier,
) {
    val visible = messages.filter { m ->
        val isPrivate = m.nick.contains("🔒") || m.text.contains("خصوصی")
        if (chatTab == 1) isPrivate else !isPrivate
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(listModifier)
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(visible, key = { it.id }) { msg ->
            MeshBubble(msg, timeFmt, voiceManager)
        }
    }

    Surface(color = SurfaceDark, tonalElevation = 4.dp) {
        MeshChatComposer(
            inputText = inputText,
            onInputChange = onInputChange,
            isRecording = isRecording,
            onAttachFile = onAttachFile,
            onPickImage = onPickImage,
            onMicClick = onMicClick,
            onSendClick = onSendClick,
        )
    }
}
