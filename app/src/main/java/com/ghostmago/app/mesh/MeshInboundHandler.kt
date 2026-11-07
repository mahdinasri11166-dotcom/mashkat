package com.ghostmago.app.mesh

import android.content.Context
import android.util.Base64
import com.ghostmago.app.crypto.X3DH
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.MeshRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbound packet dispatcher — extracted from MeshNetwork to keep the core class smaller.
 * Holds no long-lived sockets; all mutable maps are shared with MeshNetwork.
 */
internal class MeshInboundHandler(
    private val context: Context,
    private val myHash: String,
    private val nickname: String,
    private val groupId: String,
    private val groupPassword: String,
    private val role: MeshRole,
    private val hostMode: Boolean,
    private val scope: CoroutineScope,
    private val sessionCrypto: MeshSessionCrypto,
    private val members: ConcurrentHashMap<String, MemberInfo>,
    private val pending: ConcurrentHashMap<String, MemberInfo>,
    private val neighbors: ConcurrentHashMap<String, NeighborInfo>,
    private val seen: ConcurrentHashMap<String, Long>,
    private val pendingAcks: ConcurrentHashMap<String, Long>,
    private val delivered: MutableSet<String>,
    private val versionWarned: MutableSet<String>,
    private val chunkAssembler: MeshChunkAssembler,
    private val chunkMeta: ConcurrentHashMap<String, Pair<String, String>>,
    private val storeForward: ConcurrentHashMap<String, MutableList<ByteArray>>,
    private val decryptPayload: (ByteArray) -> JSONObject?,
    private val encryptPayload: (String) -> ByteArray,
    private val sendPacket: (type: Int, target: String, payload: ByteArray, ttl: Int) -> String,
    private val sendAck: (msgId: String, toHash: String) -> Unit,
    private val relay: (MeshPacket.Parsed) -> Unit,
    private val flushStoreForward: (peerHash: String) -> Unit,
    private val emitStatus: (String) -> Unit,
    private val isMembershipAccepted: () -> Boolean,
    private val setMembershipAccepted: (Boolean) -> Unit,
    private val onText: (origin: String, nick: String, text: String, hops: Int, ephemeralSec: Int) -> Unit,
    private val onVoice: (origin: String, nick: String, audio: ByteArray, hops: Int) -> Unit,
    private val onFile: (origin: String, nick: String, filename: String, data: ByteArray, hops: Int) -> Unit,
    private val onImage: (origin: String, nick: String, jpeg: ByteArray, caption: String, hops: Int) -> Unit,
    private val onDelivered: (msgId: String) -> Unit,
    private val onJoinReq: (origin: String, nick: String, role: String) -> Unit,
    private val onPrivate: (origin: String, nick: String, text: String) -> Unit,
    private val onPrivHs: (origin: String, nick: String, staticPubB64: String, ephPubB64: String) -> Unit,
    private val onMemberUpdate: () -> Unit,
    private val onJoinAccepted: (role: String) -> Unit,
    private val onCallSignal: (origin: String, nick: String, action: String, extra: String) -> Unit,
) {
    fun handle(data: ByteArray, ip: String) {
        try {
            if (data.isEmpty() || data.size > MeshNetwork.MAX_PACKET + 4096) return
    val p = MeshPacket.parse(data) ?: return
    if (p.originHash == myHash) return
    val now = System.currentTimeMillis()
    val skew = if (ControlPlane.isControl(p.type)) AppState.controlAntiReplaySec else AppState.antiReplayWindowSec
    if (kotlin.math.abs(now / 1000.0 - p.timestamp) > skew) return
    // stronger anti-replay key includes type
    val seenKey = p.msgId + ":" + p.type
    if (seen.putIfAbsent(seenKey, now) != null) return
    if (p.type == MeshPacket.TYPE_COVER) return

    val json = decryptPayload(p.payload) ?: return
    if (ControlPlane.isControl(p.type) && AppState.requireControlSignatures) {
        try {
            val verr = ControlPlane.verifyPayload(groupId, groupPassword, p.type, json, AppState.controlAntiReplaySec.toLong())
            if (verr != null) {
                emitStatus("بسته کنترل رد شد: $verr")
                return
            }
        } catch (_: Exception) {
            emitStatus("بسته کنترل: خطای تأیید امضا")
            return
        }
    }
    val nick = json.optString("nick", "?")
    neighbors[p.originHash] = NeighborInfo(nick, now, ip, p.hops + 1)
    members[p.originHash]?.let { info ->
        members[p.originHash] = info.copy(lastSeen = now, nick = nick.ifBlank { info.nick })
    }
    // Protocol / app version check
    val remoteVer = json.optString("appVer", "")
    if (remoteVer.isNotBlank() && remoteVer != com.ghostmago.app.BuildConfig.VERSION_NAME) {
        if (versionWarned.add(p.originHash)) {
            emitStatus("هشدار نسخه: $nick روی $remoteVer است — شما ${com.ghostmago.app.BuildConfig.VERSION_NAME}")
        }
        // Hard block private-like types if versions differ
        if (p.type in listOf(
                MeshPacket.TYPE_PRIVATE, MeshPacket.TYPE_PRIV_HS, MeshPacket.TYPE_X3DH
            )
        ) {
            emitStatus("چت خصوصی با نسخه ناهمسان مسدود شد")
            return
        }
    }
    flushStoreForward(p.originHash)

    when (p.type) {
        MeshPacket.TYPE_BEACON -> {
            try {
                val pe = json.optInt("epoch", -1)
                if (pe >= 0) sessionCrypto.setEpoch(pe)
            } catch (_: Exception) {}
            if (members.containsKey(p.originHash)) {
                val prev = members[p.originHash]!!
                members[p.originHash] = prev.copy(lastSeen = now, nick = nick.ifBlank { prev.nick })
                onMemberUpdate()
            } else if (!pending.containsKey(p.originHash) && p.originHash != myHash) {
                pending[p.originHash] = MemberInfo(nick.ifBlank { "کاربر" }, json.optString("role", "recruit"), now, now)
                if (role.canApprove() || hostMode) {
                    scope.launch(Dispatchers.Main) {
                        onJoinReq(p.originHash, nick.ifBlank { "کاربر" }, json.optString("role", "recruit"))
                    }
                }
                onMemberUpdate()
            } else {
                pending[p.originHash]?.let {
                    pending[p.originHash] = it.copy(lastSeen = now, nick = nick.ifBlank { it.nick })
                }
            }
        }
        MeshPacket.TYPE_JOIN_REQ -> {
            if ((role.canApprove() || hostMode) && p.originHash != myHash) {
                val r = json.optString("role", "recruit")
                val trust = MeshJoinLogic.evaluateJoinTrust(
                    context, json, groupId, nick.ifBlank { "کاربر" }, r
                )
                trust.statusNote?.let { emitStatus(it) }
                val display = trust.displayNick
                pending[p.originHash] = MemberInfo(display, trust.role, now, now)
                scope.launch(Dispatchers.Main) {
                    onJoinReq(p.originHash, display, r)
                }
                onMemberUpdate()
            } else if (p.ttl > 1) {
                relay(p)
            }
        }
        MeshPacket.TYPE_JOIN_ACCEPT -> {
            if (p.targetHash == myHash) {
                setMembershipAccepted(true)
                members[myHash] = MemberInfo(nickname, json.optString("role", role.value), now, now)
                onMemberUpdate()
                emitStatus("عضویت تأیید شد — به شبکه متصل شدید")
                scope.launch(Dispatchers.Main) {
                    onJoinAccepted(json.optString("role", role.value))
                }
            }
        }
        MeshPacket.TYPE_REMOTE_WIPE -> {
            val cmd = json.optString("cmd", "")
            if (cmd == "WIPE") {
                // Target me or broadcast from commander-rank peer
                val forMe = p.targetHash.isEmpty() || p.targetHash == "*" || p.targetHash == myHash
                if (forMe) {
                    emitStatus("دستور پاکسازی از راه دور دریافت شد")
                    scope.launch(Dispatchers.Main) {
                        try {
                            com.ghostmago.app.util.SecurityUtils.panicWipe(context)
                        } catch (_: Exception) {}
                    }
                } else if (p.ttl > 1) relay(p)
            }
        }
        MeshPacket.TYPE_JOIN_REJECT -> {
            if (p.targetHash == myHash) {
                setMembershipAccepted(false)
                emitStatus("عضویت رد شد: ${json.optString("reason", "بدون توضیح")}")
            }
        }
        MeshPacket.TYPE_DATA, MeshPacket.TYPE_BROADCAST -> {
            if (p.targetHash.isEmpty() || p.targetHash == "*" || p.targetHash == myHash) {
                var origin = p.originHash
                var displayNick = nick
                var text = json.optString("text", "")
                val eph = json.optInt("ephemeral", 0)
                if (json.optBoolean("sealed", false)) {
                    try {
                        val blob = android.util.Base64.decode(json.optString("blob"), android.util.Base64.NO_WRAP)
                        val opened = SealedSender.open(blob, groupPassword)
                        if (opened != null) {
                            origin = opened.originHash
                            displayNick = opened.nick
                            val inner = decryptPayload(opened.payload)
                            text = inner?.optString("text", "") ?: ""
                        }
                    } catch (_: Exception) {}
                }
                if (text.isNotEmpty()) {
                    scope.launch(Dispatchers.Main) {
                        onText(origin, displayNick, text, p.hops, eph)
                        MeshNotifier.notifyMessage(context, displayNick, text.take(80))
                    }
                    sendAck(p.msgId, origin)
                }
            } else if (p.ttl > 1) relay(p)
        }
        MeshPacket.TYPE_IMAGE -> {
            if (p.targetHash.isEmpty() || p.targetHash == myHash) {
                MeshInboundMedia.decodeImage(json)?.let { (jpeg, cap) ->
                    scope.launch(Dispatchers.Main) { onImage(p.originHash, nick, jpeg, cap, p.hops) }
                    sendAck(p.msgId, p.originHash)
                }
            } else if (p.ttl > 1) relay(p)
        }
        MeshPacket.TYPE_VOICE -> {
            if (p.targetHash.isEmpty() || p.targetHash == myHash || p.targetHash == "*") {
                MeshInboundMedia.decodeVoice(json)?.let { (audio, isPrivFlag, isCall) ->
                    val isPriv = isPrivFlag || p.targetHash == myHash
                    val display = MeshInboundMedia.voiceDisplayNick(nick, isPriv, isCall)
                    scope.launch(Dispatchers.Main) { onVoice(p.originHash, display, audio, p.hops) }
                }
            } else if (p.ttl > 1) relay(p)
        }
        MeshPacket.TYPE_FILE -> {
            if (p.targetHash.isEmpty() || p.targetHash == myHash) {
                MeshInboundMedia.decodeFile(json)?.let { (filename, fileData) ->
                    scope.launch(Dispatchers.Main) { onFile(p.originHash, nick, filename, fileData, p.hops) }
                }
            } else if (p.ttl > 1) relay(p)
        }

        MeshPacket.TYPE_CHUNK -> {
            if (p.targetHash.isEmpty() || p.targetHash == "*" || p.targetHash == myHash) {
                when (val cr = chunkAssembler.offer(
                    json.optString("fid", ""),
                    json.optInt("i", -1),
                    json.optInt("n", 0),
                    json.optString("filename", "file.bin"),
                    nick,
                    json.optString("data", "")
                )) {
                    is MeshChunkAssembler.Result.Complete -> {
                        scope.launch(Dispatchers.Main) {
                            onFile(p.originHash, cr.nick, cr.filename, cr.data, p.hops)
                        }
                        emitStatus("فایل کامل دریافت شد: ${cr.filename} (${cr.data.size} بایت)")
                        sendAck(p.msgId, p.originHash)
                    }
                    is MeshChunkAssembler.Result.Progress ->
                        emitStatus("تکه ${cr.got}/${cr.total} از ${cr.filename}")
                    else -> {}
                }
            } else if (p.ttl > 1) relay(p)
        }

        MeshPacket.TYPE_PRIVATE -> {
            if (p.targetHash == myHash) {
                val signal = json.optString("callSignal", "")
                if (signal.isNotEmpty()) {
                    val extra = JSONObject().apply {
                        put("sdpType", json.optString("sdpType", ""))
                        put("sdp", json.optString("sdp", ""))
                        put("iceMid", json.optString("iceMid", ""))
                        put("iceIndex", json.optInt("iceIndex", 0))
                        put("ice", json.optString("ice", ""))
                    }.toString()
                    scope.launch(Dispatchers.Main) {
                        onCallSignal(p.originHash, nick.ifBlank { "کاربر" }, signal, extra)
                        val msg = when (signal) {
                            "ring" -> "📞 تماس ورودی از $nick"
                            "accept" -> "تماس پذیرفته شد"
                            "end" -> "تماس پایان یافت"
                            "busy" -> "مشغول"
                            "sdp", "ice" -> ""
                            else -> "سیگنال تماس: $signal"
                        }
                        if (msg.isNotEmpty()) {
                            onPrivate(p.originHash, nick, msg)
                            emitStatus(msg)
                        }
                    }
                } else {
                    val b64 = json.optString("ct", "")
                    if (b64.isNotEmpty()) {
                        if (PrivateSessionStore.get(p.originHash) == null) {
                            try {
                                PrivateSessionStore.startSymmetric(myHash, p.originHash, nick, groupPassword)
                            } catch (_: Exception) {}
                        }
                        try {
                            val ct = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            val plain = PrivateSessionStore.decrypt(p.originHash, ct)
                            if (plain != null) {
                                scope.launch(Dispatchers.Main) { onPrivate(p.originHash, nick, plain) }
                                sendAck(p.msgId, p.originHash)
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else if (p.ttl > 1) relay(p)
        }
        MeshPacket.TYPE_ACK -> {
            if (p.targetHash == myHash || p.targetHash.isEmpty()) {
                val ackId = json.optString("ack", p.msgId)
                pendingAcks.remove(ackId)
                delivered.add(ackId)
                scope.launch(Dispatchers.Main) { onDelivered(ackId) }
            }
        }
        MeshPacket.TYPE_X3DH -> {
            val bundleJson = json.optString("bundle", "")
            if (bundleJson.isNotEmpty()) {
                val bundle = PrivateSessionStore.parseBundle(bundleJson)
                if (bundle != null && (p.targetHash.isEmpty() || p.targetHash == myHash)) {
                    try {
                        // Alice path if we don't have session yet or upgrade
                        if (PrivateSessionStore.get(p.originHash) == null) {
                            val (sess, eph) = PrivateSessionStore.startX3DHAlice(myHash, p.originHash, nick, bundle)
                            // send eph back as PRIV_HS style for Bob
                            val resp = org.json.JSONObject().apply {
                                put("nick", nickname)
                                put("ik", android.util.Base64.encodeToString(
                                    com.ghostmago.app.crypto.X3DH.bundle(PrivateSessionStore.local()).identityPub,
                                    android.util.Base64.NO_WRAP))
                                put("eph", android.util.Base64.encodeToString(eph, android.util.Base64.NO_WRAP))
                                put("ts", System.currentTimeMillis() / 1000.0)
                            }
                            sendPacket(MeshPacket.TYPE_PRIV_HS, p.originHash, encryptPayload(resp.toString()), MeshNetwork.DEFAULT_TTL)
                        }
                    } catch (_: Exception) {
                        // fallback ignore
                    }
                }
            }
        }
        MeshPacket.TYPE_PRIV_HS -> {
            if (p.targetHash == myHash) {
                val st = json.optString("static", json.optString("ik", ""))
                val ep = json.optString("eph", "")
                scope.launch(Dispatchers.Main) { onPrivHs(p.originHash, nick, st, ep) }
            } else if (p.ttl > 1) relay(p)
        }
    }

    } catch (e: Exception) {
        try { emitStatus("بسته رد شد") } catch (_: Exception) {}
    }
}
    }
