package com.github.nrfr.model

/** Keep MCC/MNC as strings: 02 and 002 are different codes. */
data class OperatorNumeric private constructor(val mcc: String, val mnc: String) {
    val value: String get() = mcc + mnc
    companion object {
        fun isValid(value: String) = value.matches(Regex("[0-9]{5,6}"))
        fun parse(value: String): OperatorNumeric {
            val code = value.trim()
            require(isValid(code)) { "電信業者代碼須為 5 或 6 位半形數字（MCC 3 位＋MNC 2 或 3 位）" }
            return OperatorNumeric(code.take(3), code.drop(3))
        }
    }
}
