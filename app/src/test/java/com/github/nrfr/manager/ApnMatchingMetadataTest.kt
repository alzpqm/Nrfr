package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class ApnMatchingMetadataTest {
    @Test fun matchingMarkersCannotMatchIccidsAndDoNotMergeTwoExistingProfiles() {
        val first = ApnMatchingMetadata.marker("a".repeat(64), 10)
        val second = ApnMatchingMetadata.marker("a".repeat(64), 11)
        assertNotEquals(first, second)
        assertNotEquals(first, ApnMatchingMetadata.marker("b".repeat(64), 10))
        assertFalse("8988000000000000000".startsWith(first))
    }

    @Test fun partialPreparationRestoresOnlyWrittenRowsAndPreservesNulls() {
        val marker = ApnMatchingMetadata.marker("a".repeat(64), 10)
        assertFalse(ApnMatchingMetadata.restore(null, null, null, null, marker))
        assertFalse(ApnMatchingMetadata.restore("imsi", "99901x", "imsi", "99901x", marker))
        assertTrue(ApnMatchingMetadata.restore("iccid", marker, null, null, marker))
        assertTrue(ApnMatchingMetadata.restore("iccid", marker, "imsi", "99901x", marker))
    }

    @Test fun externalMatchingEditsCannotBeOverwrittenDuringRestore() {
        val marker = ApnMatchingMetadata.marker("a".repeat(64), 10)
        assertThrows(IllegalStateException::class.java) {
            ApnMatchingMetadata.restore("spn", "changed", "imsi", "99901x", marker)
        }
        assertThrows(IllegalStateException::class.java) {
            ApnMatchingMetadata.restore("iccid", "different-row-marker", "imsi", "99901x", marker)
        }
    }

    @Test fun restoringUnchangedMetadataCannotResurrectSoftDeletedApns() {
        for (deleted in listOf("2", "3", "5", "6")) {
            assertEquals(deleted, ApnMatchingMetadata.restoredEdited(deleted, "0", true))
        }
        assertEquals("1", ApnMatchingMetadata.restoredEdited("1", "0", false))
        assertEquals("0", ApnMatchingMetadata.restoredEdited("1", "0", true))
    }
}
