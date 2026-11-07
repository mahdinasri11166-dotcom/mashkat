package com.ghostmago.app.orgtrust

import android.content.Context
import com.ghostmago.app.data.AppState
import java.security.SecureRandom

object IdentityPolicy {
    private val phoneLike = Regex("""(\+?\d[\d\s\-]{7,}\d)""")
    private val emailLike = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    fun sanitizeAlias(raw: String): String {
        var s = raw.trim()
            .replace(phoneLike, "…")
            .replace(emailLike, "…")
            .replace(Regex("""\s+"""), "-")
        if (s.length < 2) s = "OP-" + (1000 + SecureRandom().nextInt(9000))
        return s.take(24)
    }

    fun sanitizeLog(text: String): String =
        text.replace(phoneLike, "[شماره حذف‌شد]")
            .replace(emailLike, "[ایمیل حذف‌شد]")

    fun enforceOperationalNickname(context: Context) {
        val alias = AppState.nickname
        val clean = sanitizeAlias(alias)
        if (clean != alias) {
            AppState.nickname = clean
            AppState.save(context)
        }
    }

    fun requireAliasOrDefault(context: Context): String {
        enforceOperationalNickname(context)
        return AppState.nickname
    }
}

