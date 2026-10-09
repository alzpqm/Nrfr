package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class CarrierIdentityArgumentsTest {
    @Test fun modernCallPreservesRealIdentifiersAndDoesNotInjectCarrierPrivileges() {
        val args = CarrierIdentityArguments.build(10, 7, "45402", "csl")
        assertEquals(10, args.size)
        assertEquals(7, args[0])
        assertEquals("45402", args[1])
        for (index in 2..6) assertNull(args[index])
        assertEquals("csl", args[7])
        assertNull(args[8])
        assertNull(args[9])
    }
    @Test fun legacyCallPreservesThreeDigitMnc() {
        val args = CarrierIdentityArguments.build(8, 3, "310260", null)
        assertEquals(8, args.size)
        assertEquals("310260", args[1])
        assertNull(args[7])
    }
    @Test fun unsupportedBinderShapeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CarrierIdentityArguments.build(9, 3, "23410", null) }
    }
    @Test fun invalidSubscriptionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CarrierIdentityArguments.build(10, -1, "23410", null) }
    }
}
