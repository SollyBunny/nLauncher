package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.dpToPx
import java.util.Locale

/**
 * A colour picker shaped like the rest of the settings: the platform alert dialog
 * (square, like every other dialog here) with a live preview, a saturation/value
 * square and a hue bar. The only hex shown is the editable one, and colours are
 * always opaque — there is no alpha control.
 */
fun showColorPicker(
    context: Context,
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    val content = View.inflate(context, R.layout.dialog_color_picker, null)
    val preview = content.findViewById<View>(R.id.color_preview)
    val previewText = content.findViewById<TextView>(R.id.color_preview_text)
    val saturationValue = content.findViewById<ColorSaturationValueView>(R.id.color_saturation_value)
    val hueBar = content.findViewById<ColorHueView>(R.id.color_hue_bar)
    val hexInput = content.findViewById<EditText>(R.id.color_hex)

    val hsv = FloatArray(3)
    // Text colours are opaque here. An unset (or fully transparent) colour falls back
    // to white, the launcher's default text colour.
    val opaque =
        if (Color.alpha(initialColor) == 0) {
            Color.WHITE
        } else {
            initialColor or OPAQUE
        }
    Color.colorToHSV(opaque, hsv)

    var updatingHex = false

    fun renderPreview(color: Int) {
        preview.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f.dpToPx(context).toFloat()
                setColor(color)
                setStroke(1f.dpToPx(context), 0x33FFFFFF)
            }
        previewText.setTextColor(contrastColor(color))
    }

    fun showHex(color: Int) {
        val hex = colorToHex(color)
        if (hexInput.text.toString() == hex) return
        updatingHex = true
        hexInput.setText(hex)
        hexInput.setSelection(hex.length)
        updatingHex = false
    }

    fun update(color: Int) {
        renderPreview(color)
        showHex(color)
    }

    saturationValue.hsv = hsv
    hueBar.hsv = hsv

    saturationValue.onColorChanged = { color -> update(color) }
    hueBar.onColorChanged = { color ->
        saturationValue.refresh()
        update(color)
    }

    hexInput.addTextChangedListener(
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val color = parseHex(s?.toString().orEmpty()) ?: return
                Color.colorToHSV(color, hsv)
                renderPreview(color)
                saturationValue.refresh()
                hueBar.refresh()
            }
        },
    )

    hexInput.setOnFocusChangeListener { _, hasFocus ->
        if (!hasFocus) {
            parseHex(hexInput.text.toString())?.let { color ->
                Color.colorToHSV(color, hsv)
                saturationValue.refresh()
                hueBar.refresh()
            }
            // Whether the text was valid or not, put the field back in its canonical
            // form: six opaque digits, so nothing about alpha ever reaches the user.
            update(Color.HSVToColor(hsv))
        }
    }

    hexInput.setOnEditorActionListener { view, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        view.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        true
    }

    update(opaque)

    val dialog =
        AlertDialog
            .Builder(context)
            .setTitle(R.string.text_color)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onColorSelected(Color.HSVToColor(hsv))
            }.setNegativeButton(android.R.string.cancel, null)
            .create()

    // The field is only there to be tapped; opening the dialog should not pop the
    // keyboard over the picker.
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.show()
}

/**
 * The saturation/value square. Its bitmap is painted once per hue instead of once per
 * frame, so dragging the selector only redraws the marker.
 */
class ColorSaturationValueView(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var hsv: FloatArray = floatArrayOf(0f, 0f, 1f)
        set(value) {
            field = value
            refresh()
        }

    var onColorChanged: ((Int) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
    private val path = Path()
    private val rect = RectF()
    private var bitmap: Bitmap? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap?.recycle()
        bitmap =
            if (w > 0 && h > 0) {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
        refresh()
    }

    fun refresh() {
        val bitmap = bitmap ?: return
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val base = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))

        fillPaint.shader =
            LinearGradient(0f, 0f, w, 0f, Color.WHITE, base, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        fillPaint.shader =
            LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = 8f.dpToPx(context).toFloat()
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.restore()

        strokePaint.color = 0x33FFFFFF
        strokePaint.strokeWidth = 1f.dpToPx(context).toFloat()
        canvas.drawPath(path, strokePaint)

        val x = hsv[1] * w
        val y = (1f - hsv[2]) * h
        val markerRadius = 9f.dpToPx(context).toFloat()
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = 4f.dpToPx(context).toFloat()
        canvas.drawCircle(x, y, markerRadius, strokePaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f.dpToPx(context).toFloat()
        canvas.drawCircle(x, y, markerRadius, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFrom(event)
            }
            MotionEvent.ACTION_MOVE -> updateFrom(event)
            MotionEvent.ACTION_UP -> {
                updateFrom(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFrom(event: MotionEvent) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        hsv[1] = (event.x / w).coerceIn(0f, 1f)
        hsv[2] = (1f - event.y / h).coerceIn(0f, 1f)
        onColorChanged?.invoke(Color.HSVToColor(hsv))
        invalidate()
    }
}

/** The hue bar: a rainbow strip with a white thumb, instead of a plain SeekBar. */
class ColorHueView(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var hsv: FloatArray = floatArrayOf(0f, 0f, 1f)

    var onColorChanged: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
    private val path = Path()
    private val rect = RectF()
    private val thumbRect = RectF()
    private val thumbWidth = 12f.dpToPx(context).toFloat()
    private var shader: Shader? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun refresh() = invalidate()

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        val colors =
            intArrayOf(
                Color.HSVToColor(floatArrayOf(0f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(60f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(120f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(180f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(240f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(300f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf(360f, 1f, 1f)),
            )
        shader =
            if (w > 0) {
                LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
            } else {
                null
            }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        paint.shader = shader
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, paint)
        canvas.restore()
        paint.shader = null

        strokePaint.color = 0x33FFFFFF
        strokePaint.strokeWidth = 1f.dpToPx(context).toFloat()
        canvas.drawPath(path, strokePaint)

        val x = thumbX(hsv[0])
        val inset = 2f.dpToPx(context).toFloat()
        thumbRect.set(x - thumbWidth / 2f, inset, x + thumbWidth / 2f, h - inset)
        val thumbRadius = thumbWidth / 2f
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0x66000000
        paint.strokeWidth = 1f.dpToPx(context).toFloat()
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFrom(event)
            }
            MotionEvent.ACTION_MOVE -> updateFrom(event)
            MotionEvent.ACTION_UP -> {
                updateFrom(event)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFrom(event: MotionEvent) {
        val usable = width - thumbWidth
        if (usable <= 0f) return
        hsv[0] = ((event.x - thumbWidth / 2f) / usable).coerceIn(0f, 1f) * 360f
        onColorChanged?.invoke(Color.HSVToColor(hsv))
        invalidate()
    }

    private fun thumbX(hue: Float): Float {
        val usable = (width - thumbWidth).coerceAtLeast(0f)
        return thumbWidth / 2f + hue / 360f * usable
    }
}

private const val OPAQUE = 0xFF000000.toInt()

private fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

/**
 * Six digits are the form shown, but eight are accepted too so a value pasted from
 * somewhere that includes alpha still lands on a usable opaque colour.
 */
private fun parseHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    val digits = if (hex.length == 8) hex.takeLast(6) else hex
    if (digits.length != 6 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (OPAQUE or digits.toLong(16).toInt())
}

/** Black or white, whichever is readable on [color]. */
private fun contrastColor(color: Int): Int {
    val luminance =
        0.299f * Color.red(color) / 255f +
            0.587f * Color.green(color) / 255f +
            0.114f * Color.blue(color) / 255f
    return if (luminance > 0.6f) Color.BLACK else Color.WHITE
}
