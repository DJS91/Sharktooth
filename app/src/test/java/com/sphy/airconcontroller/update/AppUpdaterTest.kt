package com.sphy.airconcontroller.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test fun renamedApkTakesPriorityOverCompatibilityCopy() {
        assertEquals("Sharktooth.apk",
            AppUpdater.preferredApkAssetName(listOf("open-dikey.apk", "Sharktooth.apk")))
    }

    @Test fun olderReleasesStillOfferAnApk() {
        assertEquals("open-dikey.apk",
            AppUpdater.preferredApkAssetName(listOf("dikey-usb-bridge-c3.bin", "open-dikey.apk")))
    }

    @Test fun firmwareOnlyReleaseDoesNotOfferAnApk() {
        assertNull(AppUpdater.preferredApkAssetName(listOf("Sharktooth-usb-bridge-c3.bin")))
    }

    @Test
    fun newerDateStampBeatsOlder() {
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "2026.09.12-0218"))
        assertFalse(AppUpdater.isNewer("2026.09.12-0218", "2026.09.12-0357"))
    }

    @Test
    fun tagPrefixIsIgnored() {
        assertTrue(AppUpdater.isNewer("v2026.09.12-0357", "2026.09.12-0218"))
    }

    @Test
    fun legacyVersionIsAlwaysOlder() {
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "1.0"))
        assertTrue(AppUpdater.isNewer("2026.09.12-0357", "1.0-dev"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(AppUpdater.isNewer("2026.09.12-0357", "v2026.09.12-0357"))
    }

    @Test fun devTagsCompareAgainstInstalledDevVersions() {
        assertTrue(AppUpdater.isNewer("dev-v2026.11.21-120031-dev", "2026.11.21-120030-dev"))
        assertFalse(AppUpdater.isNewer("dev-v2026.11.21-120030-dev", "2026.11.21-120030-dev"))
        assertTrue(AppUpdater.isNewer("v2026.11.21-120031", "2026.11.21-120030-dev"))
        assertTrue(AppUpdater.isNewer("dev-v2026.11.21-120030-dev", "2026.11.21-1200"))
    }

    @Test fun stableChannelNeverAcceptsDevOrOtherPrereleases() {
        assertTrue(AppUpdater.acceptsRelease("v2026.11.21-120030", false, false, dev = false))
        assertFalse(AppUpdater.acceptsRelease("dev-v2026.11.21-120030-dev", false, true, dev = false))
        assertFalse(AppUpdater.acceptsRelease("dev-v2026.11.21-120030-dev", false, false, dev = false))
        assertFalse(AppUpdater.acceptsRelease("v2026.11.21-120030", true, false, dev = false))
        assertFalse(AppUpdater.acceptsRelease("v2026.11.21-120030-beta", false, true, dev = false))
    }

    @Test fun devChannelOnlyAcceptsPublishedDevPrereleases() {
        assertTrue(AppUpdater.acceptsRelease("dev-v2026.11.21-120030-dev", false, true, dev = true))
        assertFalse(AppUpdater.acceptsRelease("v2026.11.21-120030", false, false, dev = true))
        assertFalse(AppUpdater.acceptsRelease("dev-v2026.11.21-120030-dev", true, true, dev = true))
        assertFalse(AppUpdater.acceptsRelease("dev-v2026.11.21-120030-dev", false, false, dev = true))
        assertFalse(AppUpdater.acceptsRelease("v2026.11.21-120030-beta", false, true, dev = true))
    }
}
