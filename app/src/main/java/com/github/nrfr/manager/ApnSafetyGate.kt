package com.github.nrfr.manager

/** APN provider failures are errors, never evidence of an empty APN list. */
internal object ApnSafetyGate {
    fun preflight(currentVisible: Int, targetRaw: Int) {
        check(currentVisible > 0) { "目前 SIM 的 APN 清單無法確認；代碼尚未修改" }
        check(targetRaw > 0) { "目標代碼沒有可用的原始 APN；代碼尚未修改，避免 APN 清單消失" }
    }

    fun afterApply(visible: Int, filtered: Int) {
        check(visible > 0 && filtered > 0) { "修改後 APN 清單消失" }
    }
}
