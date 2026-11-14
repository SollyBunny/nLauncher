package io.github.aurelia.nlauncher.ui

import android.annotation.TargetApi
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToInt

/**
 * The icon with the same drop shadow as the text next to it: a blurred copy of the
 * icon drawn one pixel down and to the right, with the icon itself on top.
 *
 * The blur has to be baked into a bitmap. A [BlurMaskFilter] only affects what a
 * [Paint] draws, so it does nothing to a vector icon's own paths, and it is not
 * supported on a hardware-accelerated canvas anyway. Rendering the shadow to a small
 * bitmap once per size keeps the blur real and off the draw path.
 *
 * Usable from a drawable XML file, so every icon in a layout can carry the shadow:
 *
 * ```xml
 * <io.github.aurelia.nlauncher.ui.ShadowedIconDrawable
 *     xmlns:android="http://schemas.android.com/apk/res/android">
 *     <vector ... />
 * </io.github.aurelia.nlauncher.ui.ShadowedIconDrawable>
 * ```
 *
 * The same drawable is what [wrap] builds in code for icons that are not resources,
 * such as the app icons loaded at runtime.
 */
class ShadowedIconDrawable private constructor(
    initialIcon: Drawable,
) : Drawable() {
    /**
     * [DrawableInflater] builds custom drawables through a no-arg constructor and
     * then calls [inflate], so the real icon arrives from the XML element.
     */
    constructor() : this(ColorDrawable(Color.TRANSPARENT))

    private var icon: Drawable = initialIcon
    private var tint: ColorStateList? = null
    private var shadowBitmap: Bitmap? = null
    private var shadowWidth = -1
    private var shadowHeight = -1
    private var shadowPad = 0
    private var drawAlpha = 255
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?,
    ) {
        super.inflate(r, parser, attrs, theme)
        val a =
            theme?.obtainStyledAttributes(attrs, SOURCE_ATTRS, 0, 0)
                ?: r.obtainAttributes(attrs, SOURCE_ATTRS)
        val source: Drawable?
        val inflatedTint: ColorStateList?
        try {
            source = a.getDrawable(0)
            inflatedTint = a.getColorStateList(1)
        } finally {
            a.recycle()
        }
        // An icon file can name its source with android:src, or keep the source
        // drawable inside itself as the one child, so `@drawable/ic_*` is already
        // shadowed without a second file to wrap.
        val inflatedIcon = (source ?: childDrawable(r, parser, theme)).mutate()
        icon = inflatedIcon
        tint = inflatedTint
        icon.setTintList(inflatedTint)
    }

    /** The drawable nested inside the wrapper element, with the parser left on its end tag. */
    private fun childDrawable(
        r: Resources,
        parser: XmlPullParser,
        theme: Resources.Theme?,
    ): Drawable {
        var type = parser.next()
        while (type != XmlPullParser.START_TAG) {
            if (type == XmlPullParser.END_DOCUMENT) {
                throw IllegalArgumentException("ShadowedIconDrawable needs a source drawable")
            }
            type = parser.next()
        }
        val child = Drawable.createFromXmlInner(r, parser, Xml.asAttributeSet(parser), theme)
        // The wrapper holds one child; leave the parser on the wrapper's end tag.
        while (parser.next() != XmlPullParser.END_TAG) {
            // Ignore whitespace between the child and the wrapper's end tag.
        }
        return child
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val shadow = shadowBitmap(bounds.width(), bounds.height())
        shadowPaint.alpha = drawAlpha
        canvas.drawBitmap(
            shadow,
            bounds.left - shadowPad + SHADOW_OFFSET,
            bounds.top - shadowPad + SHADOW_OFFSET,
            shadowPaint,
        )
        icon.bounds = bounds
        icon.draw(canvas)
    }

    /**
     * The blurred shadow at [width] x [height], cached until the size changes. The
     * blur needs room to spread, so the bitmap carries [shadowPad] around the icon.
     *
     * The silhouette comes from the icon's shape, not its colors: the icon is drawn
     * untinted at full alpha, then recolored with [SHADOW_COLOR]. That keeps the
     * shadow the same whether the icon is tinted or not, and works for app icons that
     * cannot be copied.
     */
    private fun shadowBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        shadowBitmap?.let { if (width == shadowWidth && height == shadowHeight) return it }

        val pad = (SHADOW_BLUR_SIGMA * 3).roundToInt()
        val sharp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val previousAlpha = icon.alpha
        val previousFilter = icon.colorFilter
        icon.alpha = 255
        icon.setTintList(null)
        icon.colorFilter = null
        icon.bounds = Rect(0, 0, width, height)
        icon.draw(Canvas(sharp))
        icon.alpha = previousAlpha
        icon.setTintList(tint)
        icon.colorFilter = previousFilter

        val blurred =
            Bitmap.createBitmap(
                width + pad * 2,
                height + pad * 2,
                Bitmap.Config.ARGB_8888,
            )
        val blurPaint =
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(SHADOW_COLOR, PorterDuff.Mode.SRC_IN)
                maskFilter = BlurMaskFilter(SHADOW_BLUR_SIGMA, BlurMaskFilter.Blur.NORMAL)
            }
        val blurredCanvas = Canvas(blurred)
        blurredCanvas.drawBitmap(sharp, pad.toFloat(), pad.toFloat(), blurPaint)
        // Cut the icon's own shape out of the shadow, so a translucent icon keeps its
        // color instead of being darkened by the shadow behind it. The shadow sits one
        // pixel down and right, so the icon is one pixel up and left in this bitmap.
        val maskPaint =
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        blurredCanvas.drawBitmap(
            sharp,
            (pad - SHADOW_OFFSET).toFloat(),
            (pad - SHADOW_OFFSET).toFloat(),
            maskPaint,
        )
        sharp.recycle()

        shadowBitmap?.recycle()
        shadowBitmap = blurred
        shadowWidth = width
        shadowHeight = height
        shadowPad = pad
        return blurred
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tintList: ColorStateList?) {
        tint = tintList
        icon.setTintList(tintList)
        invalidateSelf()
    }

    override fun setVisible(
        visible: Boolean,
        restart: Boolean,
    ): Boolean {
        val changed = super.setVisible(visible, restart)
        if (icon.setVisible(visible, restart)) invalidateSelf()
        return changed
    }

    override fun isStateful(): Boolean = icon.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        val changed = icon.setState(state)
        if (changed) {
            dropShadow()
            invalidateSelf()
        }
        return changed
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        val changed = icon.setLayoutDirection(layoutDirection)
        if (changed) dropShadow()
        return changed
    }

    override fun jumpToCurrentState() {
        icon.jumpToCurrentState()
    }

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

    override fun getMinimumWidth(): Int = icon.minimumWidth

    override fun getMinimumHeight(): Int = icon.minimumHeight

    override fun getPadding(padding: Rect): Boolean = icon.getPadding(padding)

    private fun dropShadow() {
        shadowBitmap?.recycle()
        shadowBitmap = null
    }

    // https://stackoverflow.com/questions/65402823/deprecation-of-drawable-getopacity
    @Suppress("OVERRIDE_DEPRECATION")
    @TargetApi(Build.VERSION_CODES.Q)
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val SHADOW_OFFSET = 1f
        const val SHADOW_COLOR = 0x99000000.toInt()

        /**
         * The text's `setShadowLayer` radius. BlurMaskFilter takes a sigma, not a
         * radius, so convert the same way Skia does, or the icon blur is too strong.
         */
        private const val SHADOW_BLUR_RADIUS = 3f
        private const val SHADOW_BLUR_SIGMA = SHADOW_BLUR_RADIUS * 0.57735f + 0.5f

        /** How far the shadow reaches past the icon's bounds, in pixels. */
        val SHADOW_EXTENT_PX: Int =
            (SHADOW_BLUR_SIGMA * 3).roundToInt() + SHADOW_OFFSET.toInt()

        /** The XML attributes a wrapper drawable reads: its icon and its tint. */
        private val SOURCE_ATTRS = intArrayOf(android.R.attr.src, android.R.attr.tint)

        /** Wrap [drawable], or return null when there is no icon to shadow. */
        fun wrap(drawable: Drawable?): ShadowedIconDrawable? =
            when (drawable) {
                null -> null
                // Already shadowed, e.g. an ic_* drawable: wrapping it again would
                // draw a shadow behind a shadow.
                is ShadowedIconDrawable -> drawable
                else -> ShadowedIconDrawable(drawable.mutate())
            }
    }
}
