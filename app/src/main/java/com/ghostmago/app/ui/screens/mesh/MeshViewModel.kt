package com.ghostmago.app.ui.screens.mesh

import android.app.Application
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.AuditLog
import com.ghostmago.app.data.ChatContacts
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.data.RoomStore
import com.ghostmago.app.data.VerifiedPeers
import com.ghostmago.app.mesh.InviteQr
import com.ghostmago.app.mesh.MemberStatus
import com.ghostmago.app.mesh.MeshMessageStore
import com.ghostmago.app.mesh.MeshModule
import com.ghostmago.app.mesh.MeshNetwork
import com.ghostmago.app.mesh.MeshPacket
import com.ghostmago.app.mesh.PrivateSessionStore
import com.ghostmago.app.util.MeshConnectivity
import com.ghostmago.app.util.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds mesh session state + start/stop/send logic.
 * Extracted from MeshScreen so the Composable stays UI-focused.
 */
class MeshViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx get() = getApplication<Application>()

    var nickname by mutableStateOf(
        AppState.lastMeshNickname.ifBlank { AppState.nickname }.ifBlank { "کاربر" }
    )
    var groupId by mutableStateOf(
        AppState.lastGroupId.ifBlank { AppState.groupId.ifBlank { "default" } }
    )
    var groupPassword by mutableStateOf(
        AppState.lastGroupPassword.ifBlank { AppState.pendingMeshPassword }
    )
    var myId by mutableStateOf("")
    var isSetup by mutableStateOf(false)
    var mesh by mutableStateOf<MeshNetwork?>(null)
        private set
    var status by mutableStateOf("غیرفعال")
    val msgStore = MeshMessageStore(30)
    var messages by mutableStateOf(listOf<ChatMessage>())
    var inputText by mutableStateOf("")
    var isRecording by mutableStateOf(false)
    var memberCount by mutableIntStateOf(1)
    var onlineCount by mutableIntStateOf(0)
    var membersList by mutableStateOf(listOf<Pair<String, String>>())
    var memberStatuses by mutableStateOf(listOf<MemberStatus>())
    var pendingList by mutableStateOf(listOf<Pair<String, String>>())
    var showPending by mutableStateOf(false)
    var privatePeer by mutableStateOf<String?>(null)
    var privatePeerNick by mutableStateOf("")
    var sessionFp by mutableStateOf("")
    var safetyVerified by mutableStateOf(false)
    var safetyInput by mutableStateOf("")
    var chatTab by mutableIntStateOf(0)
    var roomsTick by mutableIntStateOf(0)
    var inviteBmp by mutableStateOf<Bitmap?>(null)
    var showInviteQr by mutableStateOf(false)
    var inviteTtl by mutableIntStateOf(AppState.inviteTtlSec.coerceIn(60, 86400))
    var waitingApproval by mutableStateOf(false)
    var membershipOk by mutableStateOf(false)
    var shouldAutoConnect by mutableStateOf(
        AppState.pendingMeshPassword.isNotBlank() || AppState.lastGroupPassword.isNotBlank()
    )
    var showJoinReqDialog by mutableStateOf(false)
    var joinReqHash by mutableStateOf("")
    var joinReqNick by mutableStateOf("")
    var myRole by mutableStateOf(AppState.meshRole)

    val voiceManager = VoiceManager(appCtx)
    val readOnlyMesh: Boolean get() = !AppState.canSendGroup()

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try { block() } catch (_: Exception) {}
        }
    }

    private var memberUpdateJob: Job? = null
    private var startingMesh = false

    /** Coalesce rapid member-list updates from the network thread. */
    private fun scheduleMemberRefresh() {
        memberUpdateJob?.cancel()
        memberUpdateJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(120)
            try {
                val m = mesh ?: return@launch
                memberCount = m.getMembers().size.coerceAtLeast(1)
                val fromMesh = m.getPending().map { it.first to it.second.nick }
                pendingList = (fromMesh + pendingList).distinctBy { it.first }
                if (pendingList.isNotEmpty()) {
                    showPending = true
                    val first = pendingList.first()
                    if (!showJoinReqDialog) {
                        joinReqHash = first.first
                        joinReqNick = first.second
                        showJoinReqDialog = true
                    }
                }
                membersList = m.getMembers().map { it.first to it.second.nick }
                memberStatuses = m.getMemberStatuses()
                onlineCount = memberStatuses.count { it.online }
                for ((h, n) in membersList) {
                    if (n != nickname && h.isNotBlank()) {
                        try { ChatContacts.save(appCtx, h, n) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    val canManageJoins: Boolean get() = myRole.canApprove() || (!waitingApproval && isSetup)

    fun toast(msg: String, long: Boolean = false) {
        onMain {
            try {
                Toast.makeText(appCtx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    fun ensurePermissions(): Boolean = MeshScreenLogic.ensurePermissions(appCtx)

    fun stopMesh() {
        memberUpdateJob?.cancel()
        memberUpdateJob = null
        startingMesh = false
        val m = mesh
        try { m?.stop() } catch (_: Exception) {}
        try { MeshModule.unbind(m) } catch (_: Exception) {}
        mesh = null
        try { msgStore.clear() } catch (_: Exception) {}
        messages = emptyList()
        isSetup = false
        membershipOk = false
        waitingApproval = false
        status = "قطع از شبکه — رمز ذخیره است؛ اتصال مجدد بزنید"
        try { voiceManager.release() } catch (_: Exception) {}
        if (groupPassword.isNotBlank()) {
            AppState.lastGroupPassword = groupPassword
            AppState.lastGroupId = groupId.ifBlank { "default" }
            AppState.lastMeshNickname = nickname
            AppState.save(appCtx)
        }
    }

    fun startMesh() {
        if (startingMesh) return
        startingMesh = true
        try {
        status = "در حال اتصال… " + try {
            com.ghostmago.app.mesh.MeshHealth.transportSummary(appCtx)
        } catch (_: Exception) { "" }
        try { mesh?.stop() } catch (_: Exception) {}
        mesh = null
        isSetup = false
        MeshConnectivity.prepareForMesh(appCtx)

        val att = com.ghostmago.app.util.Attestation.scan(appCtx)
        if (AppState.strictAttestation && !AppState.devMode) {
            if (att.rooted) {
                toast("دستگاه روت — مسدود", long = true)
                return
            }
        }
        if (!ensurePermissions()) return
        if (groupPassword.length < 4) {
            toast("رمز گروه حداقل ۴ کاراکتر")
            return
        }
        myId = "user_${System.currentTimeMillis()}"
        AppState.nickname = nickname.ifBlank { "کاربر" }
        AppState.groupId = groupId.ifBlank { "default" }
        AppState.save(appCtx)

        val net = MeshModule.create(
            context = appCtx,
            myId = myId,
            nickname = AppState.nickname,
            groupId = AppState.groupId,
            groupPassword = groupPassword,
            role = myRole,
            hostMode = !waitingApproval,
            onText = { origin, nick, text, hops, eph ->
                onMain {
                    messages = msgStore.add(
                        ChatMessage(
                            origin = origin, nick = nick, text = text,
                            hops = hops, ephemeralSec = eph
                        )
                    )
                }
            },
            onVoice = { origin, nick, audio, hops ->
                onMain {
                    messages = msgStore.add(
                        ChatMessage(
                            origin = origin, nick = nick, isVoice = true,
                            audioData = audio, hops = hops,
                            text = if (nick.contains("🔒")) "🎤 ویس خصوصی" else "🎤 ویس"
                        )
                    )
                }
            },
            onFile = { origin, nick, filename, data, hops ->
                onMain {
                    messages = msgStore.add(
                        ChatMessage(
                            origin = origin, nick = nick, isFile = true,
                            filename = filename, fileData = data, hops = hops
                        )
                    )
                }
            },
            onImage = { origin, nick, jpeg, cap, hops ->
                onMain {
                    messages = msgStore.add(
                        ChatMessage(
                            origin = origin, nick = nick, imageBytes = jpeg,
                            text = cap.ifBlank { "🖼 تصویر" }, hops = hops
                        )
                    )
                }
            },
            onDelivered = { msgId ->
                onMain {
                    messages = msgStore.mapUpdate {
                        if (it.msgId == msgId) it.copy(deliveryStatus = "delivered") else it
                    }
                }
            },
            onDeliveryFailed = { msgId ->
                onMain {
                    messages = msgStore.mapUpdate {
                        if (it.msgId == msgId) it.copy(deliveryStatus = "failed") else it
                    }
                }
            },
            onJoinReq = { origin, n, _ ->
                onMain {
                    pendingList = (listOf(origin to n) + pendingList).distinctBy { it.first }
                    showPending = true
                    joinReqHash = origin
                    joinReqNick = n
                    showJoinReqDialog = true
                    toast("درخواست عضویت از $n — تأیید یا رد کنید", long = true)
                }
            },
            onPrivate = { origin, nick, text ->
                onMain {
                    messages = msgStore.add(
                        ChatMessage(origin = origin, nick = "🔒 $nick", text = text)
                    )
                }
            },
            onPrivHs = { origin, nick, staticB64, ephB64 ->
                onMain {
                    try {
                        val myH = MeshPacket.hashId(myId.ifBlank { "local" })
                        val sess = try {
                            if (staticB64.isNotBlank() && ephB64.isNotBlank()) {
                                val ik = android.util.Base64.decode(staticB64, android.util.Base64.NO_WRAP)
                                val eph = android.util.Base64.decode(ephB64, android.util.Base64.NO_WRAP)
                                PrivateSessionStore.startX3DHBob(origin, nick, ik, eph)
                            } else {
                                PrivateSessionStore.startSymmetric(myH, origin, nick, groupPassword)
                            }
                        } catch (_: Exception) {
                            PrivateSessionStore.startSymmetric(myH, origin, nick, groupPassword)
                        }
                        sessionFp = sess.fingerprint
                        privatePeer = origin
                        privatePeerNick = nick
                        safetyVerified = if (!AppState.requireSafetyNumber) true
                        else VerifiedPeers.isVerified(appCtx, origin, sessionFp)
                        toast("جلسه خصوصی با $nick", long = true)
                    } catch (e: Exception) {
                        toast(e.message ?: "خطا")
                    }
                }
            },

            onMemberUpdate = {
                scheduleMemberRefresh()
            },
            
            onStatus = { s -> onMain { if (s != status) status = s } },
            onCallSignal = { _, _, _, _ -> },
            onJoinAccepted = { roleName ->
                onMain {
                    membershipOk = true
                    waitingApproval = false
                    status = "عضویت تأیید شد ($roleName)"
                    toast("فرمانده تأیید کرد — عضو شبکه شدید", long = true)
                    ChatContacts.save(appCtx, MeshPacket.hashId(myId), nickname)
                }
            }
        )
        net.start()
        mesh = net
        try {
            if (!waitingApproval) {
                RoomStore.ensureFromActiveMesh(
                    appCtx,
                    AppState.groupId,
                    groupPassword,
                    nickname.ifBlank { "گروه" } + " · " + AppState.groupId
                )
            }
        } catch (_: Exception) {}
        isSetup = true
        AppState.lastGroupPassword = groupPassword
        AppState.lastGroupId = AppState.groupId
        AppState.lastMeshNickname = AppState.nickname
        AppState.save(appCtx)
        AuditLog.append(appCtx, "MESH_JOIN", AppState.groupId)
        net.sendJoinRequest()
        if (waitingApproval) {
            toast("درخواست عضویت ارسال شد — منتظر تأیید میزبان/فرمانده", long = true)
        } else {
            membershipOk = true
            waitingApproval = false
            status = "میزبان شبکه — آماده تأیید اعضا"
        }
        chatTab = 0
        } finally {
            startingMesh = false
        }
    }

    fun openPrivateChat(hash: String, nick: String) {
        try {
            val myH = MeshPacket.hashId(myId.ifBlank { "local" })
            mesh?.sendX3DHBundle(hash)
            if (PrivateSessionStore.get(hash) == null) {
                try {
                    PrivateSessionStore.startSymmetric(myH, hash, nick, groupPassword)
                } catch (ex: Exception) {
                    toast(ex.message ?: "در انتظار باندل X3DH طرف مقابل…", long = true)
                }
            }
            privatePeer = hash
            privatePeerNick = nick
            sessionFp = PrivateSessionStore.get(hash)?.fingerprint ?: ""
            safetyVerified = if (!AppState.requireSafetyNumber) true
            else VerifiedPeers.isVerified(appCtx, hash, sessionFp)
            ChatContacts.save(appCtx, hash, nick)
            chatTab = 1
            val mode = PrivateSessionStore.get(hash)?.let {
                if (it.x3dh) "X3DH+Ratchet" else "موقت"
            } ?: "در انتظار X3DH"
            toast("چت خصوصی با $nick ($mode)")
        } catch (e: Exception) {
            toast(e.message ?: "خطا")
        }
    }

    fun sendPrivateVoiceNote(hash: String, nick: String) {
        try {
            openPrivateChat(hash, nick)
            if (!isRecording) {
                if (voiceManager.startRecording() != null) {
                    isRecording = true
                    toast("در حال ضبط ویس خصوصی… دوباره بزنید برای ارسال", long = true)
                }
            } else {
                val f = voiceManager.stopRecording()
                isRecording = false
                if (f != null) {
                    val data = f.readBytes()
                    mesh?.sendPrivateVoice(hash, data, isCall = false)
                    messages = msgStore.add(
                        ChatMessage(
                            origin = "me", nick = "🔒 من", text = "🎤 ویس خصوصی",
                            isVoice = true, audioData = data, isMine = true
                        )
                    )
                    toast("ویس خصوصی ارسال شد")
                }
            }
        } catch (e: Exception) {
            toast(e.message ?: "خطا")
        }
    }

    fun onComposerMic() {
        try {
            if (isRecording) {
                val file = voiceManager.stopRecording()
                isRecording = false
                if (file == null) return
                val data = try { file.readBytes() } catch (_: Exception) { return }
                if (data.isEmpty()) return
                if (chatTab == 1 && privatePeer != null) {
                    mesh?.sendPrivateVoice(privatePeer!!, data, isCall = false)
                    messages = msgStore.add(
                        ChatMessage(
                            origin = "me", nick = "🔒 من", text = "🎤 ویس خصوصی",
                            isVoice = true, audioData = data, isMine = true
                        )
                    )
                } else {
                    if (readOnlyMesh) {
                        toast("نقش فقط‌خواندنی")
                        return
                    }
                    mesh?.sendVoice(data)
                    messages = msgStore.add(
                        ChatMessage(
                            origin = "me", nick = nickname, text = "🎤 ویس",
                            isVoice = true, audioData = data, isMine = true
                        )
                    )
                }
            } else {
                if (mesh == null) {
                    toast("ابتدا به شبکه وصل شوید")
                    return
                }
                if (voiceManager.startRecording() != null) {
                    isRecording = true
                }
            }
        } catch (e: Exception) {
            isRecording = false
            toast(e.message ?: "خطای ضبط")
        }
    }

    fun onComposerSend() {
        val t = inputText.trim()
        if (t.isEmpty()) return
        if (mesh == null) {
            toast("ابتدا به شبکه وصل شوید")
            return
        }
        if (chatTab == 0 && readOnlyMesh) {
            toast("نقش فقط‌خواندنی — ارسال گروهی مجاز نیست")
            return
        }
        if (chatTab == 1 && AppState.requireSafetyNumber && !safetyVerified) {
            toast("اول Safety Number را تأیید کنید", long = true)
            return
        }
        val peer = privatePeer
        val tab = chatTab
        val nickNow = nickname
        inputText = ""
        viewModelScope.launch {
            try {
                if (tab == 1 && peer != null) {
                    if (AppState.requireSafetyNumber && !safetyVerified) {
                        toast("ابتدا Safety Number", long = true)
                        return@launch
                    }
                    if (PrivateSessionStore.get(peer) == null) {
                        val myH = MeshPacket.hashId(myId.ifBlank { "local" })
                        try {
                            PrivateSessionStore.startSymmetric(myH, peer, privatePeerNick, groupPassword)
                        } catch (_: Exception) {}
                    }
                    val ok = withContext(Dispatchers.IO) {
                        mesh?.sendPrivate(peer, t) == true
                    }
                    if (ok) {
                        messages = msgStore.add(
                            ChatMessage(origin = "me", nick = "🔒 من", text = t, isMine = true)
                        )
                    } else {
                        toast("خطا در ارسال خصوصی")
                    }
                } else {
                    val mid = withContext(Dispatchers.IO) {
                        mesh?.sendText(t, ephemeralSec = AppState.ephemeralDefaultSec) ?: ""
                    }
                    messages = msgStore.add(
                        ChatMessage(
                            origin = "me", nick = nickNow, text = t,
                            isMine = true,
                            deliveryStatus = if (mid.isNotEmpty()) "sent" else "failed",
                            msgId = mid
                        )
                    )
                    if (mid.isEmpty()) {
                        toast("ارسال نشد — شبکه را بررسی کنید")
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: "خطا")
            }
        }
    }

    fun approveJoin(hash: String, nick: String) {
        mesh?.approveMember(hash, displayNick = nick)
        pendingList = pendingList.filter { it.first != hash }
        showJoinReqDialog = false
    }

    fun rejectJoin(hash: String) {
        mesh?.rejectMember(hash)
        pendingList = pendingList.filter { it.first != hash }
        showJoinReqDialog = false
    }

    fun buildInviteQr() {
        val payload = InviteQr.buildPayload(
            groupId.ifBlank { "default" },
            groupPassword,
            nickname.ifBlank { "فرمانده" },
            "soldier",
            inviteTtl
        )
        inviteBmp = InviteQr.toBitmap(payload, 640)
        showInviteQr = true
    }

    fun verifySafetyNumber() {
        val peer = privatePeer ?: return
        if (safetyInput.isBlank()) return
        VerifiedPeers.markVerified(appCtx, peer, sessionFp)
        safetyVerified = true
        toast("Safety Number تأیید شد")
    }

    fun filterExpiredMessages() {
        val filtered = MeshScreenLogic.filterExpired(messages)
        if (filtered.size != messages.size) {
            messages = msgStore.replaceAll(filtered)
        } else if (messages.size > 40) {
            // hard cap if UI list grew outside store
            messages = msgStore.replaceAll(messages.takeLast(30))
        }
    }

    fun refreshMemberStats() {
        val m = mesh ?: return
        memberStatuses = m.getMemberStatuses()
        onlineCount = memberStatuses.count { it.online }
        membersList = m.getMembers().map { it.first to it.second.nick }
        memberCount = membersList.size.coerceAtLeast(1)
        val fromMesh = m.getPending().map { it.first to it.second.nick }
        if (fromMesh.isNotEmpty()) {
            pendingList = (fromMesh + pendingList).distinctBy { it.first }
            showPending = true
        }
    }

    override fun onCleared() {
        try { stopMesh() } catch (_: Exception) {}
        super.onCleared()
    }
}
