package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class LegacyApnCleanupCoordinatorTest {
    private val original = "99901"
    private val target = "998002"
    private fun profile(id: Long, numeric: String, name: String) = ApnProfile(id, mapOf(
        "name" to name, "numeric" to numeric, "apn" to "internet.example.invalid",
        "mvno_type" to "iccid", "mvno_match_data" to "8900000000000000001",
        "user" to "test-only-user", "password" to "test-only-password", "nullable_field" to null,
    ))
    private inner class Device : LegacyApnCleanupCoordinator.Backend {
        val rows = linkedMapOf(1L to profile(1, original, "Source 1"),
            2L to profile(2, original, "Source 2"), 100L to profile(100, target, "Alias（Nrfr）"))
        var saved: ApnJournal? = ApnJournal(target, 1L,
            listOf(ApnAlias(1, "Alias（Nrfr）", ApnProfiles.fingerprint(rows.getValue(100)), 100)))
        var selected: Long? = 100
        var denied = false
        var empty = false
        var failVerify = false
        var failDelete = false
        var accessChecks = 0
        val selectionWrites = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        override fun checkAccess() { accessChecks++; check(!denied) }
        override fun journal() = saved
        override fun clearJournal() { saved = null }
        override fun preferred() = selected
        override fun setPreferred(id: Long) { selected = id; selectionWrites += id }
        override fun originalProfiles() = if (empty) emptyList() else rows.values.filter { it.fields["numeric"] == original }
        override fun existingAliases(numeric: String) = rows.values.filter { it.fields["numeric"] == numeric }
        override fun delete(profile: ApnProfile) {
            check(!failDelete)
            check(ApnProfiles.fingerprint(rows.getValue(profile.id)) == ApnProfiles.fingerprint(profile))
            rows.remove(profile.id)
            deleted += profile.id
        }
        override fun verify(numeric: String, preferred: Long?) {
            check(!failVerify && numeric == original)
            check(preferred == null || preferred == selected && originalProfiles().any { it.id == preferred })
        }
        fun cleanup() = LegacyApnCleanupCoordinator(this, original).cleanup()
    }

    @Test fun selectedAliasIsMappedBackBeforeOnlyItsUnmodifiedRowIsDeleted() {
        val device = Device()
        val source = device.rows.getValue(1)
        device.cleanup()
        assertEquals(1L, device.selected)
        assertEquals(listOf(100L), device.deleted)
        assertSame(source, device.rows[1])
        assertNull(device.saved)
    }

    @Test fun aUserSelectedOriginalApnIsNotOverwrittenByTheOldJournalPreference() {
        val device = Device().apply { selected = 2 }
        device.cleanup()
        assertEquals(2L, device.selected)
        assertTrue(device.selectionWrites.isEmpty())
        assertNull(device.saved)
    }

    @Test fun noJournalMeansNoApnPermissionCheckOrSelectionWrite() {
        val device = Device().apply { saved = null; denied = true }
        device.cleanup()
        assertEquals(0, device.accessChecks)
        assertTrue(device.deleted.isEmpty())
        assertTrue(device.selectionWrites.isEmpty())
    }

    @Test fun aJournalThatOverlapsTheOriginalCodeCannotDeleteOriginalApns() {
        val device = Device()
        device.saved = device.saved!!.copy(numeric = original)
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertEquals(0, device.accessChecks)
        assertTrue(device.deleted.isEmpty())
        assertTrue(device.selectionWrites.isEmpty())
        assertNotNull(device.saved)
    }

    @Test fun deniedCleanupPermissionLeavesApnsAndJournalUntouched() {
        val device = Device().apply { denied = true }
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertTrue(device.deleted.isEmpty())
        assertTrue(device.selectionWrites.isEmpty())
        assertNotNull(device.saved)
    }

    @Test fun emptyOriginalApnsNeverClearThePreferredApnOrDeleteAliases() {
        val device = Device().apply { empty = true }
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertEquals(100L, device.selected)
        assertTrue(device.selectionWrites.isEmpty())
        assertTrue(device.deleted.isEmpty())
        assertNotNull(device.saved)
    }

    @Test fun externalCredentialEditsAreProtectedAndKeepTheRecoveryJournal() {
        val device = Device()
        device.rows[100] = ApnProfile(100, device.rows.getValue(100).fields + ("password" to "user-edited-test-value"))
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertTrue(device.deleted.isEmpty())
        assertNotNull(device.saved)
    }

    @Test fun renamedOrReusedAliasRowIdsAreNotDeleted() {
        val device = Device()
        device.rows[100] = profile(100, target, "Unrelated APN")
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertEquals("Unrelated APN", device.rows.getValue(100).fields["name"])
        assertNotNull(device.saved)
    }

    @Test fun pendingInsertIsRecoveredOnlyWithTheOriginalFullPayloadHash() {
        val device = Device()
        device.saved = device.saved!!.copy(aliases = device.saved!!.aliases.map { it.copy(id = null) })
        device.cleanup()
        assertEquals(listOf(100L), device.deleted)
        assertNull(device.saved)
    }

    @Test fun aChangedPendingInsertKeepsTheJournalInsteadOfDeletingAnUnknownRow() {
        val device = Device()
        device.saved = device.saved!!.copy(aliases = device.saved!!.aliases.map { it.copy(id = null) })
        device.rows[100] = ApnProfile(100, device.rows.getValue(100).fields + ("apn" to "changed.example.invalid"))
        assertThrows(IllegalStateException::class.java) { device.cleanup() }
        assertTrue(device.deleted.isEmpty())
        assertNotNull(device.saved)
    }

    @Test fun missingAliasRowsDoNotDeleteOtherProfiles() {
        val device = Device().apply { rows.remove(100); selected = 1 }
        device.cleanup()
        assertEquals(setOf(1L, 2L), device.rows.keys)
        assertTrue(device.deleted.isEmpty())
        assertNull(device.saved)
    }

    @Test fun providerReadbackOrDeleteFailureKeepsTheJournalForRetry() {
        for (failure in listOf("verify", "delete")) {
            val device = Device().apply { failVerify = failure == "verify"; failDelete = failure == "delete" }
            assertThrows(IllegalStateException::class.java) { device.cleanup() }
            assertNotNull(device.saved)
            assertTrue(device.rows.containsKey(100))
        }
    }

    @Test fun fingerprintCompatibilityIgnoresOnlyThePreviousVolatileColumns() {
        val source = profile(100, target, "Alias（Nrfr）")
        val changed = ApnProfile(100, source.fields + mapOf("_id" to "100", "edited" to "1", "current" to "1"))
        assertEquals(ApnProfiles.fingerprint(source), ApnProfiles.fingerprint(changed))
        val withoutNull = ApnProfile(100, source.fields.filterKeys { it != "nullable_field" })
        assertNotEquals(ApnProfiles.fingerprint(source), ApnProfiles.fingerprint(withoutNull))
    }
}
