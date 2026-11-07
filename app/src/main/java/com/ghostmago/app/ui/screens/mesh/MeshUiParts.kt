package com.ghostmago.app.ui.screens.mesh

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.ChatContacts
import com.ghostmago.app.data.ImmutableAudit
import com.ghostmago.app.data.RoomStore
import com.ghostmago.app.data.TwoPersonRule
import com.ghostmago.app.mesh.MeshNetwork
import com.ghostmago.app.mesh.TransportProfile
import com.ghostmago.app.ui.theme.Border
import com.ghostmago.app.ui.theme.CardDark
import com.ghostmago.app.ui.theme.Danger
import com.ghostmago.app.ui.theme.Primary
import com.ghostmago.app.ui.theme.Secondary
import com.ghostmago.app.ui.theme.TextPrimary
import com.ghostmago.app.ui.theme.TextSecondary
import com.ghostmago.app.ui.theme.Warning

@Composable
fun meshFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = Border,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Primary,
    focusedContainerColor = CardDark,
    unfocusedContainerColor = CardDark,
    focusedLabelColor = Primary,
    unfocusedLabelColor = TextSecondary
)

@Composable
fun MeshReadOnlyBanner(visible: Boolean) {
    if (!visible) return
    Card(
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            "حالت فقط‌خواندنی (نقش نیرو/تازه‌کار) — ارسال گروهی غیرفعال است. چت خصوصی در صورت تأیید Safety مجاز است.",
            color = Warning,
            fontSize = 12.sp,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
fun MeshRoomsBar(
    context: Context,
    groupId: String,
    roomsTick: Int,
    onRoomSelected: (id: String, password: String) -> Unit
) {
    val rooms = RoomStore.list()
    if (rooms.isEmpty() || !AppState.meshRole.canApprove()) return
    Text("اتاق‌ها", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rooms.filter { it.enabled }.forEach { room ->
            FilterChip(
                selected = groupId == room.id || AppState.groupId == room.id,
                onClick = {
                    RoomStore.activate(context, room)
                    onRoomSelected(room.id, room.password)
                    Toast.makeText(context, "اتاق: ${room.name}", Toast.LENGTH_SHORT).show()
                },
                label = { Text(room.name, fontSize = 11.sp) }
            )
        }
    }
}

@Composable
fun MeshTransportLine(mesh: MeshNetwork?) {
    Text(
        "مسیر انتقال: ${TransportProfile.labelFa()}" +
            (mesh?.let { " · صف آفلاین: ${it.offlineQueueSize()}" } ?: ""),
        color = TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
    )
}

@Composable
fun MeshPendingJoinsCard(
    context: Context,
    mesh: MeshNetwork?,
    pendingList: List<Pair<String, String>>,
    showPending: Boolean,
    canManageJoins: Boolean,
    isSetup: Boolean,
    onPendingChange: (List<Pair<String, String>>) -> Unit
) {
    if (!(canManageJoins && isSetup && (pendingList.isNotEmpty() || showPending))) return
    Card(
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("درخواستهای عضویت (فرمانده)", color = Warning, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (canManageJoins) {
                Button(
                    onClick = {
                        if (TwoPersonRule.requiresTwoPerson("REMOTE_WIPE") &&
                            !TwoPersonRule.hasSecondOperator(context)
                        ) {
                            Toast.makeText(
                                context,
                                "قاعده دو نفره فعال است — نفر دوم را در انطباق ثبت کنید",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            mesh?.sendRemoteWipe("*", "commander")
                            ImmutableAudit.append(context, "REMOTE_WIPE", "broadcast")
                            Toast.makeText(context, "دستور پاکسازی از راه دور ارسال شد", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) { Text("پاکسازی از راه دور همه (خطرناک)", fontSize = 12.sp) }
            }
            if (pendingList.isEmpty()) {
                Text("صف خالی است", color = TextSecondary, fontSize = 12.sp)
            } else {
                Text("${pendingList.size} درخواست در صف", color = Warning, fontSize = 12.sp)
            }
            pendingList.forEach { (hash, nick) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(nick, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(hash.take(12), color = TextSecondary, fontSize = 11.sp)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    mesh?.approveMember(hash, "soldier", nick)
                                    ChatContacts.save(context, hash, nick)
                                    onPendingChange(pendingList.filter { it.first != hash })
                                    Toast.makeText(context, "$nick تأیید و عضو شد", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Secondary)
                        ) { Text("✓ تأیید عضویت", fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                mesh?.rejectMember(hash)
                                onPendingChange(pendingList.filter { it.first != hash })
                                Toast.makeText(context, "$nick رد شد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Danger)
                        ) { Text("✗ رد", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
