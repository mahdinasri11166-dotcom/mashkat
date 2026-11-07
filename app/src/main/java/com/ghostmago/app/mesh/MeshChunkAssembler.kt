package com.ghostmago.app.mesh

import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Assembles multi-chunk file transfers for the mesh.
 */
class MeshChunkAssembler(private val maxFiles: Int = 32) {

    private val buffers = ConcurrentHashMap<String, Array<ByteArray?>>()
    private val meta = ConcurrentHashMap<String, Pair<String, String>>() // fid -> filename, nick

    sealed class Result {
        data class Progress(val got: Int, val total: Int, val filename: String) : Result()
        data class Complete(
            val filename: String,
            val nick: String,
            val data: ByteArray
        ) : Result()
        object Ignored : Result()
    }

    fun clear() {
        buffers.clear()
        meta.clear()
    }

    fun offer(
        fid: String,
        index: Int,
        total: Int,
        filename: String,
        nick: String,
        b64: String
    ): Result {
        if (fid.isBlank() || index < 0 || total <= 0 || b64.isBlank()) return Result.Ignored
        if (buffers.size > maxFiles && !buffers.containsKey(fid)) {
            // drop oldest-ish: clear all to avoid unbounded growth
            clear()
        }
        val arr = buffers.getOrPut(fid) { arrayOfNulls(total) }
        if (index >= arr.size) return Result.Ignored
        try {
            arr[index] = Base64.decode(b64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return Result.Ignored
        }
        meta[fid] = filename to nick
        if (arr.all { it != null }) {
            val full = arr.filterNotNull().fold(ByteArray(0)) { a, b -> a + b }
            buffers.remove(fid)
            val m = meta.remove(fid)
            return Result.Complete(m?.first ?: filename, m?.second ?: nick, full)
        }
        return Result.Progress(arr.count { it != null }, total, filename)
    }
}
