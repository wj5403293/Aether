package com.zhousl.aether.runtime

internal object AetherPty {
    init {
        System.loadLibrary("aetherpty")
    }

    fun start(
        command: List<String>,
        environment: List<String>,
        workingDirectory: String,
        rows: Int = 32,
        columns: Int = 96,
    ): PtyHandle {
        val result = nativeStart(
            command.toTypedArray(),
            environment.toTypedArray(),
            workingDirectory,
            rows,
            columns,
        )
        val fd = result.getOrNull(0) ?: -1
        val pid = result.getOrNull(1) ?: -1
        val errorCode = result.getOrNull(2) ?: -1
        if (fd < 0 || pid < 0 || errorCode != 0) {
            error("Unable to start native PTY. errno=$errorCode")
        }
        return PtyHandle(fd = fd, pid = pid)
    }

    fun read(handle: PtyHandle, buffer: ByteArray): Int =
        nativeRead(handle.fd, buffer, 0, buffer.size)

    fun write(handle: PtyHandle, bytes: ByteArray): Int =
        nativeWrite(handle.fd, bytes, 0, bytes.size)

    fun resize(handle: PtyHandle, rows: Int, columns: Int): Int =
        nativeResize(handle.fd, rows, columns)

    fun close(handle: PtyHandle) {
        nativeClose(handle.fd, handle.pid)
    }

    private external fun nativeStart(
        command: Array<String>,
        environment: Array<String>,
        workingDirectory: String,
        rows: Int,
        columns: Int,
    ): IntArray

    private external fun nativeRead(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWrite(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(fd: Int, rows: Int, columns: Int): Int
    private external fun nativeClose(fd: Int, pid: Int): Int
}

internal data class PtyHandle(
    val fd: Int,
    val pid: Int,
)
