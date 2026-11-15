package com.lagradost.hless

import com.lagradost.macos.models.RepoPluginDto
import kotlin.test.Test
import kotlin.test.assertEquals

class BootstrapTest {
    private fun plugin(internalName: String, installed: Boolean = false) =
        RepoPluginDto(
            id = internalName,
            name = internalName,
            internalName = internalName,
            url = "https://example.org/$internalName.cs3",
            repositoryUrl = "https://example.org/repo",
            installationId = if (installed) "inst" else null,
        )

    @Test
    fun `star selects all`() {
        assertEquals(listOf("a", "b"), choosePlugins(listOf(plugin("a"), plugin("b")), listOf("*")).map { it.internalName })
    }

    @Test
    fun `names match case-insensitively`() {
        assertEquals(listOf("MyPlugin"), choosePlugins(listOf(plugin("MyPlugin"), plugin("Other")), listOf("myplugin")).map { it.internalName })
    }

    @Test
    fun `unknown names select nothing`() {
        assertEquals(emptyList(), choosePlugins(listOf(plugin("a")), listOf("z")))
    }
}
