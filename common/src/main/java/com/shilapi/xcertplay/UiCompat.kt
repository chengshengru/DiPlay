// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * API 17-safe wrappers for framework widget APIs that only exist on newer Android.
 *
 * minSdk is 17 (Android 4.2.2 Jelly Bean head units), but the UI is written against the modern
 * AndroidX/View API. A direct call to a method that is missing on the running platform throws
 * [NoSuchMethodError] at the call site — the dalvik verifier only replaces the opcode with a
 * throwing stub, so it fails the moment the instruction executes.
 *
 * Every helper keeps the exact original call on platforms where the API exists, so behaviour on
 * modern devices is unchanged; older platforms get a graceful fallback instead of crashing.
 */
// The whole point of this file is to reach for version-specific framework calls, several of which
// are deprecated on current platforms, so the deprecation lint is suppressed for the object.
@Suppress("DEPRECATION")
internal object UiCompat {

    /** RippleDrawable is API 21+. Below that, fall back to the content shape (or transparent). */
    fun ripple(color: Int, content: Drawable?, mask: Drawable?): Drawable =
        if (Build.VERSION.SDK_INT >= 21) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color), content, mask)
        } else {
            content ?: ColorDrawable(Color.TRANSPARENT)
        }

    /** View.setStateListAnimator is API 21+. */
    fun clearStateListAnimator(view: View) {
        if (Build.VERSION.SDK_INT >= 21) view.stateListAnimator = null
    }

    /** View.setForeground is API 23+. */
    fun setForeground(view: View, drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= 23) view.foreground = drawable
    }

    /** ImageView.setImageTintList is API 21+. */
    fun setImageTint(image: ImageView, color: Int) {
        if (Build.VERSION.SDK_INT >= 21) image.imageTintList = ColorStateList.valueOf(color)
    }

    /** PopupWindow.setElevation is API 21+. */
    fun setPopupElevation(popup: PopupWindow, elevation: Float) {
        if (Build.VERSION.SDK_INT >= 21) popup.elevation = elevation
    }

    /**
     * PopupWindow.showAsDropDown(view, x, y, gravity) is API 19+. Below that, drop with the x the
     * caller computed for the alignment the gravity would have produced.
     */
    fun showAsDropDown(popup: PopupWindow, anchor: View, x: Int, y: Int, gravity: Int, fallbackX: Int) {
        if (Build.VERSION.SDK_INT >= 19) popup.showAsDropDown(anchor, x, y, gravity)
        else popup.showAsDropDown(anchor, fallbackX, y)
    }

    /** TextView.setLetterSpacing is API 21+. */
    fun setLetterSpacing(text: TextView, spacing: Float) {
        if (Build.VERSION.SDK_INT >= 21) text.letterSpacing = spacing
    }

    /** View.setClipToOutline is API 21+. */
    fun setClipToOutline(view: View, clip: Boolean) {
        if (Build.VERSION.SDK_INT >= 21) view.clipToOutline = clip
    }

    /** View.setAccessibilityLiveRegion is API 19+. */
    fun setAccessibilityLiveRegion(view: View, mode: Int) {
        if (Build.VERSION.SDK_INT >= 19) view.accessibilityLiveRegion = mode
    }

    /** View.isAttachedToWindow is API 19+. Below that a non-null window token means attached. */
    fun isAttachedToWindow(view: View): Boolean =
        if (Build.VERSION.SDK_INT >= 19) view.isAttachedToWindow else view.windowToken != null

    /** Window.setStatusBarColor/setNavigationBarColor are API 21+. */
    fun setSystemBarColors(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = color
            window.navigationBarColor = color
        }
    }

    /** Switch.setShowText is API 21+. */
    fun setShowText(switch: Switch, show: Boolean) {
        if (Build.VERSION.SDK_INT >= 21) switch.showText = show
    }

    /** CompoundButton.setButtonTintList is API 21+ (Switch and RadioButton). */
    fun setButtonTint(button: CompoundButton, list: ColorStateList) {
        if (Build.VERSION.SDK_INT >= 21) button.buttonTintList = list
    }

    /** Switch.setThumbTintList is API 21+. */
    fun setThumbTint(switch: Switch, list: ColorStateList) {
        if (Build.VERSION.SDK_INT >= 21) switch.thumbTintList = list
    }

    /** Switch.setTrackTintList is API 21+. */
    fun setTrackTint(switch: Switch, list: ColorStateList) {
        if (Build.VERSION.SDK_INT >= 21) switch.trackTintList = list
    }

    /** SeekBar.splitTrack and the progress/thumb tints are API 21+. */
    fun setSeekBarTints(seekBar: SeekBar, accent: Int) {
        if (Build.VERSION.SDK_INT >= 21) {
            seekBar.splitTrack = false
            seekBar.progressTintList = ColorStateList.valueOf(accent)
            seekBar.thumbTintList = ColorStateList.valueOf(accent)
        }
    }

    /** SeekBar.secondaryProgressTintList/progressBackgroundTintList are API 21+. */
    fun setSeekBarSecondaryTints(seekBar: SeekBar, secondary: ColorStateList, background: ColorStateList) {
        if (Build.VERSION.SDK_INT >= 21) {
            seekBar.secondaryProgressTintList = secondary
            seekBar.progressBackgroundTintList = background
        }
    }

    /** TextView.setFontFeatureSettings is API 21+. */
    fun setFontFeatureSettings(text: TextView, settings: String) {
        if (Build.VERSION.SDK_INT >= 21) text.fontFeatureSettings = settings
    }

    /** Drawable.setTint is API 21+. Below that tint through a colour filter, which is API 1. */
    fun tint(drawable: Drawable, color: Int): Drawable {
        if (Build.VERSION.SDK_INT >= 21) drawable.setTint(color) else drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        return drawable
    }

    /**
     * View.setBackgroundTintList is API 21+. Below that the background itself is filtered, so a
     * coloured button keeps its colour instead of falling back to the flat default drawable.
     */
    fun setBackgroundTint(view: View, list: ColorStateList) {
        if (Build.VERSION.SDK_INT >= 21) {
            view.backgroundTintList = list
        } else {
            val color = list.getColorForState(intArrayOf(), Color.TRANSPARENT)
            view.background?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    /**
     * View.setOutlineProvider is API 21+. The provider class itself only exists from 21 on, so it
     * must be built inside the caller's version check — never passed in from outside.
     */
    fun setOutlineProvider(view: View, clip: Boolean, provider: () -> ViewOutlineProvider?): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        view.outlineProvider = provider() ?: return false
        view.clipToOutline = clip
        return true
    }
}
