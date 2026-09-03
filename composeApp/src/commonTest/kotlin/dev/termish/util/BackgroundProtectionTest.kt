package dev.termish.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundProtectionTest {
    @Test
    fun promptsOnlyAfterAnSshSessionConnects() {
        assertFalse(
            shouldShowBackgroundProtectionGuide(
                state = BackgroundProtectionState.SYSTEM_MANAGED,
                vendor = BackgroundProtectionVendor.GENERIC,
                alreadyPrompted = false,
                hasConnectedSshSession = false,
            ),
        )
        assertTrue(
            shouldShowBackgroundProtectionGuide(
                state = BackgroundProtectionState.SYSTEM_MANAGED,
                vendor = BackgroundProtectionVendor.GENERIC,
                alreadyPrompted = false,
                hasConnectedSshSession = true,
            ),
        )
    }

    @Test
    fun neverRepeatsAfterBeingShown() {
        assertFalse(
            shouldShowBackgroundProtectionGuide(
                state = BackgroundProtectionState.SYSTEM_MANAGED,
                vendor = BackgroundProtectionVendor.OPPO_FAMILY,
                alreadyPrompted = true,
                hasConnectedSshSession = true,
            ),
        )
    }

    @Test
    fun knownVendorStillGetsInstructionsWhenSystemIsExempt() {
        assertTrue(
            shouldShowBackgroundProtectionGuide(
                state = BackgroundProtectionState.SYSTEM_EXEMPT,
                vendor = BackgroundProtectionVendor.OPPO_FAMILY,
                alreadyPrompted = false,
                hasConnectedSshSession = true,
            ),
        )
        assertFalse(
            shouldShowBackgroundProtectionGuide(
                state = BackgroundProtectionState.SYSTEM_EXEMPT,
                vendor = BackgroundProtectionVendor.GENERIC,
                alreadyPrompted = false,
                hasConnectedSshSession = true,
            ),
        )
    }
}
