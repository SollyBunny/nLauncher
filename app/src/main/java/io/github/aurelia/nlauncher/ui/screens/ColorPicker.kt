package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.aurelia.nlauncher.R

fun showHslColorPicker(
    context: Context,
    initialColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 16)
    }

    val preview = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(context, 60),
        )
        setBackgroundColor(initialColor)
    }
    container.addView(preview)

    val hexInput = EditText(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setText(colorToHex(initialColor))
        setSingleLine(true)
    }
    container.addView(hexInput)

    val hsv = FloatArray(3)
    Color.colorToHSV(initialColor, hsv)

    var updatingHex = false
    lateinit var hueSeek: SeekBar
    lateinit var svGrid: ColorGridView

    val hexWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (updatingHex) return
            val text = s?.toString() ?: ""
            val hex = if (text.startsWith("#")) text else "#$text"
            val color = parseHex(hex)
            if (color != null) {
                Color.colorToHSV(color, hsv)
                preview.setBackgroundColor(color)
                svGrid.invalidateGrid()
                hueSeek.progress = hsv[0].toInt()
            }
        }
    }
    hexInput.addTextChangedListener(hexWatcher)

    val hueLabel = TextView(context).apply { setText(R.string.color_hue) }
    container.addView(hueLabel)
    hueSeek = SeekBar(context).apply {
        max = 359
        progress = hsv[0].toInt()
    }
    container.addView(hueSeek)

    val svLabel = TextView(context).apply { setText(R.string.color_saturation) }
    container.addView(svLabel)

    svGrid = ColorGridView(context, hsv) { color ->
        preview.setBackgroundColor(color)
        updatingHex = true
        hexInput.setText(colorToHex(color))
        hexInput.setSelection(hexInput.text.length)
        updatingHex = false
    }

    svGrid.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dpToPx(context, 200),
    )
    container.addView(svGrid)

    hueSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) {
                hsv[0] = p.toFloat()
                val color = Color.HSVToColor(hsv)
                preview.setBackgroundColor(color)
                updatingHex = true
                hexInput.setText(colorToHex(color))
                hexInput.setSelection(hexInput.text.length)
                updatingHex = false
                svGrid.invalidateGrid()
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    AlertDialog.Builder(context)
        .setTitle(R.string.text_color)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            onColorSelected(Color.HSVToColor(hsv))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private class ColorGridView(
    context: Context,
    private val hsv: FloatArray,
    private val onColorChanged: (Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            invalidateGrid()
        }
    }

    fun invalidateGrid() {
        val bm = bitmap ?: return
        val canvas = Canvas(bm)
        val w = bm.width.toFloat()
        val h = bm.height.toFloat()
        val base = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val satGradient = LinearGradient(0f, 0f, w, 0f, Color.WHITE, base, Shader.TileMode.CLAMP)
        val valGradient = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        val satPaint = Paint()
        satPaint.shader = satGradient
        canvas.drawRect(0f, 0f, w, h, satPaint)
        val valPaint = Paint()
        valPaint.shader = valGradient
        canvas.drawRect(0f, 0f, w, h, valPaint)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val x = hsv[1] * w
        val y = (1f - hsv[2]) * h
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpToPx(context, 2).toFloat()
        canvas.drawCircle(x, y, dpToPx(context, 6).toFloat(), paint)
        paint.color = Color.BLACK
        paint.strokeWidth = dpToPx(context, 1).toFloat()
        canvas.drawCircle(x, y, dpToPx(context, 6).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return true
            hsv[1] = (event.x / w).coerceIn(0f, 1f)
            hsv[2] = (1f - (event.y / h)).coerceIn(0f, 1f)
            onColorChanged(Color.HSVToColor(hsv))
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }
}

private fun dpToPx(context: Context, dp: Int): Int {
    return (dp * context.resources.displayMetrics.density).toInt()
}

private fun colorToHex(color: Int): String {
    return String.format("#%08X", color)
}

private fun parseHex(hex: String): Int? {
    return try {
        if (hex.startsWith("#")) {
            Color.parseColor(hex)
        } else {
            Color.parseColor("#$hex")
        }
    } catch (e: Exception) {
        null
    }
}
