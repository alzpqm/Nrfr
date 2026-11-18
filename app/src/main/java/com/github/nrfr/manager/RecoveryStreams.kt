package com.github.nrfr.manager

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readRecoveryBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= limit) { "Recovery stream too large" }
        output.write(buffer, 0, count)
    }
}
