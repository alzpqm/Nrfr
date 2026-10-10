package com.github.nrfr.manager

import java.security.MessageDigest

// APN credentials stay in memory. The 1.1.3 journal contains only row IDs/names and hashes.
internal class ApnProfile(val id: Long, val fields: Map<String, String?>)
internal data class ApnAlias(val sourceId: Long, val name: String, val fingerprint: String, val id: Long? = null)
internal data class ApnJournal(val numeric: String, val preferredSourceId: Long?, val aliases: List<ApnAlias>)

internal object ApnProfiles {
    // Keep the 1.1.3 fingerprint format for cleanup of already-created rows.
    fun fingerprint(profile: ApnProfile): String {
        val fields = profile.fields.filterKeys { it !in setOf("_id", "current", "edited") }
        val text = buildString {
            for ((key, value) in fields.toSortedMap()) {
                append(key.length).append(':').append(key).append('=')
                if (value == null) append('-') else append(value.length).append(':').append(value)
                append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Cleanup only: no identity setter, APN creation, or profile rewriting. */
internal class LegacyApnCleanupCoordinator(private val backend: Backend, private val originalNumeric: String) {
    interface Backend {
        fun checkAccess()
        fun journal(): ApnJournal?
        fun clearJournal()
        fun preferred(): Long?
        fun setPreferred(id: Long)
        fun originalProfiles(): List<ApnProfile>
        fun existingAliases(numeric: String): List<ApnProfile>
        fun delete(profile: ApnProfile)
        fun verify(numeric: String, preferred: Long?)
    }

    fun cleanup() {
        val journal = backend.journal() ?: return
        check(journal.numeric != originalNumeric) { "無法區分原 APN 與相容項目，保留回復紀錄" }
        backend.checkAccess()
        val selected = backend.preferred()
        val originals = backend.originalProfiles().map { it.id }.toSet()
        check(originals.isNotEmpty()) { "原 APN 清單尚未就緒，未清理舊項目" }
        val rows = backend.existingAliases(journal.numeric)
        val owned = journal.aliases.mapNotNull { alias ->
            val row = if (alias.id == null) {
                val named = rows.filter { it.fields["name"] == alias.name }
                named.firstOrNull { ApnProfiles.fingerprint(it) == alias.fingerprint }.also {
                    check(it != null || named.isEmpty()) { "待清理 APN 的參數已變更，保留回復紀錄" }
                }
            } else rows.firstOrNull { it.id == alias.id }
            row ?: return@mapNotNull null // A missing row must not be replaced by another row.
            check(row.fields["name"] == alias.name && ApnProfiles.fingerprint(row) == alias.fingerprint) {
                "舊 APN 項目曾被外部修改，未刪除該項目"
            }
            alias to row
        }
        // Keep a user's current original selection. Only map an old alias back to its source.
        val preferred = selected?.takeIf { it in originals }
            ?: owned.firstOrNull { it.second.id == selected }?.first?.sourceId?.takeIf { it in originals }
            ?: journal.preferredSourceId?.takeIf { it in originals }
        check(preferred != null || owned.none { it.second.id == selected }) {
            "無法確認所選相容 APN 的原項目，未清理該項目"
        }
        if (preferred != null && preferred != selected) backend.setPreferred(preferred)
        backend.verify(originalNumeric, preferred)
        owned.forEach { backend.delete(it.second) }
        backend.verify(originalNumeric, preferred)
        backend.clearJournal()
    }
}
