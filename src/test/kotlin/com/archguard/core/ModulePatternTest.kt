package com.archguard.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModulePatternTest {

    @Test
    fun `a path matches itself`() {
        assertTrue(matchesModulePattern(":feature:orders", ":feature:orders"))
        assertFalse(matchesModulePattern(":feature:orders", ":feature:order"))
    }

    @Test
    fun `a star matches exactly one segment`() {
        assertTrue(matchesModulePattern(":feature:*", ":feature:orders"))
        assertFalse(matchesModulePattern(":feature:*", ":feature"))
        assertFalse(matchesModulePattern(":feature:*", ":feature:orders:history"))
    }

    @Test
    fun `two stars match any number of segments, none included`() {
        assertTrue(matchesModulePattern(":feature:**", ":feature"))
        assertTrue(matchesModulePattern(":feature:**", ":feature:orders"))
        assertTrue(matchesModulePattern(":feature:**", ":feature:orders:history"))
        assertFalse(matchesModulePattern(":feature:**", ":core:ui"))
    }

    @Test
    fun `two stars in the middle match across segments`() {
        assertTrue(matchesModulePattern(":feature:**:history", ":feature:orders:history"))
        assertTrue(matchesModulePattern(":feature:**:history", ":feature:history"))
        assertFalse(matchesModulePattern(":feature:**:history", ":feature:orders:tracking"))
    }

    @Test
    fun `two stars alone match every module`() {
        assertTrue(matchesModulePattern(ALL_MODULES, ":app"))
        assertTrue(matchesModulePattern(ALL_MODULES, ":shared-component:cart"))
    }

    @Test
    fun `a leading colon is optional in the pattern`() {
        assertTrue(matchesModulePattern("feature:orders", ":feature:orders"))
    }
}
