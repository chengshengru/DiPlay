package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.UiCompat
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

object SettingsWidgets {

    /**
     * 把平台 Switch（约 36x14dp 轨道）替换为 60x34dp 轨道 + 28dp 圆形拇指，
     * 保证车载远距离可读（对应 main #420 大开关样式）。
     *
     * SwitchCompat 会把拇指/轨道拉伸到整个视图高度：设置页里 Switch 曾带 56dp
     * minHeight、画中画面板里容器强制 48dp，拇指因此被拉成椭圆、轨道圆角走形。
     * 改用自绘 Drawable：拇指恒取 bounds 短边画正圆并居中，轨道 radius 恒取
     * 高度一半画胶囊，任意容器高度下形状都不变。
     *
     * 颜色直接在 Drawable 内按 view state 解析（禁用/选中/默认三态），不依赖
     * tint list；state 直接向 SwitchCompat 取，因为 SwitchCompat 只对 isStateful()
     * 的 drawable 下传 state，一旦漏传就会整只开关都画成禁用色。
     */
    fun applyLargeSwitchStyle(switch: SwitchCompat, thumbOn: Int, thumbOff: Int, trackOn: Int, trackOff: Int) {
        val density = switch.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        val state = { switch.drawableState }
        switch.thumbDrawable = CircleThumbDrawable(px(28), switchColorStates(thumbOn, thumbOff), state)
        switch.trackDrawable = PillTrackDrawable(px(60), px(34), switchColorStates(trackOn, trackOff), state)
        switch.switchMinWidth = px(60)
        switch.showText = false
    }

    /** 开关拇指/轨道的三态颜色：禁用（38% 透明）、选中、默认。 */
    private fun switchColorStates(on: Int, off: Int) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(ColorUtils.setAlphaComponent(off, 0x61), on, off),
    )

    /** 拇指：恒为正圆（直径取 bounds 短边、居中绘制），容器拉高也不会变成椭圆。 */
    internal class CircleThumbDrawable(
        val size: Int,
        val colors: ColorStateList,
        private val stateProvider: () -> IntArray,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val bounds = getBounds()
            if (bounds.isEmpty) return
            paint.color = colors.getColorForState(stateProvider(), colors.defaultColor)
            val radius = minOf(bounds.width(), bounds.height()) / 2f
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        }

        override fun onStateChange(state: IntArray): Boolean {
            invalidateSelf()
            return true
        }

        override fun isStateful(): Boolean = true

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = size

        override fun getIntrinsicHeight(): Int = size
    }

    /** 轨道：radius 恒取高度一半画全圆角胶囊，容器拉高也不会变成圆角矩形。 */
    internal class PillTrackDrawable(
        val width: Int,
        val height: Int,
        val colors: ColorStateList,
        private val stateProvider: () -> IntArray,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val bounds = getBounds()
            if (bounds.isEmpty) return
            paint.color = colors.getColorForState(stateProvider(), colors.defaultColor)
            rect.set(bounds)
            val radius = bounds.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun onStateChange(state: IntArray): Boolean {
            invalidateSelf()
            return true
        }

        override fun isStateful(): Boolean = true

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = width

        override fun getIntrinsicHeight(): Int = height
    }

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: SwitchCompat,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = if (theme.isOverlay) 16f else 22f
        setTextColor(if (theme.isOverlay) theme.accent else theme.textPrimary)
        applyTextStyle(context, theme, bold = true)
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = SwitchCompat(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            // main 9c7fda97：全部开关统一用 60x34dp 大开关样式，远距离可读（作者：TRAE AI）。
            // 拇指统一白色：开/关状态由轨道颜色（accentTrack vs trackOff）+ 拇指位置区分，
            // 白色拇指在两种轨道上都清晰可见。
            applyLargeSwitchStyle(this, Color.WHITE, Color.WHITE, theme.accentTrack, theme.trackOff)
            // SwitchCompat 会把 Drawable 拉伸到视图高度，minHeight 会把拇指拉成椭圆；
            // 56dp 触控目标高度改由 padding 承担，视图高度仍由 34dp Drawable 决定。
            if (!theme.isOverlay) setPadding(0, theme.dp(context, 11), 0, theme.dp(context, 11))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 14f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 6), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            row.background = UiCompat.ripple(ROW_RIPPLE, null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            UiCompat.setForeground(row, android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.accent)
                })
            })
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
            clampDescription(descView)
        }

        return SwitchRowResult(row, switch)
    }

    /** Long help stays two lines until tapped; screen readers still get the full text. */
    private fun clampDescription(view: TextView) {
        view.maxLines = COLLAPSED_DESCRIPTION_LINES
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.post {
            if ((view.layout?.getEllipsisCount(view.lineCount - 1) ?: 0) == 0) return@post
            view.setOnClickListener {
                view.maxLines = Int.MAX_VALUE
                view.ellipsize = null
                view.setOnClickListener(null)
                view.isClickable = false
            }
        }
    }

    private const val COLLAPSED_DESCRIPTION_LINES = 2
    private const val ROW_RIPPLE = 0x336F9FD9

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                UiCompat.setButtonTint(this, ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                ))
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            UiCompat.setSeekBarTints(this, theme.accent)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
