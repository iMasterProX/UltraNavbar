package com.minsoo.ultranavbar.util

import android.content.Context
import android.content.res.Configuration

object DeviceProfile {
    private const val TABLET_SMALLEST_WIDTH_DP = 600

    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        val smallestWidthDp = config.smallestScreenWidthDp
        if (smallestWidthDp == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
            val screenLayout = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
            return screenLayout >= Configuration.SCREENLAYOUT_SIZE_LARGE
        }
        return smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP
    }
}
