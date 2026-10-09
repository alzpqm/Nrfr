package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric
import org.junit.Assert.*
import org.junit.Test

class OperatorNumericTest {
    @Test fun preservesTwoDigitMncLeadingZero() {
        val code = OperatorNumeric.parse("45402")
        assertEquals("454", code.mcc)
        assertEquals("02", code.mnc)
        assertEquals("45402", code.value)
    }
    @Test fun preservesThreeDigitMncLeadingZeros() {
        assertEquals("002", OperatorNumeric.parse("454002").mnc)
        assertNotEquals(OperatorNumeric.parse("45402"), OperatorNumeric.parse("454002"))
    }
    @Test fun supportsSixDigitCodesAndTrimsOnlySurroundingWhitespace() {
        assertEquals("310260", OperatorNumeric.parse(" 310260 ").value)
        assertEquals("10", OperatorNumeric.parse("23410").mnc)
    }
    @Test fun rejectsMalformedNumbersWithoutTruncatingOrStrippingCharacters() {
        for (value in listOf("", "4542", "4540002", "454-02", "454 02", "４５４０２", "٤٥٤٠٢", "4540a", "+45402")) {
            assertFalse(value, OperatorNumeric.isValid(value))
            assertThrows(value, IllegalArgumentException::class.java) { OperatorNumeric.parse(value) }
        }
    }
}
