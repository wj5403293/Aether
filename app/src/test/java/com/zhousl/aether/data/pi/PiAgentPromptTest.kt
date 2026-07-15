package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class PiAgentPromptTest {
    @Test
    fun instructionsRequireFileUriLinksForLocalDownloads() {
        val instructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            availableSkills = emptyList(),
            activeSkills = emptyList(),
            mcpSnapshots = emptyList(),
            mcpToolBindings = emptyList(),
            agentModeEnabled = false,
        )

        assertTrue(instructions.contains("[report.pdf](file:///absolute/path/report.pdf)"))
        assertTrue(instructions.contains("Do not use another URI scheme for local file downloads."))
    }
}
