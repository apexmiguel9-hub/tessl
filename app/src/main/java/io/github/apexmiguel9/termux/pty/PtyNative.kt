package io.github.apexmiguel9.termux.pty

/**
 * Raw JNI surface over native/pty. Every method here has a matching
 * `Java_io_github_apexmiguel9_termux_pty_PtyNative_*` entry point in
 * app/src/main/cpp/pty_jni.c.
 *
 * None of these block except [nativeWait]; the read loop lives in Kotlin so
 * that thread attachment happens in exactly one place.
 */
internal object PtyNative {

    init {
        System.loadLibrary("tesslpty")
    }

    /** @return an opaque handle, or throws if the spawn failed. */
    external fun nativeSpawn(
        argv: Array<String>,
        envp: Array<String>?,
        rows: Int,
        cols: Int,
    ): Long

    /** @return available bytes, an empty array on EOF, null on timeout. */
    external fun nativeRead(handle: Long, timeoutMs: Int): ByteArray?

    external fun nativeWrite(handle: Long, data: ByteArray): Int

    external fun nativeResize(handle: Long, rows: Int, cols: Int)

    /** Blocks until the child exits. @return the exit code. */
    external fun nativeWait(handle: Long): Int

    external fun nativePid(handle: Long): Int

    /** errno from the child if exec failed, else 0. */
    external fun nativeExecErrno(handle: Long): Int

    external fun nativeAlive(handle: Long): Boolean

    external fun nativeKill(handle: Long)

    external fun nativeClose(handle: Long)
}
