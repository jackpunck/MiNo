package com.mino.app

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only empty host: picker checks cannot read or change user tasks. */
class PickerTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
