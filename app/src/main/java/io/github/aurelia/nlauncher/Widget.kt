package io.github.aurelia.nlauncher

/**
 * Something the home screen can show that is not an app: the clock, the date. A
 * widget is just its type — there is no per-instance data — so the type doubles as
 * the model, and the order they are stored in is what makes them swappable.
 *
 * The views live in [io.github.aurelia.nlauncher.ui.WidgetViews], so the home screen and the
 * editor's preview are built from exactly the same code.
 */
enum class Widget(
    val key: String,
) {
    CLOCK("clock"),
    DATE("date"),
    ALARM("alarm"),
    ;

    companion object {
        /** The widget for a stored key, or null for anything written by a newer version. */
        fun fromKey(key: String): Widget? = entries.firstOrNull { it.key == key }
    }
}
