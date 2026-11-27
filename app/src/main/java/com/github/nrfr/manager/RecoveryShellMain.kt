package com.github.nrfr.manager

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.annotation.Keep
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** Fixed file transport executed by Nightzuku as shell. No commands, APNs or phone APIs. */
@Keep
object RecoveryShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 2 && args[0].matches(Regex("[a-f0-9]{64}")))
            val bytes = if (args[1] == "write") FileInputStream(FileDescriptor.`in`).readBytesLimited() else byteArrayOf()
            FileOutputStream(FileDescriptor.out).apply { write(exchange(args[0], args[1], bytes)); flush() }
        } catch (failure: Throwable) {
            FileOutputStream(FileDescriptor.err).apply {
                write("Recovery storage: ${failure.javaClass.simpleName}\n".toByteArray()); flush()
            }
            kotlin.system.exitProcess(1)
        }
    }

    @Synchronized
    fun exchange(scope: String, operation: String, bytes: ByteArray): ByteArray {
            check(Process.myUid() == 2000) { "Shell identity required" }
            require(scope.matches(Regex("[a-f0-9]{64}")))
            require(operation in setOf("read", "write", "delete"))
            require(bytes.size <= 262144)
            val directory = File("/data/local/tmp", "nrfr-recovery-$scope")
            check(directory.canonicalPath == directory.absolutePath) { "Unexpected recovery path" }
            if (!directory.exists()) {
                if (operation != "write") return byteArrayOf()
                check(directory.mkdir())
            }
            check(directory.isDirectory)
            Os.chmod(directory.path, 448) // 0700: shell only.
            val file = File(directory, "state.json")
            check(file.canonicalPath == file.absolutePath)
            val atomic = AtomicFile(file)
            return when (operation) {
                "read" -> if (file.exists()) {
                    check(file.length() <= 262144)
                    atomic.readFully()
                } else byteArrayOf()
                "write" -> {
                    val stream = atomic.startWrite()
                    try {
                        Os.fchmod(stream.fd, 384) // 0600, including AtomicFile's pending file.
                        stream.write(bytes)
                        stream.fd.sync()
                        atomic.finishWrite(stream)
                    } catch (failure: Throwable) {
                        atomic.failWrite(stream)
                        throw failure
                    }
                    syncDirectory(directory)
                    atomic.readFully()
                }
                "delete" -> {
                    atomic.delete()
                    syncDirectory(directory)
                    byteArrayOf()
                }
                else -> error("Unsupported operation")
            }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        return readRecoveryBytes(262144)
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
