package com.ghostmago.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.data.AppState
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.ui.theme.*
import com.ghostmago.app.util.SecurityUtils
import com.ghostmago.app.ui.screens.settings.MeshPrivacySettings
import com.ghostmago.app.ui.screens.settings.NetworkSecuritySettings
import com.ghostmago.app.ui.screens.settings.ProfileSecuritySettings
import com.ghostmago.app.ui.screens.settings.SettingsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var nick by remember { mutableStateOf(AppState.nickname) }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var duressPw by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(AppState.darkTheme) }
    var accent by remember { mutableIntStateOf(AppState.accentColor) }
    var role by remember { mutableStateOf(AppState.meshRole) }
    var roleGatePw by remember { mutableStateOf("") }
    var radioSilence by remember { mutableStateOf(AppState.radioSilence) }
    var ephSec by remember { mutableIntStateOf(AppState.ephemeralDefaultSec) }
    var vaultPin by remember { mutableStateOf(AppState.vaultPin) }
    var safeWord by remember { mutableStateOf(AppState.safeWord) }
    var readOnly by remember { mutableStateOf(AppState.forceReadOnly) }
    var coverOn by remember { mutableStateOf(AppState.coverTraffic) }
    var coverSec by remember { mutableIntStateOf(AppState.coverIntervalSec) }
    var dmsOn by remember { mutableStateOf(com.ghostmago.app.data.DeadManSwitch.isEnabled(context)) }
    var dmsMin by remember { mutableIntStateOf(com.ghostmago.app.data.DeadManSwitch.intervalMin(context)) }
    var dmsMsg by remember { mutableStateOf(com.ghostmago.app.data.DeadManSwitch.message(context)) }
    val deviceFp = remember { com.ghostmago.app.util.LicenseManager.deviceId(context) }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        AppState.setBackground(context, uri)
        Toast.makeText(context, if (uri != null) "پسزمینه تنظیم شد" else "حذف شد", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProfileSecuritySettings(
                nick = nick, onNick = { nick = it },
                role = role, onRole = { role = it },
                roleGatePw = roleGatePw, onRoleGatePw = { roleGatePw = it },
                oldPw = oldPw, onOldPw = { oldPw = it },
                newPw = newPw, onNewPw = { newPw = it },
                confirmPw = confirmPw, onConfirmPw = { confirmPw = it },
                duressPw = duressPw, onDuressPw = { duressPw = it },
                vaultPin = vaultPin, onVaultPin = { vaultPin = it },
                safeWord = safeWord, onSafeWord = { safeWord = it },
                readOnly = readOnly, onReadOnly = { readOnly = it },
            )

            NetworkSecuritySettings(
                dmsOn = dmsOn, onDmsOn = { dmsOn = it },
                dmsMin = dmsMin, onDmsMin = { dmsMin = it },
                dmsMsg = dmsMsg, onDmsMsg = { dmsMsg = it },
                coverOn = coverOn, onCoverOn = { coverOn = it },
                coverSec = coverSec, onCoverSec = { coverSec = it },
            )

            MeshPrivacySettings(
                radioSilence = radioSilence,
                onRadioSilence = { radioSilence = it },
                ephSec = ephSec,
                onEphSec = { ephSec = it },
            )

            SettingsSection("ظاهر")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("تم تاریک", color = TextPrimary)
                Switch(checked = dark, onCheckedChange = {
                    dark = it; AppState.darkTheme = it; AppState.save(context)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("آبی", "سبز", "بنفش", "نارنجی").forEachIndexed { i, name ->
                    FilterChip(selected = accent == i, onClick = {
                        accent = i; AppState.accentColor = i; AppState.save(context)
                    }, label = { Text(name) })
                }
            }
            Button(onClick = { bgPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)) { Text("تغییر عکس پسزمینه") }
            if (AppState.bgImagePath != null) {
                TextButton(onClick = {
                    AppState.setBackground(context, null)
                }) { Text("حذف پسزمینه", color = Danger) }
            }

            SettingsSection("لاگ امنیتی")
            val log = remember { SecurityUtils.getAuditLog(context) }
            Text(if (log.isBlank()) "خالی" else log.takeLast(500), color = TextSecondary, fontSize = 11.sp)
        }
    }
}
