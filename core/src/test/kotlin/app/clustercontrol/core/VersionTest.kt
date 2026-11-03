package app.clustercontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun version() = assertEquals("0.1.0", CORE_VERSION)
}
