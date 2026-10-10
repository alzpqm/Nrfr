package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric

internal data class IdentityRestoreResult(val expectedNumeric: String?, val requiresRestart: Boolean = false)

internal object NumericOverridePolicy {
    fun requireAllowed(numeric: String?) {
        require(numeric == null) {
            "MCC／MNC 修改暫停：尚未確認可保留 APN。請只套用國家碼或名稱，或執行「還原設定」"
        }
    }

    fun original(backup: String?, subscription: String?): String? =
        listOfNotNull(backup, subscription).firstNotNullOfOrNull {
            runCatching { OperatorNumeric.parse(it).value }.getOrNull()
        }
}

/** Identity recovery must never depend on APN permissions, queries, or cleanup. */
internal class CarrierRestoreCoordinator(
    private val restoreIdentity: () -> IdentityRestoreResult,
    private val clearConfig: () -> Boolean,
    private val verifyIdentity: (String) -> Unit,
    private val cleanupApns: (IdentityRestoreResult) -> Unit,
    private val completeRestore: (IdentityRestoreResult) -> Unit,
) {
    data class Report(val persistent: Boolean, val requiresRestart: Boolean,
        val restoredNumeric: String?, val warning: String?)

    fun restore(): Report {
        val identity = try { restoreIdentity() } catch (_: Exception) { null }
        // Clear country/name even if the identity call failed. Neither operation needs APN access.
        var configCleared = false
        val persistent = try { clearConfig().also { configCleared = true } } catch (_: Exception) { false }
        val identityConfirmed = identity != null && (identity.expectedNumeric == null ||
            try { verifyIdentity(identity.expectedNumeric); true } catch (_: Exception) { false })
        if (!configCleared || !identityConfirmed) {
            val message = when {
                configCleared -> "國家碼／名稱覆寫已提交清除，但原 SIM 代碼尚未確認還原；備份已保留，請重新啟動手機後核對"
                identityConfirmed && identity?.expectedNumeric != null -> "原 SIM 代碼已回讀確認，但國家碼／名稱覆寫清除失敗；備份已保留，請重新執行還原"
                else -> "代碼與設定還原尚未確認；備份已保留，請重新啟動手機後核對並重試"
            }
            error(message)
        }
        checkNotNull(identity)
        val warnings = mutableListOf<String>()
        if (identity.expectedNumeric == null) {
            warnings += "無法取得原 SIM 代碼；尚未確認代碼還原，請重新啟動手機後核對。"
        } else {
            var cleanupComplete = false
            try {
                cleanupApns(identity)
                cleanupComplete = true
            } catch (_: Exception) {
                // Do not turn a confirmed identity restore into a failure or erase its backup.
                warnings += "原 SIM 代碼已回讀確認；舊 APN 相容項目尚未清理完成，備份已保留。請重啟後核對 APN，再執行還原。"
            }
            try { verifyIdentity(identity.expectedNumeric) } catch (_: Exception) {
                error("還原後系統重新載入電信設定，原 SIM 代碼回讀尚未確認；備份已保留，請重新啟動手機後核對")
            }
            if (cleanupComplete) try { completeRestore(identity) } catch (_: Exception) {
                warnings += "原 SIM 代碼已回讀確認；本機備份清理未完成，可重新執行還原。"
            }
        }
        return Report(persistent, identity.requiresRestart || !persistent || identity.expectedNumeric == null,
            identity.expectedNumeric, warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
