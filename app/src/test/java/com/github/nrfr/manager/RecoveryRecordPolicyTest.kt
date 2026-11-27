package com.github.nrfr.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryRecordPolicyTest {
    @Test fun durableWriteSurvivesDeathBeforeLocalCommit() {
        assertEquals(mapOf("simA" to "new"), RecoveryRecordPolicy.reconcile(
            mapOf("simA" to "old"), mapOf("simA" to "new"), setOf("simA")))
    }

    @Test fun durableRemovalDoesNotResurrectStaleLocalBackup() {
        assertEquals(emptyMap<String, String>(), RecoveryRecordPolicy.reconcile(
            mapOf("simA" to "restored"), emptyMap(), setOf("simA")))
    }

    @Test fun inactiveSimIsNotOverwrittenOrRemoved() {
        assertEquals(mapOf("simA" to "new", "simB" to "local B"), RecoveryRecordPolicy.reconcile(
            mapOf("simA" to "old", "simB" to "local B"),
            mapOf("simA" to "new", "simB" to "durable B"), setOf("simA")))
    }
}
