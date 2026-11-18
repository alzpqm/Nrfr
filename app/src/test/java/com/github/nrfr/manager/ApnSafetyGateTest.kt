package com.github.nrfr.manager

import org.junit.Assert.assertThrows
import org.junit.Test

class ApnSafetyGateTest {
    @Test fun targetWithApnsCanProceed() {
        ApnSafetyGate.preflight(currentVisible = 5, targetRaw = 2)
        ApnSafetyGate.afterApply(visible = 2, filtered = 2)
    }

    @Test fun missingTargetApnsStopsBeforeIdentityChange() {
        assertThrows(IllegalStateException::class.java) {
            ApnSafetyGate.preflight(currentVisible = 5, targetRaw = 0)
        }
    }

    @Test fun existingEmptyListDoesNotCountAsHealthyBaseline() {
        assertThrows(IllegalStateException::class.java) {
            ApnSafetyGate.preflight(currentVisible = 0, targetRaw = 2)
        }
    }

    @Test fun postApplyHiddenListFailsEvenWhenRawRowsExist() {
        assertThrows(IllegalStateException::class.java) {
            ApnSafetyGate.afterApply(visible = 0, filtered = 0)
        }
    }
}
