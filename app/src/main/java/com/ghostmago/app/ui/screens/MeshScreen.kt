package com.ghostmago.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.ChatContacts
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.data.RoomStore
import com.ghostmago.app.data.VerifiedPeers
import com.ghostmago.app.mesh.InviteQr
import com.ghostmago.app.mesh.MeshPacket
import com.ghostmago.app.ui.screens.mesh.MeshChatArea
import com.ghostmago.app.ui.screens.mesh.MeshPendingJoinsCard
import com.ghostmago.app.ui.screens.mesh.MeshReadOnlyBanner
import com.ghostmago.app.ui.screens.mesh.MeshRoomsBar
import com.ghostmago.app.ui.screens.mesh.MeshSessionHeader
import com.ghostmago.app.ui.screens.mesh.MeshSetupPanel
import com.ghostmago.app.ui.screens.mesh.MeshInviteQrBanner
import com.ghostmago.app.ui.screens.mesh.MeshTransportLine
import com.ghostmago.app.ui.screens.mesh.MeshViewModel
import com.ghostmago.app.ui.screens.mesh.meshFieldColors
import com.ghostmago.app.ui.theme.*
import com.ghostmago.app.util.ImageCompress
import com.ghostmago.app.util.MeshConnectivity
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreen(onBack: () -> Unit, nav: androidx.navigation.NavController? = null) {
    val context = LocalContext.current
    val vm: MeshViewModel = viewModel()
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale("fa")) }

    var rolePwDialog by remember { mutableStateOf<MeshRole?>(null) }
    var rolePwInput by remember { mutableStateOf("") }
    var inviteHoursCustom by remember { mutableStateOf("") }

    val inviteScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
            val bmp = android.graphics.BitmapFactory.decodeStream(input)
            input.close()
            if (bmp == null) {
                Toast.makeText(context, "تصویر خوانده نشد", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val raw = com.ghostmago.app.util.QrHelper.decode(bmp)
            val decoded = raw?.let { InviteQr.parse(it) }
            if (decoded != null) {
                vm.groupPassword = decoded.groupPassword
                vm.groupId = decoded.groupId.ifBlank { vm.groupId }
                vm.waitingApproval = true
                Toast.makeText(context, "دعوتنامه بارگذاری شد", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "QR معتبر نیست", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
        }
    }

    val meshPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result ignored — user can retry connect */ }

    val imagePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || vm.mesh == null) return@rememberLauncherForActivityResult
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
            val bytes = input.readBytes(); input.close()
            val jpeg = ImageCompress.toJpeg(bytes, maxBytes = 28000, maxDim = 1280)
            vm.mesh?.sendImage(jpeg, "")
            vm.messages = vm.msgStore.add(
                com.ghostmago.app.ui.screens.mesh.ChatMessage(
                    origin = "me", nick = vm.nickname, text = "🖼 تصویر",
                    imageBytes = jpeg, isMine = true
                )
            )
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "خطای تصویر", Toast.LENGTH_SHORT).show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || vm.mesh == null) return@rememberLauncherForActivityResult
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
            val input = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
            val data = input.readBytes(); input.close()
            if (data.size > 24_000) vm.mesh?.sendFileChunked(name, data)
            else vm.mesh?.sendFile(name, data)
            vm.messages = vm.msgStore.add(
                com.ghostmago.app.ui.screens.mesh.ChatMessage(
                    origin = "me", nick = vm.nickname, text = "📎 $name",
                    isFile = true, filename = name, fileData = data, isMine = true
                )
            )
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "خطای فایل", Toast.LENGTH_SHORT).show()
        }
    }

    // Join request dialog
    if (vm.showJoinReqDialog && vm.joinReqHash.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("درخواست عضویت جدید", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Text(
                    "کاربر «${vm.joinReqNick}» میخواهد وارد گروه شود.\nتأیید = عضو میشود  |  رد = ورود ممنوع",
                    fontSize = 15.sp,
                    lineHeight = 24.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            vm.approveJoin(vm.joinReqHash, vm.joinReqNick)
                            ChatContacts.save(context, vm.joinReqHash, vm.joinReqNick)
                            Toast.makeText(context, "${vm.joinReqNick} تأیید و عضو شد", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Secondary)
                ) { Text("✓ تأیید عضویت", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Button(
                    onClick = {
                        try {
                            vm.rejectJoin(vm.joinReqHash)
                            Toast.makeText(context, "عضویت رد شد", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("✗ رد عضویت", fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (rolePwDialog != null) {
        AlertDialog(
            onDismissRequest = { rolePwDialog = null; rolePwInput = "" },
            title = { Text("تأیید نقش ${rolePwDialog?.fa}") },
            text = {
                OutlinedTextField(
                    value = rolePwInput,
                    onValueChange = { rolePwInput = it },
                    label = { Text("رمز نقش") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = rolePwDialog
                    if (r != null && r.checkRolePassword(rolePwInput)) {
                        AppState.meshRole = r
                        AppState.save(context)
                        vm.myRole = r
                        rolePwDialog = null
                        rolePwInput = ""
                        Toast.makeText(context, "نقش ${r.fa} فعال شد", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "رمز نقش نادرست", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("تأیید") }
            },
            dismissButton = {
                TextButton(onClick = { rolePwDialog = null; rolePwInput = "" }) { Text("لغو") }
            }
        )
    }

    LaunchedEffect(Unit) {
        if (AppState.pendingMeshAutoJoin) {
            val pw = AppState.pendingMeshPassword
            if (pw.isNotBlank()) vm.groupPassword = pw
            vm.groupId = AppState.groupId.ifBlank { vm.groupId }
            AppState.pendingMeshAutoJoin = false
            AppState.pendingMeshPassword = ""
            AppState.save(context)
            vm.waitingApproval = true
            vm.shouldAutoConnect = true
        }
    }

    LaunchedEffect(vm.shouldAutoConnect, vm.groupPassword, vm.groupId) {
        if (vm.shouldAutoConnect && !vm.isSetup && vm.groupPassword.length >= 4) {
            vm.shouldAutoConnect = false
            vm.status = "آمادهسازی وایفای و مجوزها…"
            val missing = MeshConnectivity.missingPermissions(context)
            if (missing.isNotEmpty()) {
                meshPermLauncher.launch(missing)
                delay(600)
            }
            var tries = 0
            while (!MeshConnectivity.isWifiEnabled(context) && tries < 12) {
                vm.status = "وایفای را روشن کنید… ($tries)"
                delay(1000)
                tries++
            }
            if (!MeshConnectivity.isWifiEnabled(context)) {
                vm.status = "وایفای خاموش است — روشن کنید و دوباره وصل شوید"
                Toast.makeText(context, "وایفای لازم است", Toast.LENGTH_LONG).show()
            }
            delay(500)
            vm.status = "اتصال خودکار به میزبان…"
            vm.startMesh()
        }
    }

    LaunchedEffect(vm.isSetup, vm.mesh) {
        while (vm.isSetup && vm.mesh != null) {
            vm.refreshMemberStats()
            delay(if (vm.pendingList.isNotEmpty() || vm.showJoinReqDialog) 3500L else 12000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(45_000)
            vm.filterExpiredMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("شبکه ارتباطی", color = TextPrimary, fontWeight = FontWeight.Bold)
                        if (vm.isSetup) {
                            Text(vm.status, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.stopMesh()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                actions = {
                    if (vm.isSetup && vm.canManageJoins) {
                        IconButton(onClick = { vm.buildInviteQr() }) {
                            Icon(Icons.Default.QrCode, null, tint = Primary)
                        }
                    }
                    if (vm.isSetup) {
                        IconButton(onClick = { vm.stopMesh() }) {
                            Icon(Icons.Default.Stop, null, tint = Danger)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgDark
    ) { padding ->
        if (!vm.isSetup) {
            MeshSetupPanel(
                padding = padding,
                context = context,
                myRole = vm.myRole,
                nickname = vm.nickname,
                onNickname = { vm.nickname = it },
                groupId = vm.groupId,
                onGroupId = { vm.groupId = it },
                groupPassword = vm.groupPassword,
                onGroupPassword = { vm.groupPassword = it },
                inviteTtl = vm.inviteTtl,
                onInviteTtl = { vm.inviteTtl = it },
                inviteHoursCustom = inviteHoursCustom,
                onInviteHoursCustom = { inviteHoursCustom = it },
                waitingApproval = vm.waitingApproval,
                onRequestRolePassword = { r ->
                    rolePwDialog = r
                    rolePwInput = ""
                },
                onShowInviteQr = { bmp ->
                    vm.inviteBmp = bmp
                    vm.showInviteQr = true
                },
                onPickInviteFromGallery = { inviteScanLauncher.launch("image/*") },
                onOpenQrScan = {
                    try {
                        nav?.navigate(com.ghostmago.app.ui.Screen.QrScan.route)
                    } catch (_: Exception) {
                        Toast.makeText(context, "اسکن بارکد از منوی اصلی", Toast.LENGTH_SHORT).show()
                    }
                },
                onConnect = {
                    if (vm.groupPassword.length < 4) {
                        Toast.makeText(context, "رمز گروه حداقل ۴ کاراکتر", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.waitingApproval = !vm.myRole.canApprove()
                        vm.startMesh()
                    }
                }
            )

        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                MeshInviteQrBanner(
                    bitmap = vm.inviteBmp,
                    visible = vm.showInviteQr,
                    onDismiss = { vm.showInviteQr = false }
                )
                MeshSessionHeader(
                    status = vm.status,
                    waitingApproval = vm.waitingApproval,
                    membershipOk = vm.membershipOk,
                    memberCount = vm.memberCount,
                    onlineCount = vm.onlineCount
                )


                MeshReadOnlyBanner(visible = vm.readOnlyMesh)
                MeshRoomsBar(
                    context = context,
                    groupId = vm.groupId,
                    roomsTick = vm.roomsTick,
                    onRoomSelected = { id, pw ->
                        vm.groupId = id
                        vm.groupPassword = pw
                    }
                )
                MeshTransportLine(mesh = vm.mesh)

                TabRow(selectedTabIndex = vm.chatTab, containerColor = SurfaceDark, contentColor = Primary) {
                    Tab(selected = vm.chatTab == 0, onClick = { vm.chatTab = 0 }, text = { Text("گروه") })
                    Tab(selected = vm.chatTab == 1, onClick = { vm.chatTab = 1 }, text = { Text("خصوصی") })
                }

                if (vm.chatTab == 1) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .heightIn(max = 200.dp)
                    ) {
                        Column(
                            Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (vm.privatePeer != null) {
                                Text("🔒 با $vm.privatePeerNick", color = Secondary, fontSize = 12.sp)
                                if (vm.sessionFp.isNotEmpty()) {
                                    Text("شماره اعتماد جلسه", color = TextSecondary, fontSize = 11.sp)
                                    Text(vm.sessionFp, color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (vm.safetyVerified) "وضعیت: تأیید شد ✓" else "وضعیت: تأیید نشده",
                                        color = if (vm.safetyVerified) Secondary else Danger,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (AppState.requireSafetyNumber && !vm.safetyVerified) {
                                    OutlinedTextField(
                                        value = vm.safetyInput,
                                        onValueChange = { vm.safetyInput = it },
                                        label = { Text("کد طرف مقابل") },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = meshFieldColors()
                                    )
                                    Button(onClick = {
                                        val peer = vm.privatePeer
                                        if (peer == null || vm.sessionFp.isBlank()) {
                                            Toast.makeText(context, "نشست خصوصی آماده نیست", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        if (VerifiedPeers.matchesStrict(vm.sessionFp, vm.safetyInput)) {
                                            VerifiedPeers.markVerified(context, peer, vm.sessionFp)
                                            vm.safetyVerified = true
                                            Toast.makeText(context, "Safety Number تأیید و ذخیره شد", Toast.LENGTH_SHORT).show()
                                        } else {
                                            vm.safetyVerified = false
                                            Toast.makeText(
                                                context,
                                                "مطابقت ندارد — هر دو طرف باید عدد کامل را رو در رو مقایسه کنند",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }) { Text("تأیید اجباری Safety Number") }
                                }
                                TextButton(onClick = {
                                    vm.privatePeer?.let { PrivateSessionStore.remove(it) }
                                    vm.privatePeer = null
                                    vm.privatePeerNick = ""
                                    vm.sessionFp = ""
                                    vm.safetyVerified = false
                                }) { Text("پایان جلسه", color = Danger) }
                            } else {
                                                                Text("مخاطبین — پیام و ویس", color = TextSecondary, fontSize = 12.sp)
                                val saved = ChatContacts.list(context)
                                val contactRows = buildList {
                                    saved.forEach { c -> add(c.hash to c.nick) }
                                    vm.membersList.filter { it.second != vm.nickname }.forEach { add(it) }
                                }.distinctBy { it.first }
                                if (contactRows.isEmpty()) {
                                    Text("هنوز مخاطبی نیست. بعد از تأیید عضویت اینجا میآیند.", color = TextMuted, fontSize = 12.sp)
                                }
                                contactRows.forEach { (hash, nick) ->
                                    val online = vm.memberStatuses.any { it.nick == nick && it.online } ||
                                        vm.membersList.any { it.first == hash }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = CardDark),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(
                                                "$nick ${if (online) "● آنلاین" else "○"}",
                                                color = if (online) Secondary else TextPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Button(
                                                    onClick = { vm.openPrivateChat(hash, nick) },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                                ) { Text("💬 پیام", fontSize = 12.sp) }
                                                Button(
                                                    onClick = { vm.sendPrivateVoiceNote(hash, nick) },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                                ) { Text(if (vm.isRecording && vm.privatePeer == hash) "⏹ ارسال" else "🎤 ویس", fontSize = 12.sp) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                MeshPendingJoinsCard(
                    context = context,
                    mesh = vm.mesh,
                    pendingList = vm.pendingList,
                    showPending = vm.showPending,
                    canManageJoins = vm.canManageJoins,
                    isSetup = vm.isSetup,
                    onPendingChange = { vm.pendingList = it }
                )

                MeshChatArea(
                    messages = vm.messages,
                    chatTab = vm.chatTab,
                    listState = listState,
                    timeFmt = timeFmt,
                    voiceManager = vm.voiceManager,
                    inputText = vm.inputText,
                    onInputChange = { vm.inputText = it },
                    isRecording = vm.isRecording,
                    onAttachFile = { filePicker.launch("*/*") },
                    onPickImage = { imagePickLauncher.launch("image/*") },
                    onMicClick = { vm.onComposerMic() },
                    onSendClick = { vm.onComposerSend() },
                    listModifier = Modifier.weight(1f),
                )
            }
        }
    }
}


