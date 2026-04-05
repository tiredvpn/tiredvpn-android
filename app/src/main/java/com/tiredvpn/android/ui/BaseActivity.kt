package com.tiredvpn.android.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Base Activity that handles edge-to-edge display and WindowInsets properly.
 * All activities should extend this class to ensure content doesn't overlap
 * with status bar or navigation bar.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * Call this method after setContentView() with the root view of your layout.
     * This will apply proper padding to avoid status bar and navigation bar overlap.
     */
    protected fun setupWindowInsets(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }
    }
}
