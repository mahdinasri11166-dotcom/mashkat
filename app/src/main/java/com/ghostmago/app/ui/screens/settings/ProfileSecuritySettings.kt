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
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.ui.theme.*

/**
 * Profile, password, duress, calculator PIN, safe word, read-only, license.
 * Extracted from SettingsScreen.
 */
@Composable
fun ProfileSecuritySettings(
    nick: String, onNick: (String) -> Unit,
    role: MeshRole, onRole: (MeshRole) -> Unit,
    roleGatePw: String, onRoleGatePw: (String) -> Unit,
    oldPw: String, onOldPw: (String) -> Unit,
    newPw: String, onNewPw: (String) -> Unit,
    confirmPw: String, onConfirmPw: (String) -> Unit,
    duressPw: String, onDuressPw: (String) -> Unit,
    vaultPin: String, onVaultPin: (String) -> Unit,
    safeWord: String, onSafeWord: (String) -> Unit,
    readOnly: Boolean, onReadOnly: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var rndDelay by remember { mutableStateOf(AppState.randomSendDelay) }
    var licenseToken by remember { mutableStateOf("") }
    var licensed by remember { mutableStateOf(com.ghostmago.app.util.LicenseManager.isLicensed(context)) }
    val deviceFp = remember { com.ghostmago.app.util.LicenseManager.deviceId(context) }
    SettingsSection("پروفایل")
    OutlinedTextField(value = nick, onValueChange = { onNick(it) },
        label = { Text("نام مستعار") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Text("نقش در مش", color = TextSecondary, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(MeshRole.SUPREME, MeshRole.COMMANDER, MeshRole.LEADER, MeshRole.SOLDIER).forEach { r ->
            FilterChip(selected = role == r, onClick = { onRole(r) },
                label = { Text("${r.symbol}", fontSize = 11.sp) })
        }
    }
    Text(role.fa, color = Accent, fontSize = 12.sp)
    if (role.requiresRolePassword()) {
        OutlinedTextField(
            value = roleGatePw,
            onValueChange = { onRoleGatePw(it) },
            label = { Text("رمز تأیید نقش") },
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors(),
            shape = RoundedCornerShape(12.dp),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
    }
    Button(onClick = {
        if (role.requiresRolePassword() && !role.checkRolePassword(roleGatePw)) {
            Toast.makeText(context, "رمز نقش نادرست", Toast.LENGTH_SHORT).show()
            return@Button
        }
        AppState.nickname = nick
        AppState.meshRole = role
        AppState.save(context)
        onRoleGatePw("")
        Toast.makeText(context, "ذخیره شد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        shape = RoundedCornerShape(12.dp)) { Text("ذخیره پروفایل") }

    SettingsSection("رمز ورود")
    OutlinedTextField(value = oldPw, onValueChange = { onOldPw(it) }, label = { Text("رمز فعلی") },
        modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    OutlinedTextField(value = newPw, onValueChange = { onNewPw(it) }, label = { Text("رمز جدید") },
        modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    OutlinedTextField(value = confirmPw, onValueChange = { onConfirmPw(it) }, label = { Text("تکرار") },
        modifier = Modifier.fillMaxWidth(), colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    Button(onClick = {
        if (AppState.hashPassword(oldPw) != AppState.appPasswordHash) {
            Toast.makeText(context, "رمز فعلی اشتباه", Toast.LENGTH_SHORT).show(); return@Button
        }
        if (newPw.length < 4 || newPw != confirmPw) {
            Toast.makeText(context, "رمز جدید نامعتبر", Toast.LENGTH_SHORT).show(); return@Button
        }
        AppState.setPassword(context, newPw)
        onOldPw(""); onNewPw(""); onConfirmPw("")
        Toast.makeText(context, "رمز تغییر کرد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Warning),
        shape = RoundedCornerShape(12.dp)) { Text("تغییر رمز ورود") }

    SettingsSection("رمز Duress (اضطراری)")
    Text("با این رمز وارد شوید تا حالت جعلی فعال شود.", color = TextSecondary, fontSize = 12.sp)
    OutlinedTextField(value = duressPw, onValueChange = { onDuressPw(it) },
        label = { Text("رمز Duress (خالی = حذف)") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    Button(onClick = {
        AppState.setDuressPassword(context, duressPw)
        onDuressPw("")
        Toast.makeText(context, "Duress ذخیره شد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Danger),
        shape = RoundedCornerShape(12.dp)) { Text("تنظیم Duress") }

    
    SettingsSection("ماشینحساب جعلی / ورود مخفی")
    Text("پین عددی برای باز کردن اپ از ماشینحساب (پیشفرض 0000)", color = TextSecondary, fontSize = 12.sp)
    OutlinedTextField(value = vaultPin, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 12) onVaultPin(it) },
        label = { Text("پین ماشینحساب") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Button(onClick = {
        AppState.setVaultPin(context, vaultPin)
        Toast.makeText(context, "پین ذخیره شد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        shape = RoundedCornerShape(12.dp)) { Text("ذخیره پین") }

    SettingsSection("کلمه امن (Safe Word)")
    Text("اگر تحت فشار این کلمه را بهعنوان رمز ورود بزنید، حالت جعلی فعال میشود.", color = TextSecondary, fontSize = 12.sp)
    OutlinedTextField(value = safeWord, onValueChange = { onSafeWord(it) },
        label = { Text("کلمه امن") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Button(onClick = {
        AppState.setSafeWord(context, safeWord)
        Toast.makeText(context, "کلمه امن ذخیره شد", Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Warning),
        shape = RoundedCornerShape(12.dp)) { Text("ذخیره کلمه امن") }

    SettingsSection("حالت نیرو (فقطخواندنی)")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("اجبار فقطخواندنی", color = TextPrimary)
            Text("بدون ارسال گروهی", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = readOnly, onCheckedChange = {
            onReadOnly(it); AppState.forceReadOnly = it; AppState.save(context)
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("تأخیر تصادفی ارسال", color = TextPrimary)
            Text("۰.۲–۲.۵ ثانیه برای مخفیکردن الگو", color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = rndDelay, onCheckedChange = {
            rndDelay = it; AppState.randomSendDelay = it; AppState.save(context)
        })
    }

    
    
    
    
    SettingsSection("لایسنس سازمانی")
    Text("شناسه دستگاه: $deviceFp", color = TextSecondary, fontSize = 11.sp)
    Text(if (licensed) "✅ فعال" else "⚠ بدون لایسنس / دوره آزمایشی", color = if (licensed) Secondary else Warning, fontSize = 12.sp)
    OutlinedTextField(value = licenseToken, onValueChange = { licenseToken = it },
        label = { Text("توکن لایسنس") }, modifier = Modifier.fillMaxWidth(),
        colors = settingsFieldColors(), shape = RoundedCornerShape(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
            licensed = com.ghostmago.app.util.LicenseManager.activate(context, licenseToken)
            Toast.makeText(context, if (licensed) "لایسنس فعال شد" else "نامعتبر", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("فعالسازی")
        }
        Button(onClick = {
            val t = com.ghostmago.app.util.LicenseManager.generateForThisDevice(context, 365)
            licenseToken = t
            Toast.makeText(context, "توکن این دستگاه ساخته شد", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Text("تولید توکن")
        }
    }

    
    
}
