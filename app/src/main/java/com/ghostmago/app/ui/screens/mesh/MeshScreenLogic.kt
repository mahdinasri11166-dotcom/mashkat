package com.ghostmago.app.ui.screens.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ghostmago.app.data.RetentionPolicy
import com.ghostmago.app.mesh.MeshMessageStore

/**
 * Small pure helpers extracted from MeshScreen.
 */
object MeshScreenLogic {

    fun ensurePermissions(context: Context): Boolean {
        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return need.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun trimMessages(list: List<ChatMessage>): List<ChatMessage> {
        return if (list.size > 200) list.takeLast(200) else list
    }

    fun pushMessage(
        msgStore: MeshMessageStore,
        messages: List<ChatMessage>,
        msg: ChatMessage,
    ): List<ChatMessage> {
        return msgStore.add(msg)
    }

    /** Ephemeral + retention filter used by the cleanup loop in MeshScreen. */
    fun filterExpired(messages: List<ChatMessage>, now: Long = System.currentTimeMillis()): List<ChatMessage> {
        var filtered = messages.filter { m ->
            val ephOk = m.ephemeralSec <= 0 || (now - m.time) < m.ephemeralSec * 1000L
            val retOk = !RetentionPolicy.isExpired(m.time, m.classification)
            ephOk && retOk
        }
        if (filtered.size > 30) filtered = filtered.takeLast(30)
        filtered = filtered.mapIndexed { idx, m ->
            if (idx < filtered.size - 5 && (m.audioData != null || m.imageBytes != null || m.fileData != null))
                m.copy(
                    audioData = null,
                    imageBytes = null,
                    fileData = null,
                    text = m.text.ifBlank {
                        when {
                            m.isVoice -> "🎤 ویس"
                            m.imageBytes != null -> "🖼 تصویر"
                            m.isFile -> "📎 فایل"
                            else -> m.text
                        }
                    }
                )
            else m
        }
        return filtered
    }
}
