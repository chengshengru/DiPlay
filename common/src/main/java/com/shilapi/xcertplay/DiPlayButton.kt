package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.core.graphics.ColorUtils

// main #594：按钮种类；PRIMARY 主操作、SECONDARY 次要、DANGER 危险操作（作者：TRAE AI）。
// 波次十一批次F（main #642）新增 TONAL_DANGER：柔和的危险色调（用于设置抽屉里靠后的"退出应用"）。
internal enum class DiPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Button for views built in code outside `DiPlayActivity`, whose own `button()` stays private.
 * The corner radius follows the control height, as in `DiPlayActivity.buttonShape`.
 *
 * 兼容说明（作者：TRAE AI）：RippleDrawable/stateListAnimator/foreground 分别需要 API 21/21/23，
 * 全部经由 UiCompat 守卫调用，Android 4.2.2（API 17）上退化为纯色背景且无前景焦点环。
 */
internal class DiPlayButton(context: Context) : Button(context) {
    private var kind = DiPlayButtonKind.SECONDARY
    private var palette: DiPlayPalette? = null
    private var overlay = false

    init {
        isAllCaps = false
        UiCompat.clearStateListAnimator(this)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** [overlay] picks the in-car menu colours instead of the app chrome colours. */
    fun style(kind: DiPlayButtonKind, palette: DiPlayPalette, overlay: Boolean) {
        this.kind = kind
        this.palette = palette
        this.overlay = overlay
        repaint()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) repaint()
    }

    private fun repaint() {
        val palette = palette ?: return
        val density = resources.displayMetrics.density
        val fill: Int
        val text: Int
        val stroke: Int
        when (kind) {
            DiPlayButtonKind.PRIMARY -> {
                fill = if (overlay) palette.overlayAccent else palette.accent
                text = if (overlay) palette.overlayOnAccent else palette.onAccent
                stroke = fill
            }
            DiPlayButtonKind.SECONDARY -> {
                fill = if (overlay) palette.overlayTrackOff else palette.button
                text = if (overlay) palette.overlayPrimaryText else palette.primaryText
                stroke = if (overlay) fill else palette.outline
            }
            DiPlayButtonKind.DANGER -> {
                fill = palette.overlayDanger
                text = Color.WHITE
                stroke = fill
            }
            DiPlayButtonKind.TONAL_DANGER -> {
                val surface = if (overlay) palette.overlayBackground else palette.surface
                fill = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.16f)
                text = if (ColorUtils.calculateLuminance(surface) < 0.5) {
                    ColorUtils.blendARGB(palette.overlayDanger, Color.WHITE, 0.40f)
                } else {
                    palette.overlayDanger
                }
                stroke = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.48f)
            }
        }
        val radius = minOf(24f * density, (if (height > 0) height else minHeight) * 20f / 56)
        setTextColor(text)
        background = UiCompat.ripple(
            palette.ripple,
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius
                setStroke((1 * density).toInt().coerceAtLeast(1), stroke)
            },
            null,
        )
        // Remote and D-pad users need to see where they are; touch mode never shows it.
        // API 23+ 才有 View.foreground，低版本跳过（作者：TRAE AI）。
        UiCompat.setForeground(this, StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
                setStroke((3 * density).toInt(), palette.focusRing)
            })
        })
    }
}
