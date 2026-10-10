package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric
import java.security.MessageDigest

// APN payloads may contain credentials. They stay in memory and must not be logged or serialized.
internal class ApnProfile(val id: Long, val fields: Map<String, String?>)

internal data class ApnAlias(
    val sourceId: Long,
    val name: String,
    val fingerprint: String,
    val id: Long? = null,
)

// The durable recovery journal contains row IDs and hashes, never APN credentials or raw SIM IDs.
internal data class ApnJournal(
    val numeric: String,
    val preferredSourceId: Long?,
    val aliases: List<ApnAlias>,
)

internal class ApnApplyException(message: String, val restoredSelection: Long?) : IllegalStateException(message)

internal object ApnProfiles {
    private val volatileColumns = setOf("_id", "current", "edited")
    private val uniqueColumns = listOf(
        "numeric", "mcc", "mnc", "apn", "proxy", "port", "mmsproxy", "mmsport", "mmsc",
        "carrier_enabled", "bearer", "mvno_type", "mvno_match_data", "profile_id", "protocol",
        "roaming_protocol", "user_editable", "owned_by", "apn_set_id", "carrier_id",
        "infrastructure_bitmask", "esim_bootstrap_provisioning",
    )

    fun alias(source: ApnProfile, numeric: String, iccId: String, subId: Int, name: String): ApnProfile {
        val code = OperatorNumeric.parse(numeric)
        require(subId >= 0)
        // ICCID matching is prefix based. Reject lists and short/unknown identities before writing.
        require(iccId.matches(Regex("[0-9A-Fa-f]{18,32}"))) { "無法確認 APN 的 SIM 綁定，尚未覆寫代碼" }
        require(source.fields["owned_by"].orEmpty() in setOf("", "0")) { "不支援覆寫由裝置管理員管理的 APN" }
        return ApnProfile(-1, source.fields.filterKeys { it !in volatileColumns } + mapOf(
            "name" to name,
            "numeric" to code.value,
            "mcc" to code.mcc,
            "mnc" to code.mnc,
            "mvno_type" to "iccid",
            "mvno_match_data" to iccId,
            "carrier_id" to "-1",
            "sub_id" to subId.toString(),
            "owned_by" to "0",
            "edited" to "1",
        ))
    }

    fun fingerprint(profile: ApnProfile): String = digest(profile.fields.filterKeys { it !in volatileColumns })
    fun uniqueKey(profile: ApnProfile): String = digest(uniqueColumns.associateWith { profile.fields[it] })
    private fun digest(fields: Map<String, String?>): String {
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

internal class ApnOverrideCoordinator(
    private val backend: Backend,
    private val originalNumeric: String,
    private val iccId: String,
    private val subId: Int,
) {
    interface Backend {
        fun checkAccess()
        fun journal(): ApnJournal?
        fun save(journal: ApnJournal?)
        fun preferred(): Long?
        fun setPreferred(id: Long?)
        fun restoreIdentity()
        fun setIdentity(numeric: String)
        fun originalProfiles(): List<ApnProfile>
        fun assertSimBinding()
        fun existingAliases(numeric: String): List<ApnProfile>
        fun insert(profile: ApnProfile): ApnProfile
        fun delete(profile: ApnProfile)
        fun verify(numeric: String, requiredIds: Set<Long>, preferred: Long?)
    }

    fun apply(numeric: String, complete: () -> Unit) {
        val target = OperatorNumeric.parse(numeric).value
        backend.checkAccess() // A permission failure must happen before changing the SIM identity.
        val previousSelection = backend.preferred()
        var fallbackSelection = previousSelection
        var stage = "讀取原 APN"
        try {
            backend.restoreIdentity()
            recover(previousSelection)
            val originals = backend.originalProfiles()
            check(originals.isNotEmpty()) { "無法取得原 SIM 的 APN，已保留原代碼；請確認 SIM 與 APN 已就緒" }
            val originalIds = originals.map { it.id }.toSet()
            val preferred = previousSelection?.takeIf { it in originalIds }
                ?: backend.preferred()?.takeIf { it in originalIds }
            fallbackSelection = preferred
            if (preferred != null) backend.setPreferred(preferred)
            if (target == originalNumeric) {
                complete()
                backend.verify(target, originalIds, preferred)
                return
            }
            backend.assertSimBinding()
            check(backend.existingAliases(target).isEmpty()) { "此 SIM 已有相同代碼的 APN 綁定，未覆蓋既有項目；請先還原其他工具的 APN 修改" }
            val profiles = originals.map { source ->
                ApnProfiles.alias(source, target, iccId, subId, "${source.fields["name"].orEmpty()}（Nrfr）")
            }
            check(profiles.map(ApnProfiles::uniqueKey).toSet().size == profiles.size) {
                "原 APN 有無法安全區分的項目，未覆寫代碼或合併 APN"
            }
            var journal = ApnJournal(target, preferred, profiles.mapIndexed { index, profile ->
                ApnAlias(originals[index].id, profile.fields.getValue("name")!!, ApnProfiles.fingerprint(profile))
            })
            // Persist the intent before the first insert, so an interrupted operation is recoverable.
            stage = "保留 APN"
            backend.save(journal)
            for ((index, profile) in profiles.withIndex()) {
                val inserted = backend.insert(profile)
                val expectedFingerprint = journal.aliases[index].fingerprint
                val actualFingerprint = ApnProfiles.fingerprint(inserted)
                journal = journal.copy(aliases = journal.aliases.mapIndexed { i, alias ->
                    if (i == index) alias.copy(id = inserted.id, fingerprint = actualFingerprint) else alias
                })
                backend.save(journal)
                check(actualFingerprint == expectedFingerprint) {
                    "系統未完整保留 APN 參數，尚未確認套用"
                }
            }
            backend.setIdentity(target)
            val preferredAlias = journal.aliases.firstOrNull { it.sourceId == preferred }?.id
            if (preferredAlias != null) backend.setPreferred(preferredAlias)
            stage = "套用設定與回讀"
            complete()
            backend.verify(target, journal.aliases.map { checkNotNull(it.id) }.toSet(), preferredAlias)
        } catch (_: Exception) {
            try {
                val selected = backend.preferred()
                backend.restoreIdentity()
                recover(selected)
                val restored = backend.originalProfiles()
                check(restored.isNotEmpty())
                if (backend.journal() == null && backend.preferred() == null) {
                    fallbackSelection?.takeIf { id -> restored.any { it.id == id } }?.let(backend::setPreferred)
                }
                backend.verify(originalNumeric, emptySet(), backend.preferred())
            } catch (_: Exception) {
                error("代碼套用失敗，APN 回復尚未完成；備份已保留，請重新執行「還原設定」並核對 APN，必要時重新啟動手機")
            }
            throw ApnApplyException("「$stage」失敗，已回復原 SIM 代碼與 APN；系統未接受完整的 APN 保留流程", backend.preferred())
        }
    }

    fun restore(): Long? {
        backend.checkAccess()
        val selected = backend.preferred()
        backend.restoreIdentity()
        recover(selected)
        val originals = backend.originalProfiles()
        check(originals.isNotEmpty()) { "原 SIM 代碼已回復，但無法確認 APN 清單；備份已保留，請重新啟動手機後核對" }
        selected?.takeIf { id -> originals.any { it.id == id } }?.let(backend::setPreferred)
        backend.verify(originalNumeric, emptySet(), backend.preferred())
        return backend.preferred()
    }

    private fun recover(selected: Long?) {
        val journal = backend.journal() ?: return
        val rows = backend.existingAliases(journal.numeric)
        val owned = journal.aliases.mapNotNull { alias ->
            val row = rows.firstOrNull {
                it.fields["name"] == alias.name &&
                    (if (alias.id == null) ApnProfiles.fingerprint(it) == alias.fingerprint else alias.id == it.id)
            }
                ?: return@mapNotNull null // Deleted rows and reused IDs must not delete somebody else's APN.
            alias to row
        }
        val sourceSelection = owned.firstOrNull { it.second.id == selected }?.first?.sourceId
            ?: journal.preferredSourceId
        // Select the source before deleting a selected alias. Selection is always scoped by subId.
        val originals = backend.originalProfiles().map { it.id }.toSet()
        val preferred = sourceSelection?.takeIf { it in originals }
        backend.setPreferred(preferred)
        check(owned.all { (alias, row) -> ApnProfiles.fingerprint(row) == alias.fingerprint }) {
            "原 SIM 與 APN 選取值已回復；相容項目曾被其他工具修改，未刪除該項目，請核對系統 APN 並重新執行還原"
        }
        owned.forEach { backend.delete(it.second) }
        backend.verify(originalNumeric, emptySet(), preferred)
        backend.save(null)
    }
}
