package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class CarrierRestoreCoordinatorTest {
    private val original = "99901"
    private val overridden = "998002"

    private inner class Device {
        var numeric = overridden
        var backup = true
        var config = true
        var apnsCleaned = false
        var apnFailure: Exception? = null
        var identityFailure = false
        var configFailure = false
        var rejectNumeric = false
        var reloadAfterCleanup = false
        var discardFailure = false
        var expected: String? = original
        var needsRestart = false
        var persistent = true
        val events = mutableListOf<String>()
        fun restore() = CarrierRestoreCoordinator(
            restoreIdentity = {
                events += "identity"
                check(!identityFailure)
                if (!rejectNumeric && expected != null) numeric = expected!!
                IdentityRestoreResult(expected, needsRestart)
            },
            clearConfig = {
                events += "config"
                check(!configFailure)
                config = false
                persistent
            },
            verifyIdentity = { expected ->
                events += "verify"
                check(numeric == expected)
            },
            cleanupApns = {
                events += "apn"
                check(numeric == original && !config)
                apnFailure?.let { throw it }
                apnsCleaned = true
                if (reloadAfterCleanup) numeric = overridden
            },
            completeRestore = {
                events += "backup"
                check(!discardFailure)
                if (!it.requiresRestart) backup = false
            },
        ).restore()
    }

    @Test fun apnPermissionDenialCannotPreventOriginalCodeAndConfigRestoration() {
        val device = Device().apply { apnFailure = SecurityException("denied") }
        val report = device.restore()
        assertEquals(original, device.numeric)
        assertFalse(device.config)
        assertEquals(original, report.restoredNumeric)
        assertNotNull(report.warning)
        assertTrue(device.backup)
        assertEquals(listOf("identity", "config", "verify", "apn", "verify"), device.events)
    }

    @Test fun emptyApnListIsASeparateWarningAfterCodeRestoration() {
        val device = Device().apply { apnFailure = IllegalStateException("empty APN list") }
        val report = device.restore()
        assertEquals(original, report.restoredNumeric)
        assertFalse(device.config)
        assertTrue(device.backup)
        assertFalse(device.apnsCleaned)
        assertNotNull(report.warning)
    }

    @Test fun failedPreferredApnWriteDoesNotTurnIdentityRecoveryIntoFailure() {
        val device = Device().apply { apnFailure = IllegalStateException("preferred APN not saved") }
        val report = device.restore()
        assertEquals(original, report.restoredNumeric)
        assertTrue(device.backup)
    }

    @Test fun partialApnFailureDoesNotExposeProviderCredentialsOrSimIds() {
        val secret = "private APN credential and SIM identity"
        val report = Device().apply { apnFailure = IllegalArgumentException(secret) }.restore()
        assertFalse(report.warning!!.contains(secret))
    }

    @Test fun countryAndNameAreStillClearedWhenIdentityBinderFails() {
        val device = Device().apply { identityFailure = true }
        val error = assertThrows(IllegalStateException::class.java) { device.restore() }
        assertFalse(device.config)
        assertEquals(overridden, device.numeric)
        assertTrue(device.backup)
        assertFalse(device.events.contains("apn"))
        assertTrue(error.message!!.contains("代碼尚未確認"))
    }

    @Test fun unsuccessfulNumericReadbackCannotBeReportedAsRestored() {
        val device = Device().apply { rejectNumeric = true }
        assertThrows(IllegalStateException::class.java) { device.restore() }
        assertFalse(device.config)
        assertTrue(device.backup)
        assertFalse(device.events.contains("apn"))
    }

    @Test fun configFailurePreservesTheAlreadyRestoredIdentityAndItsBackup() {
        val device = Device().apply { configFailure = true }
        val error = assertThrows(IllegalStateException::class.java) { device.restore() }
        assertEquals(original, device.numeric)
        assertTrue(device.backup)
        assertFalse(device.events.contains("apn"))
        assertTrue(error.message!!.contains("原 SIM 代碼已回讀確認"))
    }

    @Test fun aLateSimReloadCannotReturnAStaleRestorationSuccess() {
        val device = Device().apply { reloadAfterCleanup = true }
        assertThrows(IllegalStateException::class.java) { device.restore() }
        assertTrue(device.backup)
        assertFalse(device.events.contains("backup"))
    }

    @Test fun unavailableOriginalCodeClearsConfigButDoesNotClaimNumericRecovery() {
        val device = Device().apply { expected = null }
        val report = device.restore()
        assertFalse(device.config)
        assertNull(report.restoredNumeric)
        assertTrue(report.requiresRestart)
        assertNotNull(report.warning)
        assertTrue(device.backup)
        assertFalse(device.events.contains("apn"))
    }

    @Test fun fullyClearedAndVerifiedRecoveryCanDiscardTheBackup() {
        val device = Device()
        val report = device.restore()
        assertEquals(original, report.restoredNumeric)
        assertTrue(device.apnsCleaned)
        assertFalse(device.backup)
        assertNull(report.warning)
        assertFalse(report.requiresRestart)
    }

    @Test fun fallbackIdentitySetterRetainsBackupAndRequestsReboot() {
        val device = Device().apply { needsRestart = true }
        val report = device.restore()
        assertEquals(original, report.restoredNumeric)
        assertTrue(report.requiresRestart)
        assertTrue(device.backup)
    }

    @Test fun temporaryConfigClearingRequestsRebootEvenIfIdentityWasFullyCleared() {
        val report = Device().apply { persistent = false }.restore()
        assertFalse(report.persistent)
        assertTrue(report.requiresRestart)
    }

    @Test fun failedBackupRemovalIsSeparateFromVerifiedIdentityRecovery() {
        val device = Device().apply { discardFailure = true }
        val report = device.restore()
        assertEquals(original, report.restoredNumeric)
        assertNotNull(report.warning)
        assertTrue(device.backup)
    }

    @Test fun newNumericOverridesAreRejectedBeforeAnyOperation() {
        NumericOverridePolicy.requireAllowed(null)
        for (code in listOf("99901", "999002", "")) {
            assertThrows(IllegalArgumentException::class.java) { NumericOverridePolicy.requireAllowed(code) }
        }
    }

    @Test fun backupAndSubscriptionFallbackPreserveLeadingZeroes() {
        assertEquals("999002", NumericOverridePolicy.original("999002", "99801"))
        assertEquals("99801", NumericOverridePolicy.original(null, "99801"))
        assertEquals("998001", NumericOverridePolicy.original("invalid", "998001"))
        assertEquals("999002", NumericOverridePolicy.original(" 999002 ", "99801"))
        assertNull(NumericOverridePolicy.original(null, ""))
    }
}
