package com.zhousl.aether.mod

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AetherNativeModsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesNativeManifestWithDexAndLibraryPaths() {
        val packageRoot = temporaryFolder.newFolder("demo-mod")
        File(packageRoot, "mod.dex").writeBytes(byteArrayOf(1, 2, 3))
        File(packageRoot, "lib").mkdirs()

        val manifest = parseAetherNativeModManifest(
            packageRoot = packageRoot,
            manifest = JSONObject(
                """
                {
                  "name": "demo-native-mod",
                  "version": "1.2.3",
                  "aether": {
                    "native": {
                      "classpath": "./mod.dex",
                      "libraryPath": "./lib",
                      "entrypoints": ["example.DemoMod"]
                    }
                  }
                }
                """.trimIndent()
            ),
        )

        assertEquals("demo-native-mod", manifest?.descriptor?.id)
        assertEquals(listOf("example.DemoMod"), manifest?.descriptor?.entrypoints)
        assertEquals("mod.dex", manifest?.classpath?.single()?.name)
        assertEquals("lib", manifest?.libraryPaths?.single()?.name)
    }

    @Test
    fun ignoresPackagesWithoutNativeManifest() {
        val packageRoot = temporaryFolder.newFolder("script-only")

        assertNull(
            parseAetherNativeModManifest(
                packageRoot = packageRoot,
                manifest = JSONObject(
                    """
                    {
                      "name": "script-only",
                      "aether": {
                        "extensions": ["./index.ts"]
                      }
                    }
                    """.trimIndent()
                ),
            )
        )
    }
}
