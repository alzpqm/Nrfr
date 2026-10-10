package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric

/** The 1.1.2 apply order; APN access is not part of identity submission. */
internal class CarrierApplyCoordinator(
    private val applyIdentity: (String) -> Unit,
    private val applyConfig: () -> Boolean,
    private val verify: (String?) -> Unit,
) {
    fun apply(numeric: String?, hasConfig: Boolean): Boolean {
        val code = numeric?.let { OperatorNumeric.parse(it).value }
        code?.let(applyIdentity)
        // Identity changes can reload CarrierConfig, so country/name must be applied afterwards.
        val persistent = if (hasConfig) applyConfig() else false
        verify(code)
        return persistent && code == null
    }
}
