package com.ghostmago.app.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.ghostmago.app.crypto.CryptoEngine
import com.ghostmago.app.data.AppState
import com.ghostmago.app.util.DeviceGuard
import com.ghostmago.app.util.UiThrottle
import com.ghostmago.app.orgtrust.OrgTrust
import com.ghostmago.app.orgtrust.IdentityPolicy
import com.ghostmago.app.data.MeshRole
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class MeshNetwork(
    private val context: Context,
    private val myId: String,
    private val nickname: String,
    private val groupId: String,
    private val groupPassword: String,
    private val role: MeshRole,
    /** true = created/host network (no wait); false = joined via invite and needs approval */
    private val hostMode: Boolean = true,
    private val onText: (origin: String, nick: String, text: String, hops: Int, ephemeralSec: Int) -> Unit,
    private val onVoice: (origin: String, nick: String, audio: ByteArray, hops: Int) -> Unit,
    private val onFile: (origin: String, nick: String, filename: String, data: ByteArray, hops: Int) -> Unit,
    private val onImage: (origin: String, nick: String, jpeg: ByteArray, caption: String, hops: Int) -> Unit = { _,_,_,_,_ -> },
    private val onDelivered: (msgId: String) -> Unit = {},
    private val onDeliveryFailed: (msgId: String) -> Unit = {},
    private val onJoinReq: (origin: String, nick: String, role: String) -> Unit = { _, _, _ -> },
    private val onPrivate: (origin: String, nick: String, text: String) -> Unit = { _, _, _ -> },
    private val onPrivHs: (origin: String, nick: String, staticPubB64: String, ephPubB64: String) -> Unit = { _, _, _, _ -> },
    private val onMemberUpdate: () -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    private val onJoinAccepted: (role: String) -> Unit = {},
    private val onCallSignal: (origin: String, nick: String, action: String, extra: String) -> Unit = { _, _, _, _ -> }
) {
    companion object {
        const val DEFAULT_PORT = 5599
        fun currentPort(): Int = try {
            AppState.meshPort.coerceIn(1024, 65535)
        } catch (_: Exception) { DEFAULT_PORT }
        const val MAX_PACKET = 60000
        const val BEACON_INTERVAL = 15000L
        const val ONLINE_TIMEOUT = 20000L
        /* DEFAULT_TTL */ const val DEFAULT_TTL = 8
        private const val TAG = "MeshNetwork"
    }

    private val myHash = MeshPacket.hashId(myId)
    private var socket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var internetRelay: MeshRelay? = null
    private var btBridge: BluetoothMeshBridge? = null
    private var usbRadio: UsbRadioBridge? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var membershipAccepted: Boolean = hostMode
    private val sessionCrypto = MeshSessionCrypto(groupPassword, groupId)
    private var running = false
    private var scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Mesh coroutine error", e)
            try { emitStatus("خطای شبکه (بازیابی)") } catch (_: Exception) {}
        }
    )
    private val seen = ConcurrentHashMap<String, Long>()
    private val statusThrottle = UiThrottle(700L)
    private fun emitStatus(msg: String) {
        if (!statusThrottle.accept(msg)) return
        try { onStatus(msg) } catch (_: Exception) {}
    }
    private val members = ConcurrentHashMap<String, MemberInfo>()
    private val pending = ConcurrentHashMap<String, MemberInfo>()
    private val neighbors = ConcurrentHashMap<String, NeighborInfo>()
    /** Offline store-and-forward: targetHash -> list of packets to deliver later */
    private val storeForward = ConcurrentHashMap<String, MutableList<ByteArray>>()
    private val pendingAcks = ConcurrentHashMap<String, Long>()
    private val chunkAssembler = MeshChunkAssembler()
    private val chunkMeta = ConcurrentHashMap<String, Pair<String, String>>() // fid -> filename, nick
    private val versionWarned = ConcurrentHashMap.newKeySet<String>()
    private val delivered = ConcurrentHashMap.newKeySet<String>()
    private val recentRealSends = java.util.concurrent.atomic.AtomicInteger(0)
    private val PENDING_TIMEOUT_MS = 25_000L
    private var inbound: MeshInboundHandler? = null
    private var outbound: MeshOutbound? = null

    @Synchronized
    private fun ensureInbound(): MeshInboundHandler {
        inbound?.let { return it }
        val h = MeshInboundHandler(
            context = context,
            myHash = myHash,
            nickname = nickname,
            groupId = groupId,
            groupPassword = groupPassword,
            role = role,
            hostMode = hostMode,
            scope = scope,
            sessionCrypto = sessionCrypto,
            members = members,
            pending = pending,
            neighbors = neighbors,
            seen = seen,
            pendingAcks = pendingAcks,
            delivered = delivered,
            versionWarned = versionWarned,
            chunkAssembler = chunkAssembler,
            chunkMeta = chunkMeta,
            storeForward = storeForward,
            decryptPayload = { decryptPayload(it) },
            encryptPayload = { encryptPayload(it) },
            sendPacket = { type, target, payload, ttl -> sendPacket(type, target, payload, ttl) },
            sendAck = { id, to -> sendAck(id, to) },
            relay = { relay(it) },
            flushStoreForward = { flushStoreForward(it) },
            emitStatus = { emitStatus(it) },
            isMembershipAccepted = { membershipAccepted },
            setMembershipAccepted = { membershipAccepted = it },
            onText = onText,
            onVoice = onVoice,
            onFile = onFile,
            onImage = onImage,
            onDelivered = onDelivered,
            onJoinReq = onJoinReq,
            onPrivate = onPrivate,
            onPrivHs = onPrivHs,
            onMemberUpdate = onMemberUpdate,
            onJoinAccepted = onJoinAccepted,
            onCallSignal = onCallSignal,
        )
        inbound = h
        return h
    }

    @Synchronized
    private fun ensureOutbound(): MeshOutbound {
        outbound?.let { return it }
        val o = MeshOutbound(
            context = context,
            myHash = myHash,
            nickname = nickname,
            groupPassword = groupPassword,
            role = role,
            hostMode = hostMode,
            encryptPayload = { encryptPayload(it) },
            encryptControl = { type, json -> encryptControl(type, json) },
            sendPacket = { type, target, payload, ttl -> sendPacket(type, target, payload, ttl) },
            emitStatus = { emitStatus(it) },
        )
        outbound = o
        return o
    }


    fun start() {
        if (running) return
        if (!scope.isActive) {
            scope = CoroutineScope(
                Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                    Log.e(TAG, "Mesh coroutine error", e)
                    try { emitStatus("خطای شبکه (بازیابی)") } catch (_: Exception) {}
                }
            )
            inbound = null
            outbound = null // rebuild with fresh scope
        }
        try {
            // Release previous socket / service to avoid EADDRINUSE
            try { MeshService.stop(context) } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            try { Thread.sleep(120) } catch (_: Exception) {}
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.soTimeout = 1000
            val port = currentPort()
            try {
                s.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port))
            } catch (bindEx: java.net.BindException) {
                try { s.close() } catch (_: Exception) {}
                try { Thread.sleep(300) } catch (_: Exception) {}
                val s2 = DatagramSocket(null)
                s2.reuseAddress = true
                s2.broadcast = true
                s2.soTimeout = 1000
                s2.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port))
                socket = s2
                running = true
                members[myHash] = MemberInfo(nickname, role.value, System.currentTimeMillis())
                scope.launch { recvLoop() }
                if (!AppState.radioSilence) {
                    scope.launch { beaconLoop() }
                }
                scope.launch { cleanupLoop() }
                scope.launch { coverLoop() }
                scope.launch { ackRetryLoop() }
                try {
                    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    multicastLock = wifi.createMulticastLock("mishkat_mesh").also {
                        it.setReferenceCounted(true)
                        it.acquire()
                    }
                } catch (_: Exception) {}
                MeshService.start(context)
                if (!membershipAccepted) scope.launch { joinRetryLoop() }
                emitStatus("فعال — پورت $port (بازیابی bind)")
                Log.i(TAG, "Mesh started after rebind")
                return
            }
            socket = s
            running = true
            members[myHash] = MemberInfo(nickname, role.value, System.currentTimeMillis())

            try {
                if (AppState.separateControlPlane) {
                    val cs = DatagramSocket(null)
                    cs.reuseAddress = true
                    cs.broadcast = true
                    cs.soTimeout = 1000
                    cs.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), ControlPlane.controlPort(port)))
                    controlSocket = cs
                    scope.launch { controlRecvLoop() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "control bind", e)
                emitStatus("کانال کنترل روی همان پورت داده")
            }
            scope.launch { recvLoop() }
            if (!AppState.radioSilence) {
                scope.launch { beaconLoop() }
            }
            scope.launch { cleanupLoop() }
            scope.launch { coverLoop() }
            scope.launch { ackRetryLoop() }
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("mishkat_mesh").also {
                    it.setReferenceCounted(true)
                    it.acquire()
                }
            } catch (_: Exception) {}
            MeshService.start(context)
            if (!membershipAccepted) {
                scope.launch { joinRetryLoop() }
            }
            try {
                internetRelay = MeshRelay(
                    onPacket = { data -> handlePacket(data, "relay") },
                    onStatus = { emitStatus(it) }
                ).also { if (AppState.relayEnabled && !AppState.orgBlockInternetRelay) it.start() else if (AppState.relayEnabled && AppState.orgBlockInternetRelay) emitStatus("رله اینترنت در پروفایل سازمانی مسدود است") }
                btBridge = BluetoothMeshBridge(context, { data -> handlePacket(data, "bt") }, { emitStatus(it) })
                    .also { if (AppState.useBluetoothMesh) it.start() }
                usbRadio = UsbRadioBridge(context, { data -> handlePacket(data, "usb-radio") }, { emitStatus(it) })
                    .also { if (AppState.useUsbRadioMesh) it.start() }
            } catch (_: Exception) {}
            val health = try { MeshHealth.transportSummary(context) } catch (_: Exception) { "" }
            emitStatus(
                "فعال — پورت ${currentPort()}" +
                    (if (AppState.radioSilence) " (سکوت)" else "") +
                    (if (health.isNotBlank()) " | $health" else "")
            )
            try {
                if (AppState.groupEpochAutoRotate) sessionCrypto.maybeAutoRotate()
            } catch (_: Exception) {}
            // Device posture in background — never block mesh start
            scope.launch {
                try {
                    val posture = DeviceGuard.scanPosture(context)
                    if (posture.reaction == DeviceGuard.Reaction.DISCONNECT && AppState.orgDeviceGuard) {
                        emitStatus("ضددستکاری: قطع مش — ${posture.detail}")
                        stop()
                    } else if (posture.reaction == DeviceGuard.Reaction.READ_ONLY && AppState.orgDeviceGuard) {
                        AppState.forceReadOnly = true
                        emitStatus("ضددستکاری: فقط‌خواندنی — ${posture.detail}")
                    } else if (posture.reaction == DeviceGuard.Reaction.WARN && AppState.orgDeviceGuard) {
                        emitStatus("هشدار دستگاه: ${posture.detail}")
                    }
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Mesh started epoch="+sessionCrypto.currentEpoch())
        } catch (e: Exception) {
            emitStatus(if (e is java.net.BindException || (e.message?:"").contains("EADDRINUSE")) "پورت مش در حال استفاده است — Stop بزنید یا پورت را در تنظیمات عوض کنید" else "خطا: ${e.message}")
            Log.e(TAG, "Start failed", e)
        }
    }

    /** Soft restart without clearing membership — recovery from transient radio loss. */
    fun softReconnect() {
        if (!running) {
            start()
            return
        }
        scope.launch {
            try {
                emitStatus("بازیابی کانال…")
                delay(200)
                if (!membershipAccepted) sendJoinRequest()
                if (!AppState.radioSilence) {
                    val payload = JSONObject().apply {
                        put("nick", nickname); put("role", role.value)
                        put("appVer", com.ghostmago.app.BuildConfig.VERSION_NAME)
                        put("proto", 5)
                        put("ts", System.currentTimeMillis() / 1000.0)
                    }
                    sendPacket(MeshPacket.TYPE_BEACON, "", encryptPayload(payload.toString()), ttl = 1)
                }
                emitStatus("بازیابی انجام شد — پورت ${currentPort()}")
            } catch (e: Exception) {
                emitStatus("بازیابی ناموفق: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        inbound = null
        outbound = null
        try { sessionCrypto.clear() } catch (_: Exception) {}
        try { scope.cancel() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        try { internetRelay?.stop() } catch (_: Exception) {}
        internetRelay = null
        try { btBridge?.stop() } catch (_: Exception) {}
        btBridge = null
        try { usbRadio?.stop() } catch (_: Exception) {}
        usbRadio = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        MeshService.stop(context)
        emitStatus("غیرفعال")
    }

    fun isRunning() = running
    fun offlineQueueSize(): Int = storeForward.values.sumOf { it.size }
    fun transportLabel(): String = try { TransportProfile.labelFa() } catch (_: Exception) { "—" }
    fun getMembers(): List<Pair<String, MemberInfo>> = members.map { it.key to it.value }

    /** hash, nick, role, statusFa (آنلاین/آفلاین), lastSeen */
    fun getMemberStatuses(): List<MemberStatus> {
        val now = System.currentTimeMillis()
        return members.map { (hash, info) ->
            val neigh = neighbors[hash]
            val seen = maxOf(info.lastSeen, neigh?.lastSeen ?: 0L)
            val online = hash == myHash || (now - seen < ONLINE_TIMEOUT)
            MemberStatus(hash, info.nick, info.role, if (online) "آنلاین" else "آفلاین", seen, online)
        }.sortedByDescending { it.online }
    }

    fun getPending(): List<Pair<String, MemberInfo>> = pending.map { it.key to it.value }
    fun getOnlineNeighbors(): List<Pair<String, NeighborInfo>> {
        val now = System.currentTimeMillis()
        return neighbors.filter { now - it.value.lastSeen < ONLINE_TIMEOUT }.toList()
    }

    fun sendText(text: String, targetHash: String = "", ephemeralSec: Int = 0): String { return ensureOutbound().sendText(text, targetHash, ephemeralSec)
    }

    fun sendBroadcast(text: String) { ensureOutbound().sendBroadcast(text)
    }

    fun sendImage(jpeg: ByteArray, caption: String = ""): String { return ensureOutbound().sendImage(jpeg, caption)
    }

    fun sendVoice(audio: ByteArray): String { return ensureOutbound().sendVoice(audio)
    }

    /** Private voice note or live-call chunk to one peer. */
    fun sendPrivateVoice(peerHash: String, audio: ByteArray, isCall: Boolean = false): String { return ensureOutbound().sendPrivateVoice(peerHash, audio, isCall)
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
        return ensureOutbound().sendCallSignal(peerHash, action, sdpType, sdp, iceMid, iceIndex, iceCand)
    }

    fun sendFile(filename: String, data: ByteArray): String { return ensureOutbound().sendFile(filename, data)
    }

    /** Multi-megabyte transfer via ordered chunks (~12KB payload each). */
    fun sendFileChunked(filename: String, data: ByteArray, maxBytes: Int = 8_000_000): String { return ensureOutbound().sendFileChunked(filename, data, maxBytes)
    }

    fun sendJoinRequest() { ensureOutbound().sendJoinRequest()
    }

    fun approveMember(originHash: String, newRole: String = "soldier", displayNick: String = "") {
        if (!role.canApprove() && !hostMode) return
        if (originHash.isBlank()) return
        val info = pending.remove(originHash)
        val nick = info?.nick?.ifBlank { null } ?: displayNick.ifBlank { "عضو" }
        members[originHash] = MemberInfo(nick, newRole, System.currentTimeMillis(), System.currentTimeMillis())
        // ensure not stuck in pending
        pending.remove(originHash)
        val payload = MeshJoinLogic.buildAcceptPayload(nickname, newRole)
        sendPacket(MeshPacket.TYPE_JOIN_ACCEPT, originHash, encryptControl(MeshPacket.TYPE_JOIN_ACCEPT, payload), ttl = 8)
        // also broadcast so multi-hop peers learn membership
        sendPacket(MeshPacket.TYPE_BEACON, "", encryptPayload(JSONObject().apply {
            put("nick", nickname); put("role", role.value)
            put("appVer", com.ghostmago.app.BuildConfig.VERSION_NAME)
            put("proto", 5)
            put("ts", System.currentTimeMillis() / 1000.0)
        }.toString()), ttl = 1)
        onMemberUpdate()
        emitStatus("عضو تأیید شد: $nick")
        try {
            com.ghostmago.app.data.AuditLog.append(context, "JOIN_OK", IdentityPolicy.sanitizeLog(nick))
        } catch (_: Exception) {}
    }

    fun sendRemoteWipe(targetHash: String = "*", reason: String = "remote") { ensureOutbound().sendRemoteWipe(targetHash, reason)
    }

    fun rejectMember(originHash: String) {
        if (!role.canApprove() && !hostMode) return
        pending.remove(originHash)
        val payload = MeshJoinLogic.buildRejectPayload()
        sendPacket(MeshPacket.TYPE_JOIN_REJECT, originHash, encryptControl(MeshPacket.TYPE_JOIN_REJECT, payload), ttl = 8)
        onMemberUpdate()
    }

    
    private fun sendAck(msgId: String, toHash: String) { ensureOutbound().sendAck(msgId, toHash)
    }

    fun sendPrivate(peerHash: String, text: String): Boolean { return ensureOutbound().sendPrivate(peerHash, text)
    }

    fun sendX3DHBundle(peerHash: String = "") { ensureOutbound().sendX3DHBundle(peerHash)
    }

    fun sendPrivHandshake(peerHash: String, staticPub: ByteArray, ephPub: ByteArray) { ensureOutbound().sendPrivHandshake(peerHash, staticPub, ephPub)
    }

    private fun encryptPayload(json: String): ByteArray = sessionCrypto.encryptPayload(json)

    private fun encryptControl(type: Int, json: JSONObject): ByteArray {
        try {
            if (AppState.requireControlSignatures) {
                ControlPlane.signPayload(groupId, groupPassword, type, json)
            }
        } catch (_: Exception) {}
        return encryptPayload(json.toString())
    }


    private fun decryptPayload(data: ByteArray): JSONObject? = sessionCrypto.decryptPayload(data)

    private fun sendPacket(type: Int, target: String, payload: ByteArray, ttl: Int = DEFAULT_TTL): String {
        if (!running || socket == null || payload.isEmpty()) return ""
        if (AppState.radioSilence && type == MeshPacket.TYPE_BEACON) return ""
        if (type != MeshPacket.TYPE_BEACON && type != MeshPacket.TYPE_COVER) {
            recentRealSends.incrementAndGet()
        }
        // Read-only roles cannot inject group traffic
        if (type in listOf(
                MeshPacket.TYPE_BROADCAST, MeshPacket.TYPE_DATA, MeshPacket.TYPE_VOICE,
                MeshPacket.TYPE_FILE, MeshPacket.TYPE_IMAGE, MeshPacket.TYPE_CHUNK
            ) && target.isEmpty() && !AppState.canSendGroup()
        ) {
            emitStatus("نقش شما فقط‌خواندنی است — ارسال گروهی مجاز نیست")
            return ""
        }
        val msgId = MeshPacket.makeMsgId(myHash)
        val packet = MeshPacket.build(type, ttl, msgId, myHash, target, 0, payload)
        if (type in listOf(MeshPacket.TYPE_DATA, MeshPacket.TYPE_PRIVATE, MeshPacket.TYPE_VOICE, MeshPacket.TYPE_FILE, MeshPacket.TYPE_IMAGE, MeshPacket.TYPE_CHUNK)
            && target.isNotEmpty() && target != "*"
        ) {
            pendingAcks[msgId] = System.currentTimeMillis()
        }
        // Control plane: join/ack must be instant so commander sees Approve buttons immediately
        val urgent = type in listOf(
            MeshPacket.TYPE_JOIN_REQ, MeshPacket.TYPE_JOIN_ACCEPT, MeshPacket.TYPE_JOIN_REJECT,
            MeshPacket.TYPE_ACK, MeshPacket.TYPE_BEACON, MeshPacket.TYPE_PRIV_HS, MeshPacket.TYPE_X3DH
        )
        scope.launch {
            if (AppState.randomSendDelay && !urgent && type != MeshPacket.TYPE_COVER) {
                delay(kotlin.random.Random.nextLong(50, 400))
            }
            if (!running) return@launch
            try {
                val isCtrl = ControlPlane.isControl(type)
                val dataSock = socket ?: return@launch
                val ctrlSock = controlSocket
                val sock = if (isCtrl && AppState.separateControlPlane && ctrlSock != null) ctrlSock else dataSock
                val destPort = if (isCtrl && AppState.separateControlPlane) ControlPlane.controlPort(currentPort()) else currentPort()
                try { CoverTrafficProfile.noteRealPacket(packet.size) } catch (_: Exception) {}
                // Unicast if we know peer IP (Wi-Fi Direct / same LAN neighbor)
                val known = if (target.isNotEmpty() && target != "*") neighbors[target] else null
                if (known != null && known.ip != "?" && known.ip.isNotBlank()) {
                    try {
                        sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(known.ip), destPort))
                    } catch (_: Exception) {
                        sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), destPort))
                    }
                } else {
                    // Broadcast + opportunistic unicast to known peers (OEM-friendlier)
                    sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), destPort))
                    for ((_, ninfo) in neighbors) {
                        if (ninfo.ip.isNotBlank() && ninfo.ip != "?") {
                            try {
                                sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(ninfo.ip), destPort))
                            } catch (_: Exception) {}
                        }
                    }
                    if (target.isNotEmpty() && target != "*" && known == null &&
                        type in listOf(MeshPacket.TYPE_DATA, MeshPacket.TYPE_PRIVATE, MeshPacket.TYPE_VOICE, MeshPacket.TYPE_FILE, MeshPacket.TYPE_IMAGE)
                    ) {
                        storeForward.getOrPut(target) { mutableListOf() }.add(packet)
                        val q = storeForward[target]!!
                        while (q.size > AppState.offlineQueueMax.coerceIn(5, 100)) q.removeAt(0)
                        // expire packets older than 10 min marked by parallel map if present
                        emitStatus("صف آفلاین: پیام ذخیره شد (منتظر برگشت همسال)")
                    }
                }
                if (TransportProfile.allowWifi()) {
                    try { internetRelay?.send(packet) } catch (_: Exception) {}
                }
                if (TransportProfile.allowBluetooth()) {
                    try { btBridge?.send(packet) } catch (_: Exception) {}
                }
                if (TransportProfile.allowUsbRadio()) {
                    try { usbRadio?.send(packet) } catch (_: Exception) {}
                }
            } catch (e: Exception) { Log.e(TAG, "Send failed", e) }
        }
        return msgId
    }

    private suspend fun recvLoop() {
        val buf = ByteArray(MAX_PACKET)
        while (running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                socket?.receive(dp)
                if (!running) break
                if (dp.length > 0) {
                    handlePacket(dp.data.copyOf(dp.length), dp.address.hostAddress ?: "?")
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) delay(30)
            }
        }
    }

    private fun handlePacket(data: ByteArray, ip: String) {
        if (!running || data.isEmpty()) return
        ensureInbound().handle(data, ip)
    }

    private fun flushStoreForward(peerHash: String) {
        val list = storeForward.remove(peerHash) ?: return
        val info = neighbors[peerHash] ?: return
        scope.launch {
            for (packet in list) {
                try {
                    socket?.send(DatagramPacket(packet, packet.size, InetAddress.getByName(info.ip), currentPort()))
                    delay(300)
                } catch (_: Exception) {}
            }
            if (list.isNotEmpty()) emitStatus("تحویل ${list.size} پیام ذخیرهشده")
        }
    }

    private fun relay(p: MeshPacket.Parsed) {
        val newTtl = p.ttl - 1
        val newHops = p.hops + 1
        if (newTtl <= 0 || newHops > 7) return
        val packet = MeshPacket.build(p.type, newTtl, p.msgId, p.originHash, p.targetHash, newHops, p.payload, p.flags)
        // Multi-hop store-and-forward: keep copy for target if not online here
        if (p.targetHash.isNotEmpty() && p.targetHash != "*" && !neighbors.containsKey(p.targetHash)
            && p.type in listOf(MeshPacket.TYPE_DATA, MeshPacket.TYPE_PRIVATE, MeshPacket.TYPE_VOICE, MeshPacket.TYPE_FILE)
        ) {
            storeForward.getOrPut(p.targetHash) { mutableListOf() }.add(packet)
            if (storeForward[p.targetHash]!!.size > 30) storeForward[p.targetHash]!!.removeAt(0)
        }
        try {
            val known = neighbors[p.targetHash]
            if (known != null && known.ip.isNotBlank() && known.ip != "?") {
                socket?.send(DatagramPacket(packet, packet.size, InetAddress.getByName(known.ip), currentPort()))
            } else {
                socket?.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), currentPort()))
            }
        } catch (_: Exception) {}
    }

    private suspend fun joinRetryLoop() {
        var tries = 0
        emitStatus("در انتظار تأیید فرمانده...")
        // First request immediately (no wait), then fast retries so commander UI appears quickly
        while (running && !membershipAccepted && tries < 50) {
            try {
                sendJoinRequest()
                emitStatus("درخواست عضویت ارسال شد — منتظر تأیید فرمانده (${tries + 1})")
            } catch (_: Exception) {
            }
            // 1s for first 10 tries, then 2.5s
            delay(if (tries < 5) 1200L else if (tries < 15) 3000L else 6000L)
            tries++
        }
        if (running && !membershipAccepted) {
            emitStatus("فرمانده پاسخ نداد — درخواست تکرار نشد")
        }
    }

    private suspend fun beaconLoop() {
        while (running) {
            val interval = try {
                MeshHealth.beaconIntervalMs(context)
            } catch (_: Exception) {
                BEACON_INTERVAL
            }
            delay(interval + Random.nextLong(-800, 800))
            if (AppState.radioSilence) continue
            try {
                if (AppState.groupEpochAutoRotate) sessionCrypto.maybeAutoRotate()
            } catch (_: Exception) {}
            val payload = JSONObject().apply {
                put("nick", nickname); put("role", role.value)
                put("appVer", com.ghostmago.app.BuildConfig.VERSION_NAME)
                put("proto", 6)
                put("ts", System.currentTimeMillis() / 1000.0)
                put("epoch", sessionCrypto.currentEpoch())
            }
            sendPacket(MeshPacket.TYPE_BEACON, "", encryptPayload(payload.toString()), ttl = 1)
        }
    }

    private suspend fun coverLoop() {
        while (running) {
            // Adaptive: more real traffic → less cover interval stretch; idle → denser cover
            val base = AppState.coverIntervalSec.coerceIn(20, 180)
            val real = recentRealSends.getAndSet(0)
            val adaptSec = when {
                real >= 5 -> base * 2
                real >= 2 -> base
                else -> maxOf(5, base / 2)
            }
            delay(CoverTrafficProfile.nextDelayMs(adaptSec))
            if (!running || !AppState.coverTraffic || AppState.radioSilence) continue
            try {
                val sz = CoverTrafficProfile.nextCoverSize()
                val junk = ByteArray(sz)
                java.security.SecureRandom().nextBytes(junk)
                sendPacket(MeshPacket.TYPE_COVER, "", junk, ttl = 1)
            } catch (_: Exception) {}
        }
    }

    private suspend fun ackRetryLoop() {
        while (running) {
            delay(12000)
            val now = System.currentTimeMillis()
            for ((msgId, sentAt) in pendingAcks.toMap()) {
                if (now - sentAt > 25_000) {
                    pendingAcks.remove(msgId)
                    if (!delivered.contains(msgId)) {
                        scope.launch(Dispatchers.Main) { onDeliveryFailed(msgId) }
                    }
                }
            }
            if (neighbors.isNotEmpty() && storeForward.isNotEmpty()) {
                val sock = socket
                if (sock != null) {
                    for ((target, packets) in storeForward.toMap()) {
                        val n = neighbors[target]
                        if (n != null && n.ip.isNotBlank() && n.ip != "?") {
                            val copy = packets.toList()
                            storeForward.remove(target)
                            for (packet in copy) {
                                try {
                                    sock.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(n.ip), currentPort()))
                                } catch (_: Exception) {}
                            }
                            emitStatus("صف آفلاین ارسال شد: ${copy.size}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun controlRecvLoop() {
        val buf = ByteArray(MAX_PACKET)
        while (running) {
            try {
                val dp = java.net.DatagramPacket(buf, buf.size)
                controlSocket?.receive(dp)
                handlePacket(dp.data.copyOf(dp.length), dp.address.hostAddress ?: "?")
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) kotlinx.coroutines.delay(40)
            }
        }
    }

    private suspend fun cleanupLoop() {
        while (running) {
            delay(20_000)
            val now = System.currentTimeMillis()
            try {
                seen.entries.removeIf { now - it.value > 90_000 }
                if (seen.size > 2000) {
                    val oldest = seen.entries.sortedBy { it.value }.take(seen.size - 1500)
                    oldest.forEach { seen.remove(it.key) }
                }
                neighbors.entries.removeIf { now - it.value.lastSeen > ONLINE_TIMEOUT * 3 }
                // Drop stale pending memberships (10 min)
                pending.entries.removeIf { now - maxOf(it.value.joinedAt, it.value.lastSeen) > 600_000 }
                if (pendingAcks.size > 80) {
                    val old = pendingAcks.entries.sortedBy { it.value }.take(pendingAcks.size - 50)
                    old.forEach { pendingAcks.remove(it.key) }
                }
                // Cap delivered / versionWarned sets to avoid unbounded growth
                if (delivered.size > 1500) {
                    val excess = delivered.size - 1000
                    val it = delivered.iterator()
                    var n = 0
                    while (it.hasNext() && n < excess) {
                        it.next(); it.remove(); n++
                    }
                }
                if (versionWarned.size > 200) {
                    versionWarned.clear()
                }
                for ((k, q) in storeForward.toMap()) {
                    if (q.isEmpty()) {
                        storeForward.remove(k)
                    } else if (q.size > 32) {
                        // keep newest ~32 offline-queued packets per peer
                        while (q.size > 32) {
                            try { q.removeAt(0) } catch (_: Exception) { break }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
