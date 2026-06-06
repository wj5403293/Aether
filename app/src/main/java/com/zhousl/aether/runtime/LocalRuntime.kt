package com.zhousl.aether.runtime

import com.zhousl.aether.data.LocalRuntimeId

enum class LocalRuntimeIssue {
    Ready,
    NotConfigured,
    NotInstalled,
    PermissionMissing,
    ExternalAppsDisabled,
    UnsupportedAbi,
    MissingAssets,
    DispatchFailed,
    Failed,
}

data class LocalRuntimeSetupState(
    val runtimeId: LocalRuntimeId,
    val issue: LocalRuntimeIssue = LocalRuntimeIssue.NotConfigured,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == LocalRuntimeIssue.Ready
}

interface LocalRuntime {
    val id: LocalRuntimeId
    val displayName: String
    val homeDirectory: String
    val workspaceRoot: String
    val managedCommandsDirectory: String

    suspend fun inspectSetup(): LocalRuntimeSetupState

    suspend fun execute(
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)? = null,
    ): String

    suspend fun fetchExecution(argumentsJson: String): String

    suspend fun killExecution(argumentsJson: String): String

    suspend fun killExecutionByRunId(
        runId: String,
        tailBytes: Int = 12 * 1024,
    ): String

    suspend fun executeCommand(
        command: String,
        workingDirectory: String = homeDirectory,
        awaitTimeoutMillis: Long = 15_000L,
    ): String

    fun normalizePath(path: String): String = when {
        path == "~" -> homeDirectory
        path.startsWith("~/") -> homeDirectory + path.removePrefix("~")
        else -> path
    }
}
