package com.github.nrfr.manager

/** The durable write-ahead state is authoritative for each currently present SIM. */
internal object RecoveryRecordPolicy {
    fun reconcile(local: Map<String, String>, durable: Map<String, String>, active: Set<String>): Map<String, String> =
        local.filterKeys { it !in active } + durable.filterKeys { it in active }
}
