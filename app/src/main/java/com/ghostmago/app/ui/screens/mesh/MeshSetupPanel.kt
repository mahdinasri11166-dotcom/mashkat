package com.ghostmago.app.ui.screens.mesh

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.mesh.InviteQr
import com.ghostmago.app.ui.theme.Accent
import com.ghostmago.app.ui.theme.Primary
import com.ghostmago.app.ui.theme.Secondary
import com.ghostmago.app.ui.theme.TextMuted
import com.ghostmago.app.ui.theme.TextPrimary
import com.ghostmago.app.ui.theme.TextSecondary

@Composable
fun MeshSetupPanel(
    padding: androidx.compose.foundation.layout.PaddingValues,
    context: Context,
    myRole: MeshRole,
    nickname: String,
    onNickname: (String) -> Unit,
    groupId: String,
    onGroupId: (String) -> Unit,
    groupPassword: String,
    onGroupPassword: (String) -> Unit,
    inviteTtl: Int,
    onInviteTtl: (Int) -> Unit,
    inviteHoursCustom: String,
    onInviteHoursCustom: (String) -> Unit,
    waitingApproval: Boolean,
    onRequestRolePassword: (MeshRole) -> Unit,
    onShowInviteQr: (Bitmap) -> Unit,
    onPickInviteFromGallery: () -> Unit,
    onOpenQrScan: () -> Unit,
    onConnect: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "راهاندازی شبکه ارتباطی — همه نقشها",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        if (groupPassword.length >= 4) {
            Text(
                "رمز گروه ذخیرهشده — برای ورود مجدد فقط «اتصال» را بزنید.",
                color = Secondary,
                fontSize = 13.sp
            )
        }
        Text(
            "هر دو گوشی روی یک وایفای باشند. یک نفر نقش فرمانده داشته باشد.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Text(
            "پس از اسکن بارکد، اتصال خودکار است؛ نیرو منتظر تأیید فرمانده میماند.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Text("نقش: ${myRole.symbol} ${myRole.fa}", color = Accent, fontSize = 14.sp)

        OutlinedTextField(
            value = nickname,
            onValueChange = onNickname,
            label = { Text("نام مستعار") },
            modifier = Modifier.fillMaxWidth(),
            colors = meshFieldColors(),
            shape = RoundedCornerShape(12.dp)
        )

        Text("نقش شما (همه نقشها پشتیبانی میشوند)", color = TextSecondary, fontSize = 12.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MeshRole.entries.forEach { r ->
                FilterChip(
                    selected = AppState.meshRole == r,
                    onClick = {
                        if (r.requiresRolePassword()) {
                            onRequestRolePassword(r)
                        } else {
                            AppState.meshRole = r
                            AppState.save(context)
                        }
                    },
                    label = { Text("${r.symbol} ${r.fa}", fontSize = 10.sp) }
                )
            }
        }

        Text(
            "میزبان: اتصال دستی بدون اسکن. مهمان: اسکن بارکد → انتظار تأیید.",
            color = TextMuted,
            fontSize = 11.sp
        )

        OutlinedTextField(
            value = groupId,
            onValueChange = onGroupId,
            label = { Text("شناسه گروه") },
            modifier = Modifier.fillMaxWidth(),
            colors = meshFieldColors(),
            shape = RoundedCornerShape(12.dp)
        )
        OutlinedTextField(
            value = groupPassword,
            onValueChange = onGroupPassword,
            label = { Text("رمز گروه (حداقل ۴)") },
            modifier = Modifier.fillMaxWidth(),
            colors = meshFieldColors(),
            shape = RoundedCornerShape(12.dp),
            visualTransformation = PasswordVisualTransformation()
        )

        if (myRole.canInvite()) {
            Text("مدت اعتبار بارکد", color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(300 to "5m", 900 to "15m", 3600 to "1h", 21600 to "6h", 86400 to "24h")
                    .forEach { (sec, label) ->
                        FilterChip(
                            selected = inviteTtl == sec,
                            onClick = { onInviteTtl(sec) },
                            label = { Text(label) }
                        )
                    }
            }
            OutlinedTextField(
                value = inviteHoursCustom,
                onValueChange = { v ->
                    val filtered = v.filter { c -> c.isDigit() || c == '.' }
                    onInviteHoursCustom(filtered)
                    val h = filtered.toDoubleOrNull()
                    if (h != null && h > 0) {
                        onInviteTtl((h * 3600).toInt().coerceIn(60, 86400 * 30))
                    }
                },
                label = { Text("ساعت سفارشی") },
                modifier = Modifier.fillMaxWidth(),
                colors = meshFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )
            Button(
                onClick = {
                    if (groupPassword.length < 4) {
                        Toast.makeText(context, "رمز حداقل ۴ کاراکتر", Toast.LENGTH_SHORT).show()
                    } else {
                        val payload = InviteQr.buildPayload(
                            groupId.ifBlank { "default" },
                            groupPassword,
                            nickname.ifBlank { "فرمانده" },
                            "soldier",
                            inviteTtl
                        )
                        onShowInviteQr(InviteQr.toBitmap(payload, 640))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(12.dp)
            ) { Text("ساخت بارکد دعوت فرمانده") }
        }

        Button(
            onClick = onPickInviteFromGallery,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Secondary),
            shape = RoundedCornerShape(12.dp)
        ) { Text("بارکد فرمانده از گالری") }

        Button(
            onClick = onOpenQrScan,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            shape = RoundedCornerShape(12.dp)
        ) { Text("اسکن بارکد با دوربین") }

        Text(
            "پس از شناسایی بارکد فرمانده، خودکار وارد شبکه می‌شوید.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Wifi, null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    waitingApproval -> "اتصال به شبکه ارتباطی (انتظار تأیید)"
                    groupPassword.length >= 4 -> "اتصال مجدد به گروه"
                    else -> "اتصال / ایجاد شبکه ارتباطی"
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}
