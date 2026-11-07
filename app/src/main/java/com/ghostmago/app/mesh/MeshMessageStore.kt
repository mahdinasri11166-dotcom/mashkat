package com.ghostmago.app.mesh

import com.ghostmago.app.ui.screens.mesh.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Capped chat buffer — keeps UI and RAM light under heavy mesh traffic.
 * Thread-safe for concurrent add from IO + UI read.
 */
class MeshMessageStore(private val maxSize: Int = 30) {
    private val items = CopyOnWriteArrayList<ChatMessage>()

    fun snapshot(): List<ChatMessage> = items.toList()

    @Synchronized
    fun add(msg: ChatMessage): List<ChatMessage> {
        items.add(msg)
        trimLocked()
        return snapshot()
    }

    @Synchronized
    fun mapUpdate(transform: (ChatMessage) -> ChatMessage): List<ChatMessage> {
        val n = items.size
        for (i in 0 until n) {
            try {
                items[i] = transform(items[i])
            } catch (_: Exception) {
            }
        }
        return snapshot()
    }

    @Synchronized
    fun replaceAll(list: List<ChatMessage>): List<ChatMessage> {
        items.clear()
        if (list.isNotEmpty()) {
            val start = (list.size - maxSize).coerceAtLeast(0)
            items.addAll(list.subList(start, list.size))
        }
        return snapshot()
    }

    private fun trimLocked() {
        while (items.size > maxSize) {
            try {
                items.removeAt(0)
            } catch (_: Exception) {
                break
            }
        }
        // Drop media payloads on older messages to free RAM
        val keepFrom = (items.size - 4).coerceAtLeast(0)
        for (i in 0 until keepFrom) {
            val m = items[i]
            if (m.audioData != null || m.imageBytes != null || m.fileData != null) {
                items[i] = m.copy(
                    audioData = null,
                    imageBytes = null,
                    fileData = null,
                    text = m.text.ifBlank {
                        when {
                            m.isVoice -> "🎤 ویس"
                            m.isFile -> "📎 فایل"
                            m.imageBytes != null -> "🖼 تصویر"
                            else -> m.text
                        }
                    }
                )
            }
        }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    fun size(): Int = items.size
}
