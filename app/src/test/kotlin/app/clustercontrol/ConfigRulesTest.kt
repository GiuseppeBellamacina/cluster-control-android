package app.clustercontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigRulesTest {
    private fun input(
        key: String = "", port: String = "", dir: String = "", repo: String = "",
    ) = ConfigInput(true, "", "", "", "", port, dir, "", key, repo, "")

    @Test
    fun projectDirIsRelativeToTheHome() {
        assertEquals("proj", normalizeProjectDir("~/proj/"))
        assertEquals("a/b", normalizeProjectDir(" a/b "))
        assertEquals("", normalizeProjectDir("  "))
    }

    @Test
    fun validInputsPass() {
        assertNull(configError(input()))
        assertNull(configError(input(key = "-----BEGIN OPENSSH PRIVATE KEY-----\nx", port = "2222", dir = "~/p", repo = "me/my-repo.x")))
    }

    @Test
    fun badInputsAreRefused() {
        assertNotNull(configError(input(key = "just some text")))
        assertNotNull(configError(input(port = "0")))
        assertNotNull(configError(input(port = "http")))
        assertNotNull(configError(input(port = "70000")))
        assertNotNull(configError(input(dir = "/etc")))
        assertNotNull(configError(input(dir = "~//etc")))
        assertNotNull(configError(input(repo = "no-slash")))
    }
}
