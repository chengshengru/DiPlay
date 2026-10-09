package com.shilapi.xcertplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Port of HeadUnitWireless-CarPlay `GolfKeyBindingsTest`, keeping only the parts that do not drive
 * B-specific activity internals. Verifies that OEM codes stay unmapped until the owner saves them,
 * that bindings never collide, and that clearing them cannot disturb other preferences.
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 (P), not 29: this module targets SDK 17, so Robolectric falls back to legacy resources
// mode and silently skips any test above P ("doesn't support legacy mode after P"). 28 keeps the
// suite runnable, and nothing here depends on a specific SDK level.
@Config(sdk = [28])
class HardwareKeyBindingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun event(code: Int = 239, scan: Int = 563, action: Int = KeyEvent.ACTION_DOWN) =
        KeyEvent(1000, 1100, action, code, 0, 0, 4, scan)

    @Test fun observedOemCodesAreUnmappedUntilOwnerSavesThem() {
        assertNull(HardwareKeyBindings.logicalKey(context, event()))
        HardwareKeyBindings.save(context, HardwareKeyBindings.Action.NEXT, event())
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, HardwareKeyBindings.logicalKey(context, event()))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT,
            HardwareKeyBindings.logicalKey(context, event(action = KeyEvent.ACTION_UP)))
        assertNull(HardwareKeyBindings.logicalKey(context, event(scan = 560)))
        assertNull(HardwareKeyBindings.logicalKey(context, event(code = 236)))
    }

    @Test fun onePhysicalSignalCannotExecuteTwoDifferentActions() {
        HardwareKeyBindings.save(context, HardwareKeyBindings.Action.NEXT, event())
        assertThrows(IllegalArgumentException::class.java) {
            HardwareKeyBindings.save(context, HardwareKeyBindings.Action.PHONE, event())
        }
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, HardwareKeyBindings.logicalKey(context, event()))
    }

    @Test fun navigationVolumePowerAndTypedCharactersCannotBeCaptured() {
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER)) {
            assertFalse("code=$code", HardwareKeyBindings.canLearn(event(code)))
        }
        assertTrue(HardwareKeyBindings.canLearn(event()))
        assertFalse(HardwareKeyBindings.canLearn(event(action = KeyEvent.ACTION_UP)))
    }

    @Test fun malformedPreferenceCannotCreateAMappingOrCrash() {
        context.getSharedPreferences("diplay_hardware_keys", 0).edit()
            .putString("next", "bad:999")
            .putString("phone", "4:158")
            .putString("previous", "239:-1").apply()
        assertTrue(HardwareKeyBindings.load(context).isEmpty())
    }

    @Test fun clearingBindingsDoesNotDisturbOtherPreferences() {
        context.getSharedPreferences("diplay_unrelated", 0).edit().putString("keep", "value").apply()
        HardwareKeyBindings.save(context, HardwareKeyBindings.Action.NEXT, event())
        assertEquals(1, HardwareKeyBindings.load(context).size)
        HardwareKeyBindings.clear(context)
        assertTrue(HardwareKeyBindings.load(context).isEmpty())
        assertEquals("value", context.getSharedPreferences("diplay_unrelated", 0).getString("keep", null))
    }
}
