package com.ghostmago.app.mesh

import android.content.Context
import com.ghostmago.app.BuildConfig
import com.ghostmago.app.data.MeshRole
import com.ghostmago.app.orgtrust.IdentityPolicy
import com.ghostmago.app.orgtrust.OrgTrust
import org.json.JSONObject

/**
 * Join / membership helpers — keeps MeshNetwork thinner.
 * Builds control payloads and evaluates org-trust on JOIN_REQ.
 */
object MeshJoinLogic {

    fun buildJoinRequestPayload(
        context: Context,
        nickname: String,
        role: MeshRole
    ): JSONObject {
        val safeNick = try {
            IdentityPolicy.sanitizeAlias(nickname)
        } catch (_: Exception) {
            nickname
        }
        return JSONObject().apply {
            put("nick", safeNick)
            put("role", role.value)
            put("appVer", BuildConfig.VERSION_NAME)
            put("proto", 7)
            put("ts", System.currentTimeMillis() / 1000.0)
            try {
                OrgTrust.getMyCertificate(context)?.let { c ->
                    put("cert", c.toJson())
                }
            } catch (_: Exception) {
            }
            try {
                OrgTrust.getPendingInvite(context)?.let { inv ->
                    put("inviteTid", inv.tokenId)
                    put(
                        "invite",
                        JSONObject()
                            .put("tid", inv.tokenId)
                            .put("gid", inv.groupId)
                            .put("role", inv.role)
                            .put("exp", inv.expiresAt)
                            .put("uses", inv.maxUses)
                            .put("caId", inv.caId)
                            .put("sig", inv.signatureB64)
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun buildAcceptPayload(commanderNick: String, newRole: String): JSONObject =
        JSONObject().apply {
            put("role", newRole)
            put("nick", commanderNick)
            put("ts", System.currentTimeMillis() / 1000.0)
        }

    fun buildRejectPayload(reason: String = "رد شد"): JSONObject =
        JSONObject().apply {
            put("reason", reason)
            put("ts", System.currentTimeMillis() / 1000.0)
        }

    fun buildRemoteWipePayload(commanderNick: String, reason: String): JSONObject =
        JSONObject().apply {
            put("cmd", "WIPE")
            put("reason", reason)
            put("nick", commanderNick)
            put("ts", System.currentTimeMillis() / 1000.0)
            put(
                "sig",
                com.ghostmago.app.crypto.CryptoEngine.sha256(
                    ("WIPE|$reason|${commanderNick}").toByteArray()
                ).take(8).joinToString("") { "%02x".format(it) }
            )
        }

    data class JoinTrustResult(
        val displayNick: String,
        val role: String,
        val statusNote: String?
    )

    /**
     * Evaluate cert + invite on JOIN_REQ for commander UI.
     */
    fun evaluateJoinTrust(
        context: Context,
        json: JSONObject,
        groupId: String,
        rawNick: String,
        roleStr: String
    ): JoinTrustResult {
        val safeNick = try {
            IdentityPolicy.sanitizeAlias(rawNick.ifBlank { "OP" })
        } catch (_: Exception) {
            rawNick.ifBlank { "OP" }
        }
        var trustNote = ""
        var statusNote: String? = null
        try {
            if (json.has("cert")) {
                val cert = OrgTrust.MemberCert.fromJson(json.getJSONObject("cert"))
                val err = OrgTrust.verifyCertificate(context, cert)
                if (err != null) {
                    statusNote = "رد اعتماد: $err ($safeNick)"
                    trustNote = "⚠$err"
                } else {
                    trustNote = "✓گواهی"
                }
            }
            if (json.has("invite")) {
                val invO = json.getJSONObject("invite")
                val inv = OrgTrust.InviteToken(
                    tokenId = invO.getString("tid"),
                    groupId = invO.optString("gid", groupId),
                    role = invO.optString("role", roleStr),
                    expiresAt = invO.getLong("exp"),
                    maxUses = invO.optInt("uses", 1),
                    caId = invO.getString("caId"),
                    signatureB64 = invO.getString("sig")
                )
                val ierr = OrgTrust.verifyInvite(context, inv)
                if (ierr != null) {
                    statusNote = "دعوت نامعتبر: $ierr"
                    trustNote = (trustNote + " ⚠دعوت").trim()
                } else {
                    trustNote = (trustNote + " ✓دعوت").trim()
                    try {
                        OrgTrust.consumeInvite(context, inv.tokenId)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            trustNote = "⚠اعتماد"
        }
        val display = if (trustNote.isNotEmpty()) "$safeNick $trustNote" else safeNick
        return JoinTrustResult(display, roleStr, statusNote)
    }
}
