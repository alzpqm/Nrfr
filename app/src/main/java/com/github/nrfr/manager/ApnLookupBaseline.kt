package com.github.nrfr.manager

internal object ApnLookupBaseline {
    fun isConfirmed(lookupRows: Set<Long>, filtered: Set<Long>, settings: Set<Long>, preferred: Set<Long>): Boolean {
        return filtered.isNotEmpty() && settings.isNotEmpty() && preferred.size == 1 &&
            lookupRows.containsAll(filtered) && lookupRows.containsAll(settings) && filtered.containsAll(preferred)
    }
}
