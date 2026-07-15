package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatAttachment
import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillManagerTest {
    @Test
    fun keepsGitHubArchiveZipUrlAsDirectDownload() {
        val url = "https://github.com/owner/repository/archive/refs/heads/main.zip"

        val plan = resolveRemoteDownloadPlan(url)

        assertEquals(SkillInstallKind.RemoteZip, plan.kind)
        assertEquals(url, plan.downloadUrl)
    }

    @Test
    fun acceptsGitHubCodeloadZipUrl() {
        val url = "https://codeload.github.com/owner/repository/zip/refs/heads/main"

        val plan = resolveRemoteDownloadPlan(url)

        assertEquals(SkillInstallKind.RemoteZip, plan.kind)
        assertEquals(url, plan.downloadUrl)
    }

    @Test
    fun keepsGitHubTreeUrlWhenSubpathEndsWithZip() {
        val plan = resolveRemoteDownloadPlan(
            "https://github.com/owner/repository/tree/main/skills/example.zip",
        )

        assertEquals(SkillInstallKind.GitHub, plan.kind)
        assertEquals(
            "https://api.github.com/repos/owner/repository/zipball/main",
            plan.downloadUrl,
        )
        assertEquals("main", plan.ref)
        assertEquals("skills/example.zip", plan.subpath)
    }

    @Test
    fun implicitSkillMatcherPrefersExplicitSkillNameAndRelevantDescription() {
        val result = scoreImplicitSkillMatch(
            skill = installedSkill(
                id = "android-qa",
                name = "Android QA",
                description = "Test Android UI workflows, reproduce bugs, and validate APK fixes.",
            ),
            requestText = "请用 Android QA skill 验证这个 APK 的 UI 修复并回归测试。",
        )

        assertTrue(result != null && result > 0)
    }

    @Test
    fun implicitSkillMatcherRejectsWeakSingleTokenOverlap() {
        val score = scoreImplicitSkillMatch(
            skill = installedSkill(
                id = "github",
                name = "GitHub",
                description = "Review repositories and pull requests.",
            ),
            requestText = "Please fix this Android crash in the app.",
        )

        assertNull(score)
    }

    @Test
    fun implicitRequestTextUsesMostRecentUserRun() {
        val requestText = buildImplicitSkillRequestText(
            listOf(
                ChatMessage(
                    id = "1",
                    author = MessageAuthor.User,
                    text = "Older request",
                ),
                ChatMessage(
                    id = "2",
                    author = MessageAuthor.Agent,
                    text = "Agent response",
                ),
                ChatMessage(
                    id = "3",
                    author = MessageAuthor.User,
                    text = "Latest request",
                    attachments = listOf(
                        ChatAttachment(
                            id = "a1",
                            uri = "content://attachment",
                            name = "bugreport.txt",
                            mimeType = "text/plain",
                            sizeBytes = 42L,
                            kind = com.zhousl.aether.ui.AttachmentKind.File,
                        )
                    ),
                ),
            )
        )

        assertEquals("Latest request\nAttachments: bugreport.txt text/plain", requestText)
    }

    @Test
    fun turnSkillSelectionKeepsImplicitMatchesOutOfExplicitSelection() {
        val explicit = activeSkillContext("explicit")
        val implicit = activeSkillContext("implicit")

        val result = resolveTurnSkillSelectionForTest(
            explicitActiveSkills = listOf(explicit),
            implicitActiveSkills = listOf(implicit),
        )

        assertEquals(listOf("explicit"), result.selectedSkillIds)
        assertEquals(listOf("explicit", "implicit"), result.activeSkills.map { it.skillId })
    }
}

private fun installedSkill(
    id: String,
    name: String,
    description: String,
): InstalledSkill = InstalledSkill(
    id = id,
    name = name,
    description = description,
    skillRootPath = "/tmp/$id",
    skillMdPath = "/tmp/$id/SKILL.md",
)

private fun activeSkillContext(skillId: String): ActiveSkillContext = ActiveSkillContext(
    skillId = skillId,
    name = skillId,
    description = "$skillId description",
    skillRootPath = "/tmp/$skillId",
    bodyMarkdown = "# $skillId",
)
