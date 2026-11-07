package com.ghostmago.app.mesh

import android.content.Context
import android.util.Log
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.MeshRole
import org.json.JSONObject

/**
 * Outbound builders (text/image/voice/file/private/handshake).
 * Membership approve/reject stays on MeshNetwork.
 */
internal class MeshOutbound(
    private val context: Context,
    private val myHash: String,
    private val nickname: String,
    private val groupPassword: String,
    private val role: MeshRole,
    private val hostMode: Boolean,
    private val encryptPayload: (String) -> ByteArray,
    private val encryptControl: (Int, JSONObject) -> ByteArray,
    private val sendPacket: (type: Int, target: String, payload: ByteArray, ttl: Int) -> String,
    private val emitStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "MeshOutbound"
    }

    fun sendText(text: String, targetHash: String = "", ephemeralSec: Int = 0): String {
        if (text.isBlank()) return ""
        val payload = JSONObject().apply {
            put("nick", nickname)
            put("role", role.value)
            put("text", text)
            put("ts", System.currentTimeMillis() / 1000.0)
            if (ephemeralSec > 0) put("ephemeral", ephemeralSec)
        }
        var bytes = encryptPayload(payload.toString())
        if (AppState.sealedSender) {
            bytes = SealedSender.seal(myHash, nickname, bytes, groupPassword)
            // mark sealed with empty nick outer — handle on recv
            val outer = JSONObject().apply {
                put("sealed", true)
                put("blob", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                put("ts", System.currentTimeMillis() / 1000.0)
            }
            return sendPacket(MeshPacket.TYPE_DATA, targetHash, encryptPayload(outer.toString()), MeshNetwork.DEFAULT_TTL)
        }
        return sendPacket(MeshPacket.TYPE_DATA, targetHash, bytes, MeshNetwork.DEFAULT_TTL)
    }

    fun sendBroadcast(text: String) {
        if (!role.canCommand()) return
        val payload = JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("text", text); put("ts", System.currentTimeMillis() / 1000.0)
        }
        sendPacket(MeshPacket.TYPE_BROADCAST, "*", encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
    }

    fun sendImage(jpeg: ByteArray, caption: String = ""): String {
        if (jpeg.isEmpty()) return ""
        // Large images go through chunked file path
        if (jpeg.size > 24_000) {
            return sendFileChunked("image_${System.currentTimeMillis()}.jpg", jpeg)
        }
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val payload = org.json.JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("img", b64); put("cap", caption)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        return sendPacket(MeshPacket.TYPE_IMAGE, "", encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
    }

    fun sendVoice(audio: ByteArray): String {
        if (audio.isEmpty()) return ""
        return try {
            val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("nick", nickname); put("role", role.value)
                put("audio", b64); put("ts", System.currentTimeMillis() / 1000.0)
            }
            sendPacket(MeshPacket.TYPE_VOICE, "", encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
        } catch (e: Exception) {
            Log.e(TAG, "sendVoice", e)
            ""
        }
    }

    fun sendPrivateVoice(peerHash: String, audio: ByteArray, isCall: Boolean = false): String {
        if (peerHash.isBlank() || audio.isEmpty()) return ""
        val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("audio", b64)
            put("priv", true)
            put("call", isCall)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        return sendPacket(MeshPacket.TYPE_VOICE, peerHash, encryptPayload(payload.toString()), 8)
    }

    fun sendCallSignal(
        peerHash: String,
        action: String,
        sdpType: String = "",
        sdp: String = "",
        iceMid: String = "",
        iceIndex: Int = 0,
        iceCand: String = ""
    ): String {
        if (peerHash.isBlank()) return ""
        val payload = JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("callSignal", action) // ring | accept | end | sdp | ice | busy
            if (sdpType.isNotBlank()) put("sdpType", sdpType)
            if (sdp.isNotBlank()) put("sdp", sdp)
            if (iceCand.isNotBlank()) {
                put("iceMid", iceMid)
                put("iceIndex", iceIndex)
                put("ice", iceCand)
            }
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        return sendPacket(MeshPacket.TYPE_PRIVATE, peerHash, encryptPayload(payload.toString()), 8)
    }

    fun sendFile(filename: String, data: ByteArray): String {
        if (data.size > 24_000) return sendFileChunked(filename, data)
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("filename", filename); put("data", b64)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        return sendPacket(MeshPacket.TYPE_FILE, "", encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
    }

    fun sendFileChunked(filename: String, data: ByteArray, maxBytes: Int = 8_000_000): String {
        if (data.size > maxBytes) {
            emitStatus("فایل خیلی بزرگ است (حداکثر ${maxBytes / 1_000_000}MB)")
            return ""
        }
        val chunkSize = 12_000
        val total = (data.size + chunkSize - 1) / chunkSize
        val fileId = MeshPacket.makeMsgId(myHash).take(12)
        emitStatus("ارسال تکهای: $filename — $total تکه")
        var lastId = ""
        for (i in 0 until total) {
            val from = i * chunkSize
            val to = minOf(from + chunkSize, data.size)
            val part = data.copyOfRange(from, to)
            val b64 = android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("nick", nickname); put("role", role.value)
                put("appVer", com.ghostmago.app.BuildConfig.VERSION_NAME)
                put("proto", 5)
                put("fid", fileId); put("i", i); put("n", total)
                put("filename", filename); put("data", b64)
                put("ts", System.currentTimeMillis() / 1000.0)
            }
            lastId = sendPacket(MeshPacket.TYPE_CHUNK, "", encryptPayload(payload.toString()), 8)
        }
        return lastId
    }

    fun sendPrivate(peerHash: String, text: String): Boolean {
        if (peerHash.isBlank() || text.isBlank()) return false
        val ct = PrivateSessionStore.encrypt(peerHash, text) ?: return false
        val b64 = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
        val payload = org.json.JSONObject().apply {
            put("nick", nickname)
            put("role", role.value)
            put("ct", b64)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        sendPacket(MeshPacket.TYPE_PRIVATE, peerHash, encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
        return true
    }

    fun sendX3DHBundle(peerHash: String = "") {
        val payload = org.json.JSONObject().apply {
            put("nick", nickname)
            put("role", role.value)
            put("bundle", com.ghostmago.app.mesh.PrivateSessionStore.myBundleJson())
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        sendPacket(MeshPacket.TYPE_X3DH, peerHash, encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
    }

    fun sendPrivHandshake(peerHash: String, staticPub: ByteArray, ephPub: ByteArray) {
        val payload = org.json.JSONObject().apply {
            put("nick", nickname)
            put("role", role.value)
            put("static", android.util.Base64.encodeToString(staticPub, android.util.Base64.NO_WRAP))
            put("eph", android.util.Base64.encodeToString(ephPub, android.util.Base64.NO_WRAP))
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        sendPacket(MeshPacket.TYPE_PRIV_HS, peerHash, encryptPayload(payload.toString()), MeshNetwork.DEFAULT_TTL)
    }

    fun sendAck(msgId: String, toHash: String) {
        val payload = org.json.JSONObject().apply {
            put("ack", msgId)
            put("nick", nickname)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        sendPacket(MeshPacket.TYPE_ACK, toHash, encryptControl(MeshPacket.TYPE_ACK, payload), 4)
    }

    fun sendRemoteWipe(targetHash: String = "*", reason: String = "remote") {
        if (!role.canApprove() && !hostMode) {
            emitStatus("فقط فرمانده می‌تواند پاکسازی از راه دور بفرستد")
            return
        }
        val payload = MeshJoinLogic.buildRemoteWipePayload(nickname, reason)
        sendPacket(MeshPacket.TYPE_REMOTE_WIPE, targetHash, encryptControl(MeshPacket.TYPE_REMOTE_WIPE, payload), 6)
        emitStatus("دستور پاکسازی از راه دور ارسال شد")
    }

    fun sendJoinRequest() {
        val payload = MeshJoinLogic.buildJoinRequestPayload(context, nickname, role)
        sendPacket(MeshPacket.TYPE_JOIN_REQ, "", encryptControl(MeshPacket.TYPE_JOIN_REQ, payload), MeshNetwork.DEFAULT_TTL)
    }
}
