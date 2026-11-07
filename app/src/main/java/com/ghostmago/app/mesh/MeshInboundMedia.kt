package com.ghostmago.app.mesh

import android.util.Base64
import org.json.JSONObject

/**
 * Decode media fields from inbound mesh JSON payloads.
 */
object MeshInboundMedia {

    fun decodeImage(json: JSONObject): Pair<ByteArray, String>? {
        val b64 = json.optString("img", "")
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64, Base64.NO_WRAP) to json.optString("cap", "")
        } catch (_: Exception) {
            null
        }
    }

    fun decodeVoice(json: JSONObject): Triple<ByteArray, Boolean, Boolean>? {
        val b64 = json.optString("audio", "")
        if (b64.isEmpty()) return null
        return try {
            Triple(
                Base64.decode(b64, Base64.NO_WRAP),
                json.optBoolean("priv", false),
                json.optBoolean("call", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun decodeFile(json: JSONObject): Pair<String, ByteArray>? {
        val filename = json.optString("filename", "file")
        val b64 = json.optString("data", "")
        if (b64.isEmpty()) return null
        return try {
            filename to Base64.decode(b64, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun voiceDisplayNick(nick: String, isPriv: Boolean, isCall: Boolean): String = when {
        isCall -> "📞 $nick"
        isPriv -> "🔒 $nick"
        else -> nick
    }
}
