package com.ghostmago.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.data.AppState
import com.ghostmago.app.ui.theme.*

/**
 * Network port, session lock, attestation, op profile, keystore, sealed sender,
 * dead-man switch, cover traffic. Extracted from SettingsScreen.
 */
@Composable
fun NetworkSecuritySettings(
    dmsOn: Boolean,
    onDmsOn: (Boolean) -> Unit,
    dmsMin: Int,
    onDmsMin: (Int) -> Unit,
    dmsMsg: String,
    onDmsMsg: (String) -> Unit,
    coverOn: Boolean,
    onCoverOn: (Boolean) -> Unit,
    coverSec: Int,
    onCoverSec: (Int) -> Unit,
) {
    val context = LocalContext.current
    var devMode by remember { mutableStateOf(AppState.devMode) }
    var strictAtt by remember { mutableStateOf(AppState.strictAttestation) }
    var profile by remember { mutableStateOf(AppState.opProfileId) }
    var hwKs by remember { mutableStateOf(AppState.useHardwareKeystore) }
    var sealedOn by remember { mutableStateOf(AppState.sealedSender) }
    var reqSafety by remember { mutableStateOf(AppState.requireSafetyNumber) }
    SettingsSection("شبکه")
    OutlinedTextField(
        value = AppState.meshPort.toString(),
        onValueChange = {
            val v = it.filter { c -> c.isDigit() }.toIntOrNull()
            if (v != null) {
                AppState.meshPort = v.coerceIn(1024, 65535)
                AppState.save(context)
            }
        },
        label = { Text("پورت مش UDP (پیشفرض ۵۵۹۹)") },
        modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )
    Text("همه اعضا باید پورت یکسان داشته باشند", color = TextSecondary, fontSize = 11.sp)

    SettingsSection("Session security")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Auto-lock", color = TextPrimary)
            Text("Lock vault after idle", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = AppState.autoLockEnabled, onCheckedChange = {
            AppState.autoLockEnabled = it; AppState.save(context)
        })
    }
    OutlinedTextField(
        value = AppState.sessionTimeoutMin.toString(),
        onValueChange = {
            AppState.sessionTimeoutMin = it.filter { c -> c.isDigit() }.toIntOrNull()?.coerceIn(1, 120) ?: 15
            AppState.save(context)
        },
        label = { Text("Idle timeout (minutes)") },
        modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )

    SettingsSection("توسعه و attestation")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("حالت توسعهدهنده", color = TextPrimary)
            Text("عدم مسدودسازی root در مش", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = devMode, onCheckedChange = {
            devMode = it; AppState.devMode = it; AppState.save(context)
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Attestation سخت", color = TextPrimary)
        Switch(checked = strictAtt, onCheckedChange = {
            strictAtt = it; AppState.strictAttestation = it; AppState.save(context)
        })
    }

    SettingsSection("پروفایل عملیاتی")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        com.ghostmago.app.data.OpProfile.entries.forEach { pr ->
            FilterChip(
                selected = profile == pr.id,
                onClick = {
                    profile = pr.id
                    AppState.opProfileId = pr.id
                    pr.applyTo(AppState)
                    AppState.save(context)
                    Toast.makeText(context, pr.fa, Toast.LENGTH_SHORT).show()
                },
                label = { Text(pr.fa, fontSize = 11.sp) }
            )
        }
    }
    Text(com.ghostmago.app.data.OpProfile.from(profile).desc, color = TextSecondary, fontSize = 11.sp)

    SettingsSection("Keystore / StrongBox")
    Text(com.ghostmago.app.crypto.HardwareKeystore.status(context), color = TextSecondary, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("استفاده از کلید سختافزاری", color = TextPrimary)
        Switch(checked = hwKs, onCheckedChange = {
            hwKs = it; AppState.useHardwareKeystore = it; AppState.save(context)
            if (it) com.ghostmago.app.crypto.HardwareKeystore.ensureKey(true)
        })
    }

    SettingsSection("Sealed Sender")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("فرستنده ناشناس در شبکه", color = TextPrimary)
            Text("ناظر ترافیک هویت را نمیبیند", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = sealedOn, onCheckedChange = {
            sealedOn = it; AppState.sealedSender = it; AppState.save(context)
        })
    }

    SettingsSection("کلید مرگ (Dead-man)")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("فعال", color = TextPrimary)
        Switch(checked = dmsOn, onCheckedChange = {
            onDmsOn(it)
            if (it) com.ghostmago.app.data.DeadManSwitch.enable(context, dmsMin, dmsMsg)
            else com.ghostmago.app.data.DeadManSwitch.disable(context)
        })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15, 30, 60, 180, 360).forEach { m ->
            FilterChip(selected = dmsMin == m, onClick = {
                onDmsMin(m)
                if (dmsOn) com.ghostmago.app.data.DeadManSwitch.enable(context, m, dmsMsg)
            }, label = { Text("${m}د") })
        }
    }
    OutlinedTextField(value = dmsMsg, onValueChange = { onDmsMsg(it) },
        label = { Text("پیام در صورت عدم چکاین") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Button(onClick = {
        com.ghostmago.app.data.DeadManSwitch.checkIn(context)
        Toast.makeText(context, "چکاین ثبت شد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Warning),
        shape = RoundedCornerShape(12.dp)) { Text("چکاین الان") }

    SettingsSection("ترافیک پوششی (Cover Traffic)")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("فعالسازی مداوم", color = TextPrimary)
            Text("بستههای جعلی دورهای روی مش", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = coverOn, onCheckedChange = {
            onCoverOn(it); AppState.coverTraffic = it; AppState.save(context)
        })
    }
    Text("فاصله ارسال پوششی", color = TextSecondary, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 10, 15, 30, 60).forEach { sec ->
            FilterChip(selected = coverSec == sec, onClick = {
                onCoverSec(sec); AppState.coverIntervalSec = sec; AppState.save(context)
            }, label = { Text("${sec}s") })
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("تأیید اجباری Safety Number", color = TextPrimary)
            Text("قبل از چت خصوصی", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = reqSafety, onCheckedChange = {
            reqSafety = it; AppState.requireSafetyNumber = it; AppState.save(context)
        })
    }

}
