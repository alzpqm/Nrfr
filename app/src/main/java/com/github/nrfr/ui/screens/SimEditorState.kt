package com.github.nrfr.ui.screens

import com.github.nrfr.data.PresetCarriers

/** A draft and its result belong to one selected subscription only. */
data class SimEditorState(
    val subId: Int? = null,
    val selectedCountryCode: String = "",
    val customCountryCode: String = "",
    val isCustomCountryCode: Boolean = false,
    val selectedCarrier: PresetCarriers.CarrierPreset? = null,
    val customCarrierName: String = "",
    val operatorNumeric: String = "",
    val changeNumeric: Boolean = false,
    val operationMessage: String? = null,
) {
    fun selectSim(newSubId: Int?): SimEditorState =
        if (newSubId == subId) this else SimEditorState(subId = newSubId)
}
