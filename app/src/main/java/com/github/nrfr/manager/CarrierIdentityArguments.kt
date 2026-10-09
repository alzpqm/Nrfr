package com.github.nrfr.manager

import com.github.nrfr.model.OperatorNumeric

internal object CarrierIdentityArguments {
    fun build(arity: Int, subId: Int, numeric: String, spn: String?): Array<Any?> {
        require(subId >= 0) { "無效的 SIM 訂閱 ID" }
        require(arity == 8 || arity == 10) { "系統未提供相容的電信業者代碼覆寫介面" }
        // Null preserves real IMSI/ICCID/GID/PNN; empty strings would erase them.
        val args = arrayOf<Any?>(subId, OperatorNumeric.parse(numeric).value, null, null, null, null, null, spn)
        return args.copyOf(arity)
    }
}
