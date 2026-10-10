package com.github.nrfr.ui.screens

import com.github.nrfr.data.PresetCarriers
import org.junit.Assert.*
import org.junit.Test

class SimEditorStateTest {
    private fun simbaDraft() = SimEditorState(subId = 12).copy(
        selectedCountryCode = "SG",
        customCountryCode = "SG",
        isCustomCountryCode = true,
        selectedCarrier = PresetCarriers.presets.first { it.operatorNumeric == "52510" },
        customCarrierName = "SIMBA",
        operatorNumeric = "52510",
        changeNumeric = true,
        operationMessage = "SIM 2 回讀已確認：52510",
    )

    @Test fun switchingFromSimbaToAnotherSimCannotReuseItsDraftOrResult() {
        val otherSim = simbaDraft().selectSim(7)
        assertEquals(7, otherSim.subId)
        assertNull(otherSim.selectedCarrier)
        assertTrue(otherSim.selectedCountryCode.isEmpty())
        assertTrue(otherSim.customCountryCode.isEmpty())
        assertFalse(otherSim.isCustomCountryCode)
        assertTrue(otherSim.customCarrierName.isEmpty())
        assertTrue(otherSim.operatorNumeric.isEmpty())
        assertFalse(otherSim.changeNumeric)
        assertNull(otherSim.operationMessage)
    }

    @Test fun reloadingTheSameSubscriptionKeepsItsPendingEdits() {
        val pending = simbaDraft()
        assertEquals(pending, pending.selectSim(12))
    }

    @Test fun aRemovedSubscriptionCannotLeaveAnApplicableDraft() {
        val removed = simbaDraft().selectSim(null)
        assertNull(removed.subId)
        assertTrue(removed.operatorNumeric.isEmpty())
        assertFalse(removed.changeNumeric)
        assertNull(removed.operationMessage)
    }

    @Test fun switchingBackDoesNotResurrectAnotherSubscriptionsInputs() {
        val returned = simbaDraft().selectSim(7).copy(
            selectedCountryCode = "GB", operatorNumeric = "23410", changeNumeric = true,
        ).selectSim(12)
        assertEquals(12, returned.subId)
        assertTrue(returned.selectedCountryCode.isEmpty())
        assertTrue(returned.operatorNumeric.isEmpty())
        assertFalse(returned.changeNumeric)
    }
}
