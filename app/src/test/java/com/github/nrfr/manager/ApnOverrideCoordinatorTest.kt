package com.github.nrfr.manager

import org.junit.Assert.*
import org.junit.Test

class ApnOverrideCoordinatorTest {
    // Synthetic test identities/APNs only, unrelated to any user's SIM or credentials.
    private val original = "99901"
    private val target = "999002"
    private val sim = "8900000000000000001"
    private val otherSim = "8900000000000000002"
    private fun source(id: Long, apn: String = "internet.example.invalid", type: String = "default") = ApnProfile(id, mapOf(
        "name" to "Test APN", "numeric" to original, "mcc" to "999", "mnc" to "01", "apn" to apn,
        "type" to type, "protocol" to "IPV4V6", "roaming_protocol" to "IPV4V6", "carrier_enabled" to "1",
        "mvno_type" to "spn", "mvno_match_data" to "Test SIM", "carrier_id" to "100",
        "user" to "test-only-user", "password" to "test-only-password", "mmsc" to "https://mms.example.invalid",
        "mmsproxy" to "192.0.2.1", "mmsport" to "80", "authtype" to "3", "mtu_v4" to "1400",
        "owned_by" to "0", "edited" to "0", "user_visible" to "1", "profile_id" to "0", "sub_id" to "7",
        "vendor_extension" to "preserve-me", "nullable_extension" to null,
    ))

    private inner class FakeBackend : ApnOverrideCoordinator.Backend {
        val rows = linkedMapOf(1L to source(1), 2L to source(2, "mms.example.invalid", "mms,default"))
        var numeric = original
        var selected: Long? = 1
        var saved: ApnJournal? = null
        var denied = false
        var failInsert: Int? = null
        var inserts = 0
        var identityWrites = 0
        var rejectTargetData = false
        var loseSelectionOnIdentityChange = false
        var normalizePayload = false
        var failJournalSaveAt: Int? = null
        var saves = 0
        val selectionWrites = mutableListOf<Long?>()
        private var nextId = 100L

        fun matching(code: String = numeric, identity: String = sim): List<ApnProfile> = rows.values.filter {
            it.fields["numeric"] == code && when (it.fields["mvno_type"]) {
                "iccid" -> identity.startsWith(it.fields["mvno_match_data"].orEmpty())
                else -> code == original
            }
        }
        override fun checkAccess() { check(!denied) { "no permission" } }
        override fun journal() = saved
        override fun save(journal: ApnJournal?) {
            saves++
            check(saves != failJournalSaveAt) { "save failed" }
            saved = journal
        }
        override fun preferred() = selected
        override fun setPreferred(id: Long?) { selected = id; selectionWrites += id }
        override fun restoreIdentity() {
            numeric = original
            identityWrites++
            if (loseSelectionOnIdentityChange) selected = null
        }
        override fun setIdentity(numeric: String) {
            this.numeric = numeric
            identityWrites++
            if (loseSelectionOnIdentityChange) selected = null
        }
        override fun originalProfiles() = matching()
        override fun assertSimBinding() {}
        override fun existingAliases(numeric: String) = rows.values.filter {
            it.fields["numeric"] == numeric && it.fields["mvno_type"] == "iccid" && it.fields["mvno_match_data"] == sim
        }
        override fun insert(profile: ApnProfile): ApnProfile {
            inserts++
            check(inserts != failInsert) { "insert failed" }
            val fields = if (normalizePayload) profile.fields + ("password" to "unwanted-normalization") else profile.fields
            return ApnProfile(nextId++, fields).also { rows[it.id] = it }
        }
        override fun delete(profile: ApnProfile) { check(rows.remove(profile.id) != null) }
        override fun verify(numeric: String, requiredIds: Set<Long>, preferred: Long?) {
            check(this.numeric == numeric)
            val available = matching().map { it.id }.toSet()
            check(available.isNotEmpty() && available.containsAll(requiredIds))
            check(!rejectTargetData || numeric == original) { "data provider rejected aliases" }
            check(preferred == null || (selected == preferred && preferred in available))
        }
    }
    private fun coordinator(backend: FakeBackend) = ApnOverrideCoordinator(backend, original, sim, 7)

    @Test fun changingNumericWithoutApnPreservationReproducesAnEmptyList() {
        val backend = FakeBackend()
        assertEquals(2, backend.matching().size)
        backend.numeric = target
        assertTrue(backend.matching().isEmpty())
    }

    @Test fun applyingNumericKeepsOriginalRowsAndAllDataMmsAndVendorParameters() {
        val backend = FakeBackend()
        val originals = backend.rows.toMap()
        coordinator(backend).apply(target) {}
        assertEquals(target, backend.numeric)
        assertEquals(2, backend.matching().size)
        assertSame(originals[1], backend.rows[1])
        assertSame(originals[2], backend.rows[2])
        val alias = backend.matching().first()
        for (key in listOf("apn", "user", "password", "mmsc", "mmsproxy", "mmsport", "authtype", "protocol",
            "roaming_protocol", "mtu_v4", "vendor_extension", "nullable_extension")) {
            assertEquals(key, originals.getValue(1).fields[key], alias.fields[key])
        }
        assertEquals("002", alias.fields["mnc"])
        assertEquals(target, alias.fields["numeric"])
        assertEquals("iccid", alias.fields["mvno_type"])
        assertEquals(sim, alias.fields["mvno_match_data"])
        assertEquals("-1", alias.fields["carrier_id"])
    }

    @Test fun aliasesAreExcludedFromAnotherSimEvenIfBothUseTheSameNumeric() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        assertEquals(2, backend.matching(target, sim).size)
        assertTrue(backend.matching(target, otherSim).isEmpty())
    }

    @Test fun selectionSurvivesIdentityReloadAndMapsToTheCorrectAlias() {
        val backend = FakeBackend().apply { selected = 2; loseSelectionOnIdentityChange = true }
        coordinator(backend).apply(target) {}
        val alias = backend.saved!!.aliases.first { it.sourceId == 2L }
        assertEquals(alias.id, backend.selected)
    }

    @Test fun noPreviousSelectionDoesNotForceAnArbitraryDefault() {
        val backend = FakeBackend().apply { selected = null }
        coordinator(backend).apply(target) {}
        assertNull(backend.saved!!.preferredSourceId)
        assertNull(backend.selected)
    }

    @Test fun restoringMapsAUserChangedAliasSelectionBackToItsOriginal() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        backend.selected = backend.saved!!.aliases.first { it.sourceId == 2L }.id
        assertEquals(2L, coordinator(backend).restore())
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertNull(backend.saved)
    }

    @Test fun changingNumericAgainUsesTheOriginalApnsAndKeepsCurrentSelection() {
        val backend = FakeBackend()
        val flow = coordinator(backend)
        flow.apply(target) {}
        backend.selected = backend.saved!!.aliases.first { it.sourceId == 2L }.id
        flow.apply("99803") {}
        assertEquals("99803", backend.numeric)
        assertEquals(4, backend.rows.size)
        assertEquals(2L, backend.saved!!.preferredSourceId)
        assertEquals(backend.saved!!.aliases.first { it.sourceId == 2L }.id, backend.selected)
        assertTrue(backend.matching().none { it.fields["name"]!!.count { c -> c == '（' } > 1 })
    }

    @Test fun legacyOverrideWithAnEmptyApnListIsRecoveredBeforeApplying() {
        val backend = FakeBackend().apply { numeric = "99803" }
        assertTrue(backend.matching().isEmpty())
        coordinator(backend).apply(target) {}
        assertEquals(2, backend.matching().size)
        assertEquals(target, backend.numeric)
    }

    @Test fun deniedPermissionDoesNotChangeIdentityApnsOrSelection() {
        val backend = FakeBackend().apply { denied = true }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(0, backend.identityWrites)
        assertEquals(2, backend.rows.size)
        assertEquals(1L, backend.selected)
        assertNull(backend.saved)
    }

    @Test fun partialInsertFailureRollsBackBeforeReportingAnError() {
        val backend = FakeBackend().apply { failInsert = 2 }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertEquals(1L, backend.selected)
        assertNull(backend.saved)
    }

    @Test fun interruptionAfterInsertBeforeJournalCheckpointCanRecoverThePendingRow() {
        val backend = FakeBackend().apply { failJournalSaveAt = 2 }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertNull(backend.saved)
        assertEquals(original, backend.numeric)
    }

    @Test fun carrierConfigFailureRevertsIdentityAndApns() {
        val backend = FakeBackend()
        assertThrows(IllegalStateException::class.java) {
            coordinator(backend).apply(target) { error("config verification failed") }
        }
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertEquals(1L, backend.selected)
        assertNull(backend.saved)
    }

    @Test fun aVisibleListWithoutDataProviderSupportIsNotReportedAsSuccessful() {
        val backend = FakeBackend().apply { rejectTargetData = true }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
    }

    @Test fun unexpectedProviderNormalizationIsRejectedAndTheCreatedRowsAreRemoved() {
        val backend = FakeBackend().apply { normalizePayload = true }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertEquals("test-only-password", backend.rows.getValue(1).fields["password"])
    }

    @Test fun externalEditsToAnAliasAreProtectedAndTheRecoveryJournalIsKept() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        val id = backend.saved!!.aliases.first().id!!
        backend.rows[id] = ApnProfile(id, backend.rows.getValue(id).fields + ("apn" to "user-edited.example.invalid"))
        assertThrows(IllegalStateException::class.java) { coordinator(backend).restore() }
        assertEquals(original, backend.numeric)
        assertEquals("user-edited.example.invalid", backend.rows.getValue(id).fields["apn"])
        assertEquals(1L, backend.selected)
        assertNotNull(backend.saved)
    }

    @Test fun reusedRowIdsDoNotDeleteUnrelatedApns() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        val id = backend.saved!!.aliases.first().id!!
        backend.rows[id] = ApnProfile(id, backend.rows.getValue(id).fields + ("name" to "Unrelated APN"))
        coordinator(backend).restore()
        assertEquals("Unrelated APN", backend.rows.getValue(id).fields["name"])
        assertNull(backend.saved)
    }

    @Test fun incompatibleAliasesAreRejectedBeforeMergingAnyExistingRows() {
        val backend = FakeBackend()
        backend.rows[2] = source(2, type = "mms")
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(0, backend.inserts)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertEquals(original, backend.numeric)
    }

    @Test fun existingExternalIccidBindingsAreNotOverwrittenOrDeleted() {
        val backend = FakeBackend()
        backend.rows[50] = ApnProfiles.alias(source(50), target, sim, 7, "Other tool")
        // FakeBackend indexes rows by ID, as a real provider does.
        backend.rows[50] = ApnProfile(50, backend.rows.getValue(50).fields)
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(target) {} }
        assertEquals(0, backend.inserts)
        assertEquals("Other tool", backend.rows.getValue(50).fields["name"])
    }

    @Test fun recoveryDoesNotSelectDeletedOriginalRowIds() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        backend.rows.remove(1)
        backend.selectionWrites.clear()
        coordinator(backend).restore()
        assertFalse(backend.selectionWrites.contains(1L))
        assertNull(backend.saved)
    }

    @Test fun choosingTheOriginalNumericCleansAliasesWithoutCreatingNewOnes() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        coordinator(backend).apply(original) {}
        assertEquals(original, backend.numeric)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
        assertNull(backend.saved)
    }

    @Test fun durableMetadataDoesNotContainApnCredentialsOrRawSimIdentifiers() {
        val backend = FakeBackend()
        coordinator(backend).apply(target) {}
        val metadata = backend.saved.toString()
        for (secret in listOf(sim, "test-only-user", "test-only-password", "internet.example.invalid")) {
            assertFalse(secret, metadata.contains(secret))
        }
    }

    @Test fun unknownOrListBasedIccidsCannotCreateBroadMatchingApns() {
        for (identity in listOf("", "123", "$sim,$otherSim")) {
            assertThrows(IllegalArgumentException::class.java) { ApnProfiles.alias(source(1), target, identity, 7, "Alias") }
        }
    }

    @Test fun restoringLegacySettingsKeepsSelectionEvenWhenIdentityReloadClearsIt() {
        val backend = FakeBackend().apply { selected = 2; numeric = "99803"; loseSelectionOnIdentityChange = true }
        assertEquals(2L, coordinator(backend).restore())
        assertEquals(2L, backend.selected)
        assertEquals(setOf(1L, 2L), backend.rows.keys)
    }

    @Test fun failedOriginalNumericApplyDoesNotLoseThePreviousSelection() {
        val backend = FakeBackend().apply { selected = 2; loseSelectionOnIdentityChange = true }
        assertThrows(IllegalStateException::class.java) { coordinator(backend).apply(original) { error("config failed") } }
        assertEquals(original, backend.numeric)
        assertEquals(2L, backend.selected)
    }
}
