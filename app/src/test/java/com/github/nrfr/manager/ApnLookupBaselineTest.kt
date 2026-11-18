package com.github.nrfr.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApnLookupBaselineTest {
    @Test fun sim2ChecksRowsFromItsFixedSamsungLookup() {
        val subscriptionRows = setOf(1L, 2L, 3L)
        val effectiveRows = setOf(4L, 5L, 6L)
        val preferred = setOf(4L)
        assertFalse(ApnLookupBaseline.isConfirmed(subscriptionRows, effectiveRows, effectiveRows, preferred))
        assertTrue(ApnLookupBaseline.isConfirmed(effectiveRows, effectiveRows, effectiveRows, preferred))
        assertFalse(ApnLookupBaseline.isConfirmed(effectiveRows, effectiveRows, setOf(7L), preferred))
        assertFalse(ApnLookupBaseline.isConfirmed(effectiveRows, effectiveRows, effectiveRows, setOf(1L)))
    }
}
