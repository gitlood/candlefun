package com.example.ofi.kukanov

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class KukanovOfiConfigTest {
    @Test
    fun `ofi config validates inputs`() {
        assertFailsWith<IllegalArgumentException> { KukanovOfiConfig(window = 0.milliseconds) }
        assertFailsWith<IllegalArgumentException> { KukanovOfiConfig(depthLevels = 0) }
    }
}
