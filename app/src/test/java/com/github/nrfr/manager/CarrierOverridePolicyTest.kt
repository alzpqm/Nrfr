package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class CarrierOverridePolicyTest {
    @Test fun android16And17UseEffectiveTemporaryOverrides() {
        for (sdk in listOf(36, 37)) {
            val calls = mutableListOf<Boolean>()
            assertFalse(CarrierOverridePolicy.apply(sdk, false) { calls.add(it) })
            assertEquals(listOf(false), calls)
        }
    }

    @Test fun olderAndroidKeepsPersistentWrites() {
        val calls = mutableListOf<Boolean>()
        assertTrue(CarrierOverridePolicy.apply(35, false) { calls.add(it) })
        assertEquals(listOf(true), calls)
    }

    @Test fun resetAttemptsToClearPreviouslyPersistedConfiguration() {
        val calls = mutableListOf<Boolean>()
        assertTrue(CarrierOverridePolicy.apply(37, true) { calls.add(it) })
        assertEquals(listOf(true), calls)
    }

    @Test fun knownPersistentRestrictionFallsBackDuringReset() {
        val calls = mutableListOf<Boolean>()
        assertFalse(CarrierOverridePolicy.apply(37, true) {
            calls.add(it)
            if (it) throw SecurityException("persistent=true requires a system app")
        })
        assertEquals(listOf(true, false), calls)
    }

    @Test fun unrelatedPermissionFailureDoesNotRetry() {
        val calls = mutableListOf<Boolean>()
        val denied = SecurityException("MODIFY_PHONE_STATE denied")
        val actual = assertThrows(SecurityException::class.java) {
            CarrierOverridePolicy.apply(35, false) {
                calls.add(it)
                throw denied
            }
        }
        assertSame(denied, actual)
        assertEquals(listOf(true), calls)
    }

    @Test fun failedTemporaryWriteIsReported() {
        val denied = SecurityException("overrideConfig denied")
        assertSame(denied, assertThrows(SecurityException::class.java) {
            CarrierOverridePolicy.apply(37, false) { throw denied }
        })
    }
}
