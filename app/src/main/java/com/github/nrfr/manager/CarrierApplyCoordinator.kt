package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric

/** The 1.1.2 apply order; APN access is not part of identity submission. */
internal class CarrierApplyCoordinator(
    private val applyIdentity: (String) -> Unit,
    private val applyConfig: () -> Boolean,
    private val verify: (String?) -> Unit,
    private val rollbackIdentity: () -> Unit,
) {
    fun apply(numeric: String?, hasConfig: Boolean): Boolean {
        val code = numeric?.let { OperatorNumeric.parse(it).value }
        var identityAttempted = false
        try {
            if (code != null) {
                identityAttempted = true
                applyIdentity(code)
            }
            // Identity changes can reload CarrierConfig, so country/name must be applied afterwards.
            val persistent = if (hasConfig) applyConfig() else false
            verify(code)
            return persistent && code == null
        } catch (failure: Exception) {
            if (identityAttempted) {
                try {
                    rollbackIdentity()
                } catch (rollback: Exception) {
                    failure.addSuppressed(rollback)
                    throw IllegalStateException("代碼操作失敗，且自動還原尚未確認；請執行還原設定並核對 APN", failure)
                }
            }
            throw failure
        }
    }
}
