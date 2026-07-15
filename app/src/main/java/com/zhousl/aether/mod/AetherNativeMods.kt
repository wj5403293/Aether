package com.zhousl.aether.mod

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import com.zhousl.aether.data.AetherDiagnosticLogger
import com.zhousl.aether.data.AetherModKernel
import com.zhousl.aether.data.AetherModOperationDecision
import com.zhousl.aether.data.AetherModOperationInterceptor
import com.zhousl.aether.data.AetherModServiceHandler
import com.zhousl.aether.data.AetherModServiceMethod
import com.zhousl.aether.data.PiExtensionStateRepository
import com.zhousl.aether.runtime.AlpineRuntime
import com.zhousl.aether.ui.AetherUiState
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val AetherNativeExtensionGuestDirectory = "/root/.aether/extensions"
private const val NativeModPreferences = "aether_native_mods"
private const val PreferenceStartupInProgress = "startup_in_progress"
private const val PreferenceSafeMode = "safe_mode"
private const val PreferenceLastLoadingMod = "last_loading_mod"
private const val PreferenceFailedModIds = "failed_mod_ids"
private const val NativeModUiStableDelayMillis = 5_000L

enum class AetherNativeComponentMode {
    Before,
    After,
    Replace,
    Wrap,
    Hide,
}

fun interface AetherNativeHost {
    suspend fun invoke(
        method: String,
        args: JSONObject,
    ): JSONObject
}

data class AetherNativeComponentContext(
    val target: String,
    val uiState: AetherUiState,
    val publicState: JSONObject,
    val host: AetherNativeHost,
)

interface AetherNativeComponentRenderer {
    @SuppressLint("ComposableNaming")
    @Composable
    fun render(
        context: AetherNativeComponentContext,
        next: @Composable () -> Unit,
    )
}

data class AetherNativeComponentRegistration(
    val target: String,
    val id: String,
    val owner: String,
    val mode: AetherNativeComponentMode,
    val priority: Int,
    val sequence: Long,
    val renderer: AetherNativeComponentRenderer?,
)

class AetherNativeComponentRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val _registrations =
        MutableStateFlow<List<AetherNativeComponentRegistration>>(emptyList())

    val registrations: StateFlow<List<AetherNativeComponentRegistration>> =
        _registrations.asStateFlow()

    fun register(
        target: String,
        id: String,
        owner: String,
        mode: AetherNativeComponentMode = AetherNativeComponentMode.After,
        priority: Int = 0,
        renderer: AetherNativeComponentRenderer? = null,
    ): () -> Unit {
        val normalizedTarget = target.trim()
        val normalizedId = id.trim()
        require(normalizedTarget.isNotBlank()) {
            "Aether native components require a target."
        }
        require(normalizedId.isNotBlank()) {
            "Aether native components require an id."
        }
        require(mode == AetherNativeComponentMode.Hide || renderer != null) {
            "Aether native component $normalizedId requires a renderer for mode $mode."
        }
        val registration = AetherNativeComponentRegistration(
            target = normalizedTarget,
            id = normalizedId,
            owner = owner.trim().ifBlank { "unknown" },
            mode = mode,
            priority = priority,
            sequence = sequence.incrementAndGet(),
            renderer = renderer,
        )
        synchronized(lock) {
            _registrations.value = _registrations.value + registration
        }
        return {
            synchronized(lock) {
                _registrations.value = _registrations.value - registration
            }
        }
    }

    fun componentsAt(target: String): List<AetherNativeComponentRegistration> =
        _registrations.value
            .filter { it.target == target }
            .sortedWith(
                compareBy<AetherNativeComponentRegistration> { it.priority }
                    .thenBy { it.sequence }
            )

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            _registrations.value = _registrations.value.filterNot { it.owner == owner }
        }
    }
}

interface AetherNativeMod {
    fun onLoad(context: AetherNativeModContext)

    fun onUnload() = Unit
}

class AetherNativeModContext internal constructor(
    val application: Application,
    val modId: String,
    val packageRoot: File,
    val classLoader: ClassLoader,
    val kernel: AetherModKernel,
    val diagnosticLogger: AetherDiagnosticLogger,
) {
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    fun registerService(
        id: String,
        description: String = "",
        priority: Int = 100,
        methods: List<AetherModServiceMethod>,
        handler: AetherModServiceHandler,
    ): () -> Unit = track(
        kernel.services.register(
            id = id,
            owner = modId,
            description = description,
            priority = priority,
            methods = methods,
            handler = handler,
        )
    )

    fun intercept(
        operation: String,
        priority: Int = 100,
        interceptor: AetherModOperationInterceptor,
    ): () -> Unit = track(
        kernel.operations.register(
            operation = operation,
            owner = modId,
            priority = priority,
            interceptor = interceptor,
        )
    )

    fun registerComponent(
        target: String,
        id: String,
        mode: AetherNativeComponentMode = AetherNativeComponentMode.After,
        priority: Int = 100,
        renderer: AetherNativeComponentRenderer? = null,
    ): () -> Unit = track(
        kernel.components.register(
            target = target,
            id = id,
            owner = modId,
            mode = mode,
            priority = priority,
            renderer = renderer,
        )
    )

    fun packageFile(relativePath: String): File =
        File(packageRoot, relativePath).canonicalFile

    fun log(
        event: String,
        details: Map<String, Any?> = emptyMap(),
        level: String = "info",
    ) {
        diagnosticLogger.event(
            category = "native_mod",
            event = event,
            level = level,
            details = details + ("mod_id" to modId),
        )
    }

    internal fun rollback() {
        cleanups.asReversed().forEach { cleanup ->
            runCatching(cleanup)
        }
        cleanups.clear()
        kernel.services.unregisterOwner(modId)
        kernel.operations.unregisterOwner(modId)
        kernel.components.unregisterOwner(modId)
    }

    private fun track(cleanup: () -> Unit): () -> Unit {
        cleanups += cleanup
        return {
            if (cleanups.remove(cleanup)) {
                cleanup()
            }
        }
    }
}

data class AetherNativeModDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val packagePath: String,
    val entrypoints: List<String>,
)

data class AetherLoadedNativeMod(
    val id: String,
    val entrypoint: String,
)

data class AetherNativeModFailure(
    val id: String,
    val entrypoint: String = "",
    val message: String,
)

data class AetherNativeModState(
    val isInitializing: Boolean = false,
    val safeModeActive: Boolean = false,
    val restartRequired: Boolean = false,
    val suspectedCrashModId: String = "",
    val discovered: List<AetherNativeModDescriptor> = emptyList(),
    val loaded: List<AetherLoadedNativeMod> = emptyList(),
    val failures: List<AetherNativeModFailure> = emptyList(),
)

internal data class AetherNativeModManifest(
    val descriptor: AetherNativeModDescriptor,
    val packageRoot: File,
    val classpath: List<File>,
    val libraryPaths: List<File>,
)

@SuppressLint("ApplySharedPref")
class AetherNativeModManager(
    context: Context,
    private val application: Application,
    private val alpineRuntime: AlpineRuntime,
    private val kernel: AetherModKernel,
    private val piExtensionStateRepository: PiExtensionStateRepository,
    private val diagnosticLogger: AetherDiagnosticLogger,
) {
    private data class LoadedEntrypoint(
        val instance: AetherNativeMod,
        val context: AetherNativeModContext,
        val classLoader: ClassLoader,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        NativeModPreferences,
        Context.MODE_PRIVATE,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val loadedEntrypoints = CopyOnWriteArrayList<LoadedEntrypoint>()
    private val _state = MutableStateFlow(AetherNativeModState())
    private val lock = Any()
    private var didInitialize = false
    private var initializationCompleted = false
    private var uiStable = false

    val state: StateFlow<AetherNativeModState> = _state.asStateFlow()

    suspend fun initialize() {
        synchronized(lock) {
            if (didInitialize) return
            didInitialize = true
        }
        val discovery = discoverNativeMods()
        val previousStartupWasInterrupted =
            preferences.getBoolean(PreferenceStartupInProgress, false)
        val suspectedModId = preferences.getString(PreferenceLastLoadingMod, "").orEmpty()
        val safeModeActive = preferences.getBoolean(PreferenceSafeMode, false) ||
            previousStartupWasInterrupted
        if (previousStartupWasInterrupted) {
            preferences.edit()
                .putBoolean(PreferenceSafeMode, true)
                .putBoolean(PreferenceStartupInProgress, false)
                .apply()
        }
        if (safeModeActive) {
            _state.value = AetherNativeModState(
                safeModeActive = true,
                suspectedCrashModId = suspectedModId,
                discovered = discovery.manifests.map(AetherNativeModManifest::descriptor),
                failures = discovery.failures,
            )
            markInitializationCompleted()
            diagnosticLogger.event(
                category = "native_mod",
                event = "safe_mode_active",
                level = "warn",
                details = mapOf(
                    "suspected_mod_id" to suspectedModId,
                    "discovered_count" to discovery.manifests.size,
                ),
            )
            return
        }
        if (discovery.manifests.isEmpty()) {
            _state.value = AetherNativeModState(
                discovered = emptyList(),
                failures = discovery.failures,
            )
            markInitializationCompleted()
            return
        }

        preferences.edit()
            .putBoolean(PreferenceStartupInProgress, true)
            .putString(PreferenceLastLoadingMod, "")
            .commit()
        _state.value = AetherNativeModState(
            isInitializing = true,
            discovered = discovery.manifests.map(AetherNativeModManifest::descriptor),
            failures = discovery.failures,
        )

        val loaded = mutableListOf<AetherLoadedNativeMod>()
        val failures = discovery.failures.toMutableList()
        discovery.manifests.forEach { manifest ->
            loadManifest(manifest, loaded, failures)
        }
        preferences.edit()
            .putStringSet(
                PreferenceFailedModIds,
                failures.map(AetherNativeModFailure::id).toSet(),
            )
            .putString(PreferenceLastLoadingMod, "")
            .apply()
        _state.value = AetherNativeModState(
            discovered = discovery.manifests.map(AetherNativeModManifest::descriptor),
            loaded = loaded,
            failures = failures,
        )
        markInitializationCompleted()
    }

    fun notifyUiStable() {
        handler.removeCallbacksAndMessages(this)
        handler.postAtTime(
            {
                synchronized(lock) {
                    uiStable = true
                }
                maybeClearStartupGuard()
            },
            this,
            android.os.SystemClock.uptimeMillis() + NativeModUiStableDelayMillis,
        )
    }

    fun allowNativeModsOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, false)
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
        _state.value = _state.value.copy(
            safeModeActive = false,
            restartRequired = true,
            suspectedCrashModId = "",
        )
    }

    suspend fun refreshDiscovery() {
        val discovery = discoverNativeMods()
        val descriptors = discovery.manifests.map(AetherNativeModManifest::descriptor)
        val current = _state.value
        val changed = current.discovered != descriptors
        _state.value = current.copy(
            discovered = descriptors,
            restartRequired = current.restartRequired || (didInitialize && changed),
            failures = (current.failures + discovery.failures)
                .distinctBy { failure ->
                    "${failure.id}:${failure.entrypoint}:${failure.message}"
                },
        )
    }

    fun requestDisableOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, true)
            .putBoolean(PreferenceStartupInProgress, false)
            .apply()
        _state.value = _state.value.copy(
            restartRequired = true,
        )
    }

    private fun loadManifest(
        manifest: AetherNativeModManifest,
        loaded: MutableList<AetherLoadedNativeMod>,
        failures: MutableList<AetherNativeModFailure>,
    ) {
        val classLoader = runCatching {
            val optimizedDirectory = File(
                appContext.codeCacheDir,
                "aether-native-mods/${sanitizeId(manifest.descriptor.id)}",
            ).apply {
                require(mkdirs() || isDirectory) {
                    "Unable to create native mod code cache."
                }
            }
            DexClassLoader(
                manifest.classpath.joinToString(File.pathSeparator) { it.path },
                optimizedDirectory.path,
                manifest.libraryPaths
                    .takeIf(List<File>::isNotEmpty)
                    ?.joinToString(File.pathSeparator) { it.path },
                appContext.classLoader,
            )
        }.getOrElse { throwable ->
            failures += AetherNativeModFailure(
                id = manifest.descriptor.id,
                message = throwable.message ?: throwable.javaClass.name,
            )
            diagnosticLogger.exception(
                category = "native_mod",
                event = "classloader_failed",
                throwable = throwable,
                details = mapOf("mod_id" to manifest.descriptor.id),
            )
            return
        }

        manifest.descriptor.entrypoints.forEach { entrypoint ->
            val ownerId = "${manifest.descriptor.id}:$entrypoint"
            preferences.edit()
                .putString(PreferenceLastLoadingMod, ownerId)
                .commit()
            val nativeContext = AetherNativeModContext(
                application = application,
                modId = ownerId,
                packageRoot = manifest.packageRoot,
                classLoader = classLoader,
                kernel = kernel,
                diagnosticLogger = diagnosticLogger,
            )
            runCatching {
                val entrypointClass = Class.forName(entrypoint, true, classLoader)
                val instance = instantiateNativeMod(entrypointClass)
                require(instance is AetherNativeMod) {
                    "$entrypoint does not implement ${AetherNativeMod::class.java.name}."
                }
                instance.onLoad(nativeContext)
                loadedEntrypoints += LoadedEntrypoint(
                    instance = instance,
                    context = nativeContext,
                    classLoader = classLoader,
                )
                loaded += AetherLoadedNativeMod(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                )
                diagnosticLogger.event(
                    category = "native_mod",
                    event = "loaded",
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }.onFailure { throwable ->
                nativeContext.rollback()
                failures += AetherNativeModFailure(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                    message = throwable.message ?: throwable.javaClass.name,
                )
                diagnosticLogger.exception(
                    category = "native_mod",
                    event = "load_failed",
                    throwable = throwable,
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }
        }
    }

    private fun markInitializationCompleted() {
        synchronized(lock) {
            initializationCompleted = true
        }
        maybeClearStartupGuard()
    }

    private fun maybeClearStartupGuard() {
        val shouldClear = synchronized(lock) {
            initializationCompleted && uiStable
        }
        if (!shouldClear) return
        preferences.edit()
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
    }

    private suspend fun discoverNativeMods(): NativeModDiscovery {
        val disabledPaths = piExtensionStateRepository.loadOptions().disabledExtensionPaths
        val root = alpineRuntime.resolveManagedGuestPath(
            AetherNativeExtensionGuestDirectory
        )
        if (!root.isDirectory) return NativeModDiscovery()
        val manifests = mutableListOf<AetherNativeModManifest>()
        val failures = mutableListOf<AetherNativeModFailure>()
        root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { packageRoot ->
                if (packageRoot.canonicalPath in disabledPaths) return@forEach
                val manifestFile = File(packageRoot, "package.json")
                if (!manifestFile.isFile) return@forEach
                runCatching {
                    parseAetherNativeModManifest(
                        packageRoot = packageRoot,
                        manifest = JSONObject(manifestFile.readText(Charsets.UTF_8)),
                    )
                }.onSuccess { manifest ->
                    if (manifest != null) manifests += manifest
                }.onFailure { throwable ->
                    failures += AetherNativeModFailure(
                        id = packageRoot.name,
                        message = throwable.message ?: throwable.javaClass.name,
                    )
                    diagnosticLogger.exception(
                        category = "native_mod",
                        event = "manifest_failed",
                        throwable = throwable,
                        details = mapOf("package_path" to packageRoot.path),
                    )
                }
            }
        return NativeModDiscovery(
            manifests = manifests,
            failures = failures,
        )
    }
}

private data class NativeModDiscovery(
    val manifests: List<AetherNativeModManifest> = emptyList(),
    val failures: List<AetherNativeModFailure> = emptyList(),
)

internal fun parseAetherNativeModManifest(
    packageRoot: File,
    manifest: JSONObject,
): AetherNativeModManifest? {
    val native = manifest
        .optJSONObject("aether")
        ?.optJSONObject("native")
        ?: return null
    if (!native.optBoolean("enabled", true)) return null

    val packageCanonical = packageRoot.canonicalFile
    val id = manifest.optString("name").trim().ifBlank { packageCanonical.name }
    val entrypoints = native.stringList("entrypoints")
        .ifEmpty { native.stringList("entrypoint") }
    require(entrypoints.isNotEmpty()) {
        "Native mod $id must declare aether.native.entrypoints."
    }
    val classpath = native.stringList("classpath").map { path ->
        resolvePackagePath(packageCanonical, path).also { file ->
            require(file.isFile) {
                "Native mod classpath does not exist: $path"
            }
        }
    }
    require(classpath.isNotEmpty()) {
        "Native mod $id must declare aether.native.classpath."
    }
    val libraryPaths = (
        native.stringList("libraryPath") +
            native.stringList("library_path")
        )
        .distinct()
        .map { path ->
            resolvePackagePath(packageCanonical, path).also { file ->
                require(file.isDirectory) {
                    "Native mod library path does not exist: $path"
                }
            }
        }
    return AetherNativeModManifest(
        descriptor = AetherNativeModDescriptor(
            id = id,
            name = manifest.optString("displayName").trim().ifBlank { id },
            version = manifest.optString("version"),
            packagePath = packageCanonical.path,
            entrypoints = entrypoints,
        ),
        packageRoot = packageCanonical,
        classpath = classpath,
        libraryPaths = libraryPaths,
    )
}

private fun JSONObject.stringList(key: String): List<String> = when (
    val value = opt(key)
) {
    is JSONArray -> buildList {
        for (index in 0 until value.length()) {
            value.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    is String -> value.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}

private fun resolvePackagePath(
    packageRoot: File,
    path: String,
): File {
    val file = File(packageRoot, path).canonicalFile
    require(
        file.path == packageRoot.path ||
            file.path.startsWith(packageRoot.path + File.separator)
    ) {
        "Native mod path escaped its package: $path"
    }
    return file
}

private fun instantiateNativeMod(entrypointClass: Class<*>): Any {
    runCatching {
        entrypointClass.getField("INSTANCE").get(null)
    }.getOrNull()?.let { return it }
    return entrypointClass.getDeclaredConstructor().apply {
        isAccessible = true
    }.newInstance()
}

private fun sanitizeId(value: String): String =
    value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "native-mod" }

fun AetherNativeModContext.cancelOperation(
    payload: JSONObject,
    reason: String = "",
): AetherModOperationDecision = AetherModOperationDecision(
    payload = payload,
    cancelled = true,
    reason = reason,
)
