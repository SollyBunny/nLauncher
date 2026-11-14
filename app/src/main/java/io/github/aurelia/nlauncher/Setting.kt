package io.github.aurelia.nlauncher

import android.content.Context
import android.content.SharedPreferences

private const val PREFERENCES_NAME = "nlauncher"

abstract class Setting<T>(
    val key: String,
) {
    abstract fun defaultValue(context: Context): T

    abstract fun get(context: Context): T

    abstract fun set(
        context: Context,
        value: T,
    )

    protected fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

/** A string setting. [rejectBlank] treats a stored whitespace-only value as unset. */
class SettingString(
    key: String,
    private val default: String? = null,
) : Setting<String?>(key) {
    override fun defaultValue(context: Context): String? = default

    override fun get(context: Context) = preferences(context).getString(key, null) ?: default

    override fun set(
        context: Context,
        value: String?,
    ) {
        preferences(context).edit().putString(key, value).apply()
    }
}

class SettingBool(
    key: String,
    private val default: Boolean,
) : Setting<Boolean>(key) {
    override fun defaultValue(context: Context): Boolean = default

    override fun get(context: Context): Boolean = preferences(context).getBoolean(key, default)

    override fun set(
        context: Context,
        value: Boolean,
    ) {
        preferences(context).edit().putBoolean(key, value).apply()
    }
}

abstract class SettingIntValue(
    key: String,
    private val default: (Context) -> Int,
) : Setting<Int>(key) {
    override fun defaultValue(context: Context): Int = default(context)

    override fun get(context: Context): Int = preferences(context).getInt(key, defaultValue(context))

    override fun set(
        context: Context,
        value: Int,
    ) {
        preferences(context).edit().putInt(key, value).apply()
    }
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

class SettingColor(
    key: String,
    default: (Context) -> Int,
) : SettingIntValue(key, default)

class SettingFloat(
    key: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
) : Setting<Float>(key) {
    override fun defaultValue(context: Context): Float = default

    override fun get(context: Context): Float = preferences(context).getFloat(key, default)

    override fun set(
        context: Context,
        value: Float,
    ) {
        preferences(context).edit().putFloat(key, value).apply()
    }
}

// A list stored as newline-separated lines
open class SettingList<T>(
    key: String,
    private val default: (Context) -> List<T>,
    private val encode: (T) -> String,
    private val decode: (String) -> T?,
) : Setting<List<T>>(key) {
    private var parsedRaw: String? = null
    private var parsed: List<T> = emptyList()

    override fun defaultValue(context: Context): List<T> = default(context)

    override fun get(context: Context): List<T> {
        val raw = preferences(context).getString(key, null) ?: return default(context)
        if (raw == parsedRaw) return parsed
        parsed = raw.split('\n').filter { it.isNotBlank() }.mapNotNull(decode)
        parsedRaw = raw
        return parsed
    }

    override fun set(
        context: Context,
        value: List<T>,
    ) {
        preferences(context).edit().putString(key, value.joinToString("\n", transform = encode)).apply()
    }
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
) : Setting<GestureAction>("gesture_" + gesture.key) {
    override fun defaultValue(context: Context): GestureAction = default

    override fun get(context: Context): GestureAction {
        val stored = preferences(context).getString(key, null) ?: return default
        return GestureAction.parse(stored)
    }

    override fun set(
        context: Context,
        value: GestureAction,
    ) {
        preferences(context).edit().putString(key, value.serialize()).apply()
    }
}
