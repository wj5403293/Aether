package com.zhousl.aether.data

import android.content.Context
import android.net.Uri
import com.zhousl.aether.runtime.AlpineRuntime
import com.zhousl.aether.runtime.RuntimeRouter
import com.zhousl.aether.termux.TermuxContract
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RuntimeWorkspaceFileBridge(
    context: Context,
    private val runtimeRouter: RuntimeRouter,
    private val alpineRuntime: AlpineRuntime,
    private val termuxFileBridge: WorkspaceFileBridge,
) {
    private val appContext = context.applicationContext

    suspend fun readWorkspaceFile(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        path: String,
        workingDirectory: String = "",
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> {
        val normalizedPath = normalizeRuntimeWorkspacePath(path)
        val runtimeId = resolveWorkspaceRuntimeId(
            path = normalizedPath,
            workingDirectory = workingDirectory,
            defaultRuntimeId = runtimeRouter.runtimeFor(settings, null)?.id ?: LocalRuntimeId.Termux,
        )
        val resolvedWorkingDirectory = workingDirectory.ifBlank {
            when (runtimeId) {
                LocalRuntimeId.Alpine -> workspaceDirectory
                LocalRuntimeId.Termux -> termuxWorkspaceDirectory
            }
        }
        return readWorkspaceFile(
            runtimeId = runtimeId,
            path = normalizedPath,
            workingDirectory = resolvedWorkingDirectory,
            byteLimit = byteLimit,
        )
    }

    suspend fun readWorkspaceFile(
        path: String,
        workingDirectory: String,
        defaultRuntimeId: LocalRuntimeId,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> {
        val normalizedPath = normalizeRuntimeWorkspacePath(path)
        val runtimeId = resolveWorkspaceRuntimeId(
            path = normalizedPath,
            workingDirectory = workingDirectory,
            defaultRuntimeId = defaultRuntimeId,
        )
        val resolvedWorkingDirectory = workingDirectory.ifBlank {
            when (runtimeId) {
                LocalRuntimeId.Alpine -> alpineRuntime.workspaceRoot
                LocalRuntimeId.Termux -> TermuxContract.HomeDirectory
            }
        }
        return readWorkspaceFile(
            runtimeId = runtimeId,
            path = normalizedPath,
            workingDirectory = resolvedWorkingDirectory,
            byteLimit = byteLimit,
        )
    }

    private suspend fun readWorkspaceFile(
        runtimeId: LocalRuntimeId,
        path: String,
        workingDirectory: String,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> {
        return when (runtimeId) {
            LocalRuntimeId.Alpine -> readAlpineWorkspaceFile(
                path = path,
                workingDirectory = workingDirectory,
                byteLimit = byteLimit,
            )

            LocalRuntimeId.Termux -> termuxFileBridge.readWorkspaceFile(
                path = path,
                workingDirectory = workingDirectory,
                byteLimit = byteLimit,
            )
        }
    }

    suspend fun writeWorkspaceBytes(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        absolutePath: String,
        bytes: ByteArray,
    ): Result<Long> {
        val normalizedPath = normalizeRuntimeWorkspacePath(absolutePath)
        val runtimeId = resolveWorkspaceRuntimeId(
            path = normalizedPath,
            workingDirectory = workspaceDirectory,
            defaultRuntimeId = runtimeRouter.runtimeFor(settings, null)?.id ?: LocalRuntimeId.Termux,
        )
        return when (runtimeId) {
            LocalRuntimeId.Alpine -> writeAlpineWorkspaceBytes(
                path = normalizedPath,
                workingDirectory = workspaceDirectory,
                bytes = bytes,
            )

            LocalRuntimeId.Termux -> termuxFileBridge.writeWorkspaceBytes(
                absolutePath = normalizedPath,
                bytes = bytes,
            )
        }
    }

    suspend fun saveWorkspaceFileToDocument(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        path: String,
        destinationUri: Uri,
        byteLimit: Int = 256 * 1024 * 1024,
    ): Boolean {
        val normalizedPath = normalizeRuntimeWorkspacePath(path)
        val runtimeId = resolveWorkspaceRuntimeId(
            path = normalizedPath,
            workingDirectory = workspaceDirectory,
            defaultRuntimeId = runtimeRouter.runtimeFor(settings, null)?.id ?: LocalRuntimeId.Termux,
        )
        return when (runtimeId) {
            LocalRuntimeId.Alpine -> saveAlpineWorkspaceFileToDocument(
                path = normalizedPath,
                workingDirectory = workspaceDirectory,
                destinationUri = destinationUri,
                byteLimit = byteLimit,
            )

            LocalRuntimeId.Termux -> termuxFileBridge.saveWorkspaceFileToDocument(
                path = termuxFileBridge.resolveTermuxPath(
                    path = normalizedPath,
                    workingDirectory = termuxWorkspaceDirectory,
                ),
                destinationUri = destinationUri,
                byteLimit = byteLimit,
            )
        }
    }

    fun guessMimeType(path: String): String = termuxFileBridge.guessMimeType(path)

    private suspend fun readAlpineWorkspaceFile(
        path: String,
        workingDirectory: String,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> = withContext(Dispatchers.IO) {
        runCatching {
            require(byteLimit > 0) { "byteLimit must be greater than 0." }
            val resolved = alpineRuntime.resolveWorkspaceHostPath(path, workingDirectory)
                ?: error("Path is outside the Alpine workspace: $path")
            val file = resolved.hostFile
            require(file.isFile) { "File was not found in the Alpine workspace: ${resolved.guestPath}" }
            val sizeBytes = file.length()
            require(sizeBytes <= byteLimit) {
                "File is larger than $byteLimit bytes: ${resolved.guestPath}"
            }
            val bytes = file.readBytes()
            require(bytes.size <= byteLimit) {
                "File is larger than $byteLimit bytes: ${resolved.guestPath}"
            }
            WorkspaceFilePayload(
                absolutePath = resolved.guestPath,
                bytes = bytes,
                sizeBytes = bytes.size.toLong(),
            )
        }
    }

    private suspend fun writeAlpineWorkspaceBytes(
        path: String,
        workingDirectory: String,
        bytes: ByteArray,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = alpineRuntime.resolveWorkspaceHostPath(path, workingDirectory)
                ?: error("Path is outside the Alpine workspace: $path")
            resolved.hostFile.parentFile?.mkdirs()
            resolved.hostFile.outputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
            bytes.size.toLong()
        }
    }

    private suspend fun saveAlpineWorkspaceFileToDocument(
        path: String,
        workingDirectory: String,
        destinationUri: Uri,
        byteLimit: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = alpineRuntime.resolveWorkspaceHostPath(path, workingDirectory)
                ?: error("Path is outside the Alpine workspace: $path")
            val source = resolved.hostFile
            require(source.isFile) { "File was not found in the Alpine workspace: ${resolved.guestPath}" }
            require(source.length() <= byteLimit) {
                "File is larger than $byteLimit bytes: ${resolved.guestPath}"
            }
            val resolver = source.takeIf { it.canRead() }
                ?: error("File is not readable: ${resolved.guestPath}")
            val didWrite = appContext.contentResolver.openOutputStream(destinationUri, "w")
                ?.use { output ->
                    resolver.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    true
                } ?: false
            if (!didWrite) {
                runCatching { appContext.contentResolver.delete(destinationUri, null, null) }
            }
            didWrite
        }.getOrDefault(false)
    }
}

internal fun resolveWorkspaceRuntimeId(
    path: String,
    workingDirectory: String,
    defaultRuntimeId: LocalRuntimeId,
): LocalRuntimeId {
    val normalizedPath = normalizeRuntimeWorkspacePath(path)
    val normalizedWorkingDirectory = normalizeRuntimeWorkspacePath(workingDirectory)
    return when {
        isAlpineWorkspacePath(normalizedPath) -> LocalRuntimeId.Alpine
        isTermuxWorkspacePath(normalizedPath) -> LocalRuntimeId.Termux
        isAlpineWorkspacePath(normalizedWorkingDirectory) -> LocalRuntimeId.Alpine
        isTermuxWorkspacePath(normalizedWorkingDirectory) -> LocalRuntimeId.Termux
        else -> defaultRuntimeId
    }
}

private fun normalizeRuntimeWorkspacePath(path: String): String {
    val trimmed = path.trim()
    if (!trimmed.startsWith("file://", ignoreCase = true)) return trimmed
    val withoutScheme = trimmed.substring("file://".length)
    val withoutLocalhost = when {
        withoutScheme.startsWith("localhost/") -> "/" + withoutScheme.removePrefix("localhost/")
        withoutScheme == "localhost" -> "/"
        else -> withoutScheme
    }
    return URLDecoder.decode(withoutLocalhost, Charsets.UTF_8.name()).trim()
}

private fun isAlpineWorkspacePath(path: String): Boolean =
    path == "/workspace" || path.startsWith("/workspace/")

private fun isTermuxWorkspacePath(path: String): Boolean =
    path == "~" ||
        path.startsWith("~/") ||
        path == TermuxContract.HomeDirectory ||
        path.startsWith("${TermuxContract.HomeDirectory}/")
