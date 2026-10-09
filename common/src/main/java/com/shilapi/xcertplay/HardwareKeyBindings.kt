package com.shilapi.xcertplay

import android.content.Context
import android.view.KeyEvent

/**
 * Owner-learned hardware-key bindings for head units whose steering-wheel keys use OEM-specific codes.
 *
 * Ported from HeadUnitWireless-CarPlay (GPL-3.0) `GolfKeyBindings`, with this port's conventions:
 * the preferences file is `diplay_hardware_keys` and localized labels live in the settings UI, not
 * here. Only explicit owner assignments are stored — firmware numeric codes carry no assumed
 * meaning — and a physical signal is matched by (keyCode, scanCode) so codes a firmware reuses for
 * several functions still bind correctly.
 */
internal object HardwareKeyBindings {
    enum class Action(val id: String, val logicalCode: Int) {
        PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT),
        PHONE("phone", KeyEvent.KEYCODE_CALL),
        VOICE("voice", KeyEvent.KEYCODE_VOICE_ASSIST),
    }
    data class Binding(val action: Action, val keyCode: Int, val scanCode: Int)

    private fun prefs(context: Context) =
        context.getSharedPreferences("diplay_hardware_keys", Context.MODE_PRIVATE)

    fun canLearn(event: KeyEvent): Boolean = event.action == KeyEvent.ACTION_DOWN &&
        event.repeatCount == 0 && validCode(event.keyCode, event.scanCode) && !event.isPrintingKey

    private fun validCode(code: Int, scan: Int): Boolean = code >= 0 && scan >= 0 &&
        (code != KeyEvent.KEYCODE_UNKNOWN || scan > 0) && code !in RESERVED_CODES

    fun load(context: Context): List<Binding> {
        val prefs = prefs(context)
        val result = mutableListOf<Binding>()
        for (action in Action.values()) {
            val parts = prefs.getString(action.id, null)?.split(':') ?: continue
            if (parts.size != 2) continue
            val code = parts[0].toIntOrNull() ?: continue
            val scan = parts[1].toIntOrNull() ?: continue
            if (!validCode(code, scan) || KeyEvent(KeyEvent.ACTION_DOWN, code).isPrintingKey ||
                result.any { it.keyCode == code && it.scanCode == scan }) continue
            result.add(Binding(action, code, scan))
        }
        return result
    }

    fun save(context: Context, action: Action, event: KeyEvent) {
        require(canLearn(event)) { "This key cannot be assigned" }
        require(load(context).none { it.action != action && it.keyCode == event.keyCode && it.scanCode == event.scanCode }) {
            "This physical signal is already assigned to another action"
        }
        prefs(context).edit().putString(action.id, "${event.keyCode}:${event.scanCode}").apply()
    }

    fun clear(context: Context) { prefs(context).edit().clear().apply() }

    fun logicalKey(context: Context, event: KeyEvent): Int? = logicalKey(load(context), event)

    fun logicalKey(bindings: List<Binding>, event: KeyEvent): Int? = bindings.firstOrNull {
        it.keyCode == event.keyCode && it.scanCode == event.scanCode
    }?.action?.logicalCode

    private val RESERVED_CODES = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
}
