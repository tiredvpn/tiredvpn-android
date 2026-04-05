package com.tiredvpn.android.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

object TvUtils {

    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isLargeScreen(context: Context): Boolean {
        val screenLayout = context.resources.configuration.screenLayout
        val screenSize = screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return screenSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }
}
