package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.piExtensionStateDataStore by preferencesDataStore(
    name = "aether_pi_extension_state",
)

data class PiExtensionLoadOptions(
    val disabledExtensionPaths: Set<String> = emptySet(),
    val disabledPackageSources: Set<String> = emptySet(),
) {
    fun toJsonArrays(): Pair<List<String>, List<String>> =
        disabledExtensionPaths.toList() to disabledPackageSources.toList()
}

class PiExtensionStateRepository(
    private val context: Context,
) {
    val disabledExtensionIds: Flow<Set<String>> =
        context.piExtensionStateDataStore.data.map { preferences ->
            preferences[DISABLED_EXTENSION_IDS].orEmpty()
        }

    suspend fun setEnabled(
        extensionId: String,
        enabled: Boolean,
    ) {
        val normalizedId = extensionId.trim()
        if (normalizedId.isBlank()) return
        context.piExtensionStateDataStore.edit { preferences ->
            val disabledIds = preferences[DISABLED_EXTENSION_IDS].orEmpty().toMutableSet()
            if (enabled) {
                disabledIds.remove(normalizedId)
            } else {
                disabledIds.add(normalizedId)
            }
            if (disabledIds.isEmpty()) {
                preferences.remove(DISABLED_EXTENSION_IDS)
            } else {
                preferences[DISABLED_EXTENSION_IDS] = disabledIds
            }
        }
    }

    suspend fun loadOptions(): PiExtensionLoadOptions =
        loadOptionsForIds(disabledExtensionIds.first())

    private companion object {
        val DISABLED_EXTENSION_IDS = stringSetPreferencesKey("disabled_extension_ids")
    }
}

internal fun loadOptionsForIds(
    disabledExtensionIds: Set<String>,
): PiExtensionLoadOptions {
    val disabledExtensionPaths = mutableSetOf<String>()
    val disabledPackageSources = mutableSetOf<String>()
    disabledExtensionIds.forEach { rawId ->
        val id = rawId.trim()
        when {
            id.startsWith("package:") -> {
                id.removePrefix("package:")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(disabledPackageSources::add)
            }

            id.startsWith("import:") -> {
                id.substringAfter(':', "")
                    .substringAfter(':', "")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(disabledExtensionPaths::add)
            }
        }
    }
    return PiExtensionLoadOptions(
        disabledExtensionPaths = disabledExtensionPaths,
        disabledPackageSources = disabledPackageSources,
    )
}
