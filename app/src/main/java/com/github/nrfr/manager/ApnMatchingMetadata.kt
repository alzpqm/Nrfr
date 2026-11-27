package com.github.nrfr.manager

internal object ApnMatchingMetadata {
    // ICCIDs contain decimal/hex digits. This per-row marker cannot prefix a real ICCID.
    fun marker(simKey: String, id: Long): String {
        require(simKey.matches(Regex("[a-f0-9]{64}")) && id >= 0)
        return "nrfr:${simKey.take(12)}:$id"
    }

    /** A crash can leave a mix of unchanged and updated rows. Never overwrite an external
     * matching edit while undoing only our own metadata. */
    fun restore(type: String?, data: String?, originalType: String?, originalData: String?, marker: String): Boolean {
        if (type == originalType && data == originalData) return false
        check(type == "iccid" && data == marker) { "目標 APN 匹配條件已被其他操作修改，備份已保留" }
        return true
    }

    fun restoredEdited(current: String?, original: String?, contentUnchanged: Boolean): String? =
        if (current in setOf("2", "3", "5", "6") || !contentUnchanged) current else original
}
