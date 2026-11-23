package io.github.aurelia.nlauncher

import android.content.Context
import android.content.SharedPreferences

private const val PREFERENCES_NAME = "nlauncher"

abstract class Setting<T>(
    val key: String,
    private val default: (Context) -> T,
) {
    fun defaultValue(context: Context): T = default(context)

    abstract fun get(context: Context): T

    abstract fun set(
        context: Context,
        value: T,
    )

    protected fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    protected fun edit(
        context: Context,
        block: SharedPreferences.Editor.() -> Unit,
    ) {
        preferences(context).edit().apply(block).apply()
    }
}

/** A string setting; a missing value falls back to [default]. */
class SettingString(
    key: String,
    private val default: String? = null,
) : Setting<String?>(key, { default }) {
    override fun get(context: Context): String? = preferences(context).getString(key, null) ?: default

    override fun set(
        context: Context,
        value: String?,
    ) = edit(context) { putString(key, value) }
}

class SettingBool(
    key: String,
    private val default: Boolean,
) : Setting<Boolean>(key, { default }) {
    override fun get(context: Context): Boolean = preferences(context).getBoolean(key, default)

    override fun set(
        context: Context,
        value: Boolean,
    ) = edit(context) { putBoolean(key, value) }
}

open class SettingIntValue(
    key: String,
    default: (Context) -> Int,
) : Setting<Int>(key, default) {
    override fun get(context: Context): Int = preferences(context).getInt(key, defaultValue(context))

    override fun set(
        context: Context,
        value: Int,
    ) = edit(context) { putInt(key, value) }
}

class SettingInt(
    key: String,
    default: (Context) -> Int,
    val min: Int,
    val max: Int,
    val step: Int,
) : SettingIntValue(key, default) {
    constructor(
        key: String,
        default: Int,
        min: Int,
        max: Int,
        step: Int,
    ) : this(key, { default }, min, max, step)
}

/**
 * A color setting where no stored value means "unset". Removal is used rather than a
 * sentinel so clearing it is the same as never having picked one.
 */
class SettingNullableColor(
    key: String,
) : Setting<Int?>(key, { null }) {
    override fun get(context: Context): Int? = preferences(context).let { if (it.contains(key)) it.getInt(key, 0) else null }

    override fun set(
        context: Context,
        value: Int?,
    ) = edit(context) {
        if (value == null) remove(key) else putInt(key, value)
    }
}

class SettingFloat(
    key: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
) : Setting<Float>(key, { default }) {
    override fun get(context: Context): Float = preferences(context).getFloat(key, default)

    override fun set(
        context: Context,
        value: Float,
    ) = edit(context) { putFloat(key, value) }
}

// A list stored as newline-separated lines
open class SettingList<T>(
    key: String,
    default: (Context) -> List<T>,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
) : Setting<List<T>>(key, default) {
    private var parsedRaw: String? = null
    private var parsed: List<T> = emptyList()

    override fun get(context: Context): List<T> {
        val raw = preferences(context).getString(key, null) ?: return defaultValue(context)
        if (raw == parsedRaw) return parsed
        parsed = raw.split('\n').filter { it.isNotBlank() }.mapNotNull(decode)
        parsedRaw = raw
        return parsed
    }

    override fun set(
        context: Context,
        value: List<T>,
    ) = edit(context) { putString(key, value.joinToString("\n", transform = encode)) }
}

/** A list of plain strings, one per line. */
class SettingStringList(
    key: String,
    default: List<String> = emptyList(),
) : SettingList<String>(
        key = key,
        default = { default },
        encode = { it },
        decode = { it },
    )

class SettingGestureAction(
    gesture: Gesture,
    private val default: GestureAction,
) : Setting<GestureAction>("gesture_" + gesture.key, { default }) {
    override fun get(context: Context): GestureAction {
        val stored = preferences(context).getString(key, null) ?: return default
        return GestureAction.parse(stored)
    }

    override fun set(
        context: Context,
        value: GestureAction,
    ) = edit(context) { putString(key, value.serialize()) }
}
