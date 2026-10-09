package com.github.nrfr.manager

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PendingRequestStore<T>(
    private val ttlMillis: Long,
    private val clock: () -> Long,
) {
    private data class Entry<T>(val ownerPid: Int, val createdAt: Long, val value: T)
    private val entries = ConcurrentHashMap<String, Entry<T>>()

    fun add(ownerPid: Int, value: T): String = UUID.randomUUID().toString().also {
        entries[it] = Entry(ownerPid, clock(), value)
    }

    fun take(token: String, ownerPid: Int): T? {
        val entry = entries.remove(token) ?: return null
        if (entry.ownerPid != ownerPid || clock() - entry.createdAt >= ttlMillis) return null
        return entry.value
    }

    fun remove(token: String) {
        entries.remove(token)
    }
}
