package com.ghostmago.app.orgtrust

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object CrlStore {
    private const val PREF = "mishkat_crl_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun revoke(context: Context, serial: String, reason: String = "revoked") {
        val p = prefs(context)
        val arr = try {
            JSONArray(p.getString("revoked", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        // avoid dup
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("serial") == serial) return
        }
        arr.put(
            JSONObject()
                .put("serial", serial)
                .put("ts", System.currentTimeMillis() / 1000)
                .put("reason", reason)
        )
        p.edit()
            .putString("revoked", arr.toString())
            .putLong("updated", System.currentTimeMillis() / 1000)
            .apply()
    }

    fun isRevoked(context: Context, serial: String): Boolean {
        val arr = try {
            JSONArray(prefs(context).getString("revoked", "[]"))
        } catch (_: Exception) {
            return false
        }
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("serial") == serial) return true
        }
        return false
    }

    fun exportJson(context: Context): String {
        val p = prefs(context)
        return JSONObject()
            .put("v", 1)
            .put("app", "مشکات")
            .put("updated", p.getLong("updated", 0))
            .put("revoked", JSONArray(p.getString("revoked", "[]")))
            .toString()
    }

    fun importJson(context: Context, json: String) {
        val o = JSONObject(json)
        val incoming = o.getJSONArray("revoked")
        val p = prefs(context)
        val existing = try {
            JSONArray(p.getString("revoked", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val seen = mutableSetOf<String>()
        val merged = JSONArray()
        fun addAll(src: JSONArray) {
            for (i in 0 until src.length()) {
                val e = src.getJSONObject(i)
                val s = e.optString("serial")
                if (s.isNotBlank() && seen.add(s)) merged.put(e)
            }
        }
        addAll(existing)
        addAll(incoming)
        p.edit()
            .putString("revoked", merged.toString())
            .putLong("updated", System.currentTimeMillis() / 1000)
            .apply()
    }

    fun listSerials(context: Context): List<String> {
        val arr = try {
            JSONArray(prefs(context).getString("revoked", "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("serial") }
    }
}

/**
 * Operational identity policy: alias only, strip phone-like data from logs/identity.
 */
