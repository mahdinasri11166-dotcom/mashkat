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
 * Mesh privacy, relay, Bluetooth, icon hide, ephemeral defaults.
 * Extracted from SettingsScreen to keep UI files under ~300 lines.
 */
@Composable
fun MeshPrivacySettings(
    radioSilence: Boolean,
    onRadioSilence: (Boolean) -> Unit,
    ephSec: Int,
    onEphSec: (Int) -> Unit,
) {
    val context = LocalContext.current
    SettingsSection("مش و حریم")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("سکوت رادیویی", color = TextPrimary)
            Text("بدون Beacon خروجی", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = radioSilence, onCheckedChange = {
            onRadioSilence(it)
            AppState.radioSilence = it
            AppState.save(context)
        })
    }
    var relayOn by remember { mutableStateOf(AppState.relayEnabled) }
    var relayHost by remember { mutableStateOf(AppState.relayHost) }
    var btOn by remember { mutableStateOf(AppState.useBluetoothMesh) }
    var hideCode by remember { mutableStateOf(AppState.hideIconCode) }
    var showCode by remember { mutableStateOf(AppState.showIconCode) }
    Text("رله اینترنت (شهرهای مختلف)", color = TextPrimary, fontWeight = FontWeight.SemiBold)
    Text("آدرس مثل my.server.com:7777 — سرور TCP که هر خط base64 را به بقیه میفرستد", color = TextSecondary, fontSize = 11.sp)
    OutlinedTextField(value = relayHost, onValueChange = { relayHost = it }, label = { Text("میزبان:پورت رله") },
        modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("فعالسازی رله اینترنت", color = TextPrimary)
        Switch(checked = relayOn, onCheckedChange = {
            relayOn = it; AppState.relayEnabled = it; AppState.relayHost = relayHost; AppState.save(context)
        })
    }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("مش بلوتوث", color = TextPrimary)
            Text("اگر وایفای مشغول بود از بلوتوث جفتشده استفاده شود", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = btOn, onCheckedChange = {
            btOn = it; AppState.useBluetoothMesh = it; AppState.save(context)
        })
    }
    var usbOn by remember { mutableStateOf(AppState.useUsbRadioMesh) }
    var usbBand by remember { mutableStateOf(AppState.usbRadioBand) }
    var usbText by remember { mutableStateOf(AppState.usbRadioTextOnly) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("مش رادیو USB (FM/AM)", color = TextPrimary)
            Text(
                "ماژول لحیم‌شده/دانگل روی تایپ‌سی — وقتی وای‌فای و بلوتوث در دسترس نیست",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
        Switch(
            checked = usbOn,
            onCheckedChange = {
                usbOn = it
                AppState.useUsbRadioMesh = it
                AppState.save(context)
            }
        )
    }
    if (usbOn) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = usbBand == "fm",
                onClick = {
                    usbBand = "fm"
                    AppState.usbRadioBand = "fm"
                    AppState.save(context)
                },
                label = { Text("FM") }
            )
            FilterChip(
                selected = usbBand == "am",
                onClick = {
                    usbBand = "am"
                    AppState.usbRadioBand = "am"
                    AppState.save(context)
                },
                label = { Text("AM") }
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("فقط متن روی رادیو (بدون فایل حجیم)", color = TextSecondary, fontSize = 12.sp)
            Switch(
                checked = usbText,
                onCheckedChange = {
                    usbText = it
                    AppState.usbRadioTextOnly = it
                    AppState.save(context)
                }
            )
        }
        Text(
            "ماژول باید از USB به‌صورت سریال/Bulk دیده شود. گوشی FM فرستنده داخلی ندارد.",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }

    Text("شبکه و ترافیک سازمانی", color = TextPrimary, fontWeight = FontWeight.Bold)
    var blockRelay by remember { mutableStateOf(AppState.orgBlockInternetRelay) }
    var reqCtrl by remember { mutableStateOf(AppState.requireControlSignatures) }
    var sepCtrl by remember { mutableStateOf(AppState.separateControlPlane) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("مسدود کردن رله اینترنت", color = TextPrimary)
            Text("پروفایل دولتی — فقط شبکه محلی", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = blockRelay, onCheckedChange = {
            blockRelay = it; AppState.orgBlockInternetRelay = it; AppState.save(context)
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("امضای بسته‌های کنترل", color = TextPrimary)
            Text("join/approve/reject با HMAC", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = reqCtrl, onCheckedChange = {
            reqCtrl = it; AppState.requireControlSignatures = it; AppState.save(context)
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("جداسازی کانال کنترل", color = TextPrimary)
            Text("پورت داده + ۱ برای مدیریت", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = sepCtrl, onCheckedChange = {
            sepCtrl = it; AppState.separateControlPlane = it; AppState.save(context)
        })
    }
    var bands by remember { mutableStateOf(AppState.allowedUsbRadioBands) }
    OutlinedTextField(
        value = bands,
        onValueChange = {
            bands = it
            AppState.allowedUsbRadioBands = it
            AppState.save(context)
        },
        label = { Text("باندهای مجاز رادیو USB (مثل fm,am)") },
        modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )

    Text("پروفایل انتقال مش", color = TextPrimary, fontWeight = FontWeight.Bold)
    var tMode by remember { mutableStateOf(AppState.transportMode) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            "auto" to "خودکار",
            "wifi" to "وای‌فای",
            "bt" to "بلوتوث",
            "usb" to "رادیو USB"
        ).forEach { (id, label) ->
            FilterChip(
                selected = tMode == id,
                onClick = {
                    tMode = id
                    AppState.transportMode = id
                    AppState.save(context)
                },
                label = { Text(label, fontSize = 11.sp) }
            )
        }
    }
    Text(
        "خودکار: وای‌فای + بلوتوث/رادیو در صورت فعال بودن در تنظیمات",
        color = TextSecondary,
        fontSize = 11.sp
    )

    Text("پشتیبان کلید مشکات", color = TextPrimary, fontWeight = FontWeight.Bold)
    var backupPw by remember { mutableStateOf("") }
    var backupBlob by remember { mutableStateOf("") }
    OutlinedTextField(
        value = backupPw,
        onValueChange = { backupPw = it },
        label = { Text("رمز پشتیبان (حداقل ۶)") },
        modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                try {
                    val out = com.ghostmago.app.crypto.KeyBackup.export(context, backupPw)
                    backupBlob = out
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("mishkat-backup", out))
                    Toast.makeText(context, "پشتیبان ساخته و در کلیپ‌بورد کپی شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) { Text("ساخت پشتیبان", fontSize = 12.sp) }
        Button(
            onClick = {
                try {
                    val blob = backupBlob.ifBlank {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    }
                    val msg = com.ghostmago.app.crypto.KeyBackup.import(context, backupPw, blob)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "خطا", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Secondary)
        ) { Text("بازیابی", fontSize = 12.sp) }
    }
    OutlinedTextField(
        value = backupBlob,
        onValueChange = { backupBlob = it },
        label = { Text("متن پشتیبان (جای‌گذاری)") },
        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
        colors = settingsFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Safety Number اجباری", color = TextPrimary)
            Text("قبل از چت خصوصی باید رو در رو تأیید شود", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = AppState.requireSafetyNumber,
            onCheckedChange = {
                AppState.requireSafetyNumber = it
                AppState.save(context)
            }
        )
    }

    Text("مخفیسازی آیکون مشکات", color = TextPrimary, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(value = hideCode, onValueChange = { hideCode = it.filter { c -> c.isDigit() }.take(8) },
        label = { Text("کد مخفی شمارهگیر (1234)") }, modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    OutlinedTextField(value = showCode, onValueChange = { showCode = it.filter { c -> c.isDigit() }.take(8) },
        label = { Text("کد نمایش شمارهگیر (4321)") }, modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            AppState.hideIconCode = hideCode.ifBlank { "1234" }
            AppState.showIconCode = showCode.ifBlank { "4321" }
            AppState.save(context)
            com.ghostmago.app.util.IconHider.hide(context)
            android.widget.Toast.makeText(context, "آیکون مخفی شد", android.widget.Toast.LENGTH_SHORT).show()
        }, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = RoundedCornerShape(12.dp)) { Text("مخفی الآن") }
        Button(onClick = {
            AppState.hideIconCode = hideCode.ifBlank { "1234" }
            AppState.showIconCode = showCode.ifBlank { "4321" }
            AppState.save(context)
            com.ghostmago.app.util.IconHider.show(context)
            android.widget.Toast.makeText(context, "آیکون نمایان شد", android.widget.Toast.LENGTH_SHORT).show()
        }, colors = ButtonDefaults.buttonColors(containerColor = Secondary), shape = RoundedCornerShape(12.dp)) { Text("نمایش آیکون") }
    }
    Text("پیام ناپدیدشونده پیشفرض (ثانیه، ۰=خاموش)", color = TextSecondary, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 30, 60, 300).forEach { s ->
            FilterChip(selected = ephSec == s, onClick = {
                onEphSec(s)
                AppState.ephemeralDefaultSec = s
                AppState.save(context)
            }, label = { Text(if (s == 0) "خاموش" else "${s}s") })
        }
    }

}
