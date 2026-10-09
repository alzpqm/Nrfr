package com.github.nrfr.manager

internal object CarrierOverridePolicy {
    // Android 16+ may reject or silently ignore persistent overrides for ordinary apps.
    // Reset still tries to remove any persistent override created on an older release.
    fun apply(sdk: Int, reset: Boolean, write: (persistent: Boolean) -> Unit): Boolean {
        if (sdk >= 36 && !reset) {
            write(false)
            return false
        }
        try {
            write(true)
            return true
        } catch (error: SecurityException) {
            val message = error.message.orEmpty().lowercase()
            if (!message.contains("persistent=true") && !message.contains("system app") &&
                !message.contains("persistent override")) {
                throw error
            }
            write(false)
            return false
        }
    }
}
