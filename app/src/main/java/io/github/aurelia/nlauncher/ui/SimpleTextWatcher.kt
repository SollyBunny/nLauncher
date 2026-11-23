package io.github.aurelia.nlauncher.ui

import android.text.Editable
import android.text.TextWatcher

/** A [TextWatcher] that only overrides the callbacks it needs; this app has no AndroidX KTX. */
interface SimpleTextWatcher : TextWatcher {
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

    override fun afterTextChanged(s: Editable?) = Unit
}
