package com.ghostmago.app.ui.screens.mesh

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmago.app.ui.theme.CardDark
import com.ghostmago.app.ui.theme.Danger
import com.ghostmago.app.ui.theme.Secondary
import com.ghostmago.app.ui.theme.TextSecondary
import com.ghostmago.app.ui.theme.Warning

@Composable
fun MeshInviteQrBanner(bitmap: Bitmap?, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible || bitmap == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR",
                modifier = Modifier.size(180.dp)
            )
            TextButton(onClick = onDismiss) {
                Text("بستن", color = Danger)
            }
        }
    }
}

@Composable
fun MeshSessionHeader(
    status: String,
    waitingApproval: Boolean,
    membershipOk: Boolean,
    memberCount: Int,
    onlineCount: Int
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(CardDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text("● $status", color = Secondary, fontSize = 12.sp, maxLines = 2)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                when {
                    waitingApproval && !membershipOk -> "⏳ منتظر تأیید فرمانده"
                    membershipOk -> "✓ عضو شبکه"
                    else -> "شبکه فعال"
                },
                color = if (waitingApproval && !membershipOk) Warning else Secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "اعضا $memberCount · آنلاین $onlineCount",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}
