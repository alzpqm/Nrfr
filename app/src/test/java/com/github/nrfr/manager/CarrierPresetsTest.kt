package com.github.nrfr.manager

import com.github.nrfr.data.CountryPresets
import com.github.nrfr.data.PresetCarriers
import com.github.nrfr.model.OperatorNumeric
import org.junit.Assert.*
import org.junit.Test

class CarrierPresetsTest {
    @Test fun everyPresetHasAValidUnambiguousCodeAndSelectableRegion() {
        val presets = PresetCarriers.presets.filter { it.region.isNotEmpty() }
        val regions = CountryPresets.countries.map { it.code }.toSet()
        assertEquals(presets.size, presets.map { it.operatorNumeric }.toSet().size)
        for (preset in presets) {
            assertTrue(preset.name, OperatorNumeric.isValid(preset.operatorNumeric))
            assertTrue(preset.name, preset.region in regions)
            assertTrue(preset.name, preset.displayName.isNotBlank())
        }
    }
    @Test fun requestedExamplesAndTaiwanCarriersHaveTheCorrectRegion() {
        val byCode = PresetCarriers.presets.associateBy { it.operatorNumeric }
        assertEquals("HK", byCode.getValue("45402").region)
        assertEquals("GB", byCode.getValue("23410").region)
        for (code in listOf("46692", "46697", "46601")) assertEquals("TW", byCode.getValue(code).region)
        assertEquals("US", byCode.getValue("310260").region)
    }
}
