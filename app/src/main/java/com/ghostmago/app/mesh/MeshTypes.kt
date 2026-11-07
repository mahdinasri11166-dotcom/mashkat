package com.ghostmago.app.mesh

/**
 * Shared mesh data types — extracted from MeshNetwork for smaller files
 * and reuse across UI / health / notifier modules.
 */
data class MemberInfo(
    val nick: String,
    val role: String,
    val joinedAt: Long,
    val lastSeen: Long = joinedAt
)

data class NeighborInfo(
    val nick: String,
    val lastSeen: Long,
    val ip: String,
    val hops: Int
)

/** hash, nick, role, statusFa (آنلاین/آفلاین), lastSeen */
data class MemberStatus(
    val hash: String,
    val nick: String,
    val role: String,
    val statusFa: String,
    val lastSeen: Long,
    val online: Boolean
)
