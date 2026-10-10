package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class CarrierApplyCoordinatorTest {
    private inner class Device {
        var numeric = "998002"
        var country = "old"
        var savedCountry = "new"
        var persistent = true
        var identityFailure = false
        var configFailure = false
        var rejectNumeric = false
        val events = mutableListOf<String>()
        fun apply(code: String?, hasConfig: Boolean = true) = CarrierApplyCoordinator(
            applyIdentity = {
                events += "identity"
                check(!identityFailure)
                if (!rejectNumeric) numeric = it
                // The phone service can reload country/name when its identity changes.
                country = "reloaded"
            },
            applyConfig = {
                events += "config"
                check(!configFailure)
                country = savedCountry
                persistent
            },
            verify = {
                events += "verify"
                check(it == null || it == numeric)
                check(!hasConfig || country == savedCountry)
            },
        ).apply(code, hasConfig)
    }

    @Test fun submittingTheOriginalCodeAfterAnOverrideWorksAndReappliesCountryLast() {
        val device = Device()
        assertFalse(device.apply("99901"))
        assertEquals("99901", device.numeric)
        assertEquals("new", device.country)
        assertEquals(listOf("identity", "config", "verify"), device.events)
    }

    @Test fun sixDigitCodeKeepsLeadingZeroesAndIsNeverReportedAsPersistent() {
        val device = Device()
        assertFalse(device.apply(" 999002 "))
        assertEquals("999002", device.numeric)
    }

    @Test fun countryAndNameOnlyDoNotTouchTheSimIdentity() {
        val device = Device()
        assertTrue(device.apply(null))
        assertEquals("998002", device.numeric)
        assertEquals(listOf("config", "verify"), device.events)
    }

    @Test fun numericOnlySkipsTheCarrierConfigWrite() {
        val device = Device()
        assertFalse(device.apply("99901", hasConfig = false))
        assertEquals(listOf("identity", "verify"), device.events)
    }

    @Test fun malformedCodesCannotChangeIdentityOrCountry() {
        for (code in listOf("", "9990", "9990000", "9990x", "９９９０１")) {
            val device = Device()
            assertThrows(IllegalArgumentException::class.java) { device.apply(code) }
            assertEquals("998002", device.numeric)
            assertEquals("old", device.country)
            assertTrue(device.events.isEmpty())
        }
    }

    @Test fun rejectedIdentitySubmissionCannotBeReportedAsSaved() {
        val device = Device().apply { identityFailure = true }
        assertThrows(IllegalStateException::class.java) { device.apply("99901") }
        assertEquals("998002", device.numeric)
        assertEquals(listOf("identity"), device.events)
    }

    @Test fun aNumericReadbackMismatchCannotBeReportedAsSuccess() {
        val device = Device().apply { rejectNumeric = true }
        assertThrows(IllegalStateException::class.java) { device.apply("99901") }
        assertEquals("998002", device.numeric)
    }

    @Test fun aConfigFailureKeepsTheSubmittedIdentityAndReportsFailure() {
        val device = Device().apply { configFailure = true }
        assertThrows(IllegalStateException::class.java) { device.apply("99901") }
        assertEquals("99901", device.numeric)
        assertEquals(listOf("identity", "config"), device.events)
    }
}
