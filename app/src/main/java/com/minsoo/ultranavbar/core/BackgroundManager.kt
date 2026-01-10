package com.minsoo.ultranavbar.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil


class BackgroundManager(
    private val context: Context,
    private val listener: BackgroundChangeListener
) {
    companion object {
        private const val TAG = "BackgroundManager"
    }

    
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var bgAnimator: ValueAnimator? = null

    private val settings: SettingsManager = SettingsManager.getInstance(context)

    
    private var landscapeBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    
    private var _isDarkMode: Boolean = false
    val isDarkMode: Boolean get() = _isDarkMode

    private var _currentButtonColor: Int = Color.WHITE
    val currentButtonColor: Int get() = _currentButtonColor

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    
    interface BackgroundChangeListener {
        fun onButtonColorChanged(color: Int)
        fun onBackgroundApplied(drawable: Drawable)
    }

    

    
    fun initializeDarkMode() {
        _isDarkMode = isSystemDarkMode()
        _currentButtonColor = getDefaultButtonColor()
    }

    
    fun initializeOrientation(orientation: Int) {
        currentOrientation = orientation
    }

    

    
    fun loadBackgroundBitmaps(forceReload: Boolean = false) {
        if (!settings.homeBgEnabled) {
            recycleBitmaps()
            return
        }

        
        if (!forceReload && landscapeBitmap != null && portraitBitmap != null) {
            Log.d(TAG, "Background bitmaps already loaded, skipping reload")
            return
        }

        
        recycleBitmaps()

        landscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true)
        portraitBitmap = ImageCropUtil.loadBackgroundBitmap(context, false)

        Log.d(TAG, "Background bitmaps loaded: landscape=${landscapeBitmap?.hashCode()}, portrait=${portraitBitmap?.hashCode()}")
    }

    
    private fun recycleBitmaps() {
        landscapeBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        landscapeBitmap = null

        portraitBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        portraitBitmap = null
    }

    
    fun getCurrentBitmap(): Bitmap? {
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        val bitmap = if (isLandscape) landscapeBitmap else portraitBitmap
        Log.d(TAG, "getCurrentBitmap: orientation=${getOrientationName(currentOrientation)}, returning ${if (isLandscape) "landscape" else "portrait"} bitmap (hash=${bitmap?.hashCode()})")
        return bitmap
    }

    
    fun hasBitmaps(): Boolean {
        return landscapeBitmap != null || portraitBitmap != null
    }

    

    
    fun handleOrientationChange(newOrientation: Int): Boolean {
        if (currentOrientation == newOrientation) return false

        Log.d(TAG, "Orientation changed: ${getOrientationName(currentOrientation)} -> ${getOrientationName(newOrientation)}")
        currentOrientation = newOrientation
        return true
    }

    
    fun syncOrientationWithSystem(): Boolean {
        val actualOrientation = context.resources.configuration.orientation
        if (currentOrientation != actualOrientation) {
            Log.w(TAG, "Orientation mismatch! cached=$currentOrientation, actual=$actualOrientation - resyncing")
            currentOrientation = actualOrientation
            
            return true
        }
        return false
    }

    
    fun forceOrientationSync(orientation: Int) {
        Log.d(TAG, "Force orientation sync: ${getOrientationName(currentOrientation)} -> ${getOrientationName(orientation)}")
        currentOrientation = orientation
    }

    private fun getOrientationName(orientation: Int): String {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
    }

    

    
    private fun isSystemDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    
    fun updateDarkMode(): Boolean {
        val newDarkMode = isSystemDarkMode()
        if (_isDarkMode != newDarkMode) {
            _isDarkMode = newDarkMode
            val newButtonColor = getDefaultButtonColor()
            _currentButtonColor = newButtonColor
            
            listener.onButtonColorChanged(newButtonColor)
            Log.d(TAG, "Dark mode changed: $_isDarkMode, button color: ${getColorName(newButtonColor)}")
            return true
        }
        return false
    }

    

    
    fun getDefaultBackgroundColor(): Int {
        return if (_isDarkMode) Color.BLACK else Color.WHITE
    }

    
    fun getDefaultButtonColor(): Int {
        return if (_isDarkMode) Color.WHITE else Color.DKGRAY
    }

    
    fun calculateButtonColorForBitmap(bitmap: Bitmap): Int {
        val sampleSize = Constants.Threshold.LUMINANCE_SAMPLE_SIZE
        val width = bitmap.width
        val height = bitmap.height

        var totalLuminance = 0.0
        var sampleCount = 0

        val stepX = maxOf(1, width / sampleSize)
        val stepY = maxOf(1, height / sampleSize)

        var x = 0
        while (x < width) {
            var y = 0
            while (y < height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                totalLuminance += luminance
                sampleCount++
                y += stepY
            }
            x += stepX
        }

        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else Constants.Threshold.BRIGHTNESS_THRESHOLD

        return if (avgLuminance > Constants.Threshold.BRIGHTNESS_THRESHOLD) Color.BLACK else Color.WHITE
    }

    
    fun updateButtonColor(color: Int) {
        if (_currentButtonColor != color) {
            _currentButtonColor = color
            listener.onButtonColorChanged(color)
            Log.d(TAG, "Button color updated: ${getColorName(color)}")
        }
    }

    private fun getColorName(color: Int): String {
        return when (color) {
            Color.WHITE -> "WHITE"
            Color.BLACK -> "BLACK"
            Color.DKGRAY -> "DARK_GRAY"
            else -> "0x${Integer.toHexString(color)}"
        }
    }

    

    
    fun shouldUseCustomBackground(isOnHomeScreen: Boolean, isRecentsVisible: Boolean): Boolean {
        return isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible
    }

    
    fun applyBackground(targetView: View, useCustom: Boolean, forceUpdate: Boolean = false) {
        val currentBg = targetView.background
        val defaultBgColor = getDefaultBackgroundColor()

        if (forceUpdate) {
            cancelBackgroundTransition()
        }

        if (useCustom) {
            applyCustomBackground(targetView, currentBg, defaultBgColor, forceUpdate)
        } else {
            applyDefaultBackground(targetView, currentBg, defaultBgColor, forceUpdate)
        }
    }

    private fun applyCustomBackground(
        targetView: View,
        currentBg: Drawable?,
        defaultBgColor: Int,
        forceUpdate: Boolean
    ) {
        val targetBitmap = getCurrentBitmap()

        if (targetBitmap != null) {
            val currentBitmap = (currentBg as? BitmapDrawable)?.bitmap
            if (forceUpdate || currentBitmap !== targetBitmap) {
                Log.d(TAG, "Applying custom background image")

                val bgDrawable = BitmapDrawable(context.resources, targetBitmap).apply {
                    gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                }

                
                val buttonColor = calculateButtonColorForBitmap(targetBitmap)
                updateButtonColor(buttonColor)

                
                val needsFade = !forceUpdate && (currentBg is ColorDrawable || currentBg?.alpha == 0)
                if (needsFade) {
                    bgDrawable.alpha = 0
                    targetView.background = bgDrawable
                    animateBackgroundAlpha(bgDrawable, 0, 255)
                } else {
                    bgDrawable.alpha = 255
                    targetView.background = bgDrawable
                }

                listener.onBackgroundApplied(bgDrawable)
            }
        } else {
            
            if (forceUpdate || (currentBg as? ColorDrawable)?.color != defaultBgColor) {
                Log.d(TAG, "Fallback to default background (bitmap not loaded)")
                targetView.background = ColorDrawable(defaultBgColor)
                updateButtonColor(getDefaultButtonColor())
            }
        }
    }

    private fun applyDefaultBackground(targetView: View, currentBg: Drawable?, defaultBgColor: Int, forceUpdate: Boolean) {
        updateButtonColor(getDefaultButtonColor())

        val isCurrentlyImage = currentBg is BitmapDrawable && currentBg.alpha > 0
        if (isCurrentlyImage && !forceUpdate) {
            Log.d(TAG, "Transitioning from image to default background")
            
            animateBackgroundAlpha(currentBg as BitmapDrawable, 255, 0) {
                val defaultDrawable = ColorDrawable(defaultBgColor)
                targetView.background = defaultDrawable
                listener.onBackgroundApplied(defaultDrawable)
            }
        } else if (forceUpdate || (currentBg as? ColorDrawable)?.color != defaultBgColor) {
            val defaultDrawable = ColorDrawable(defaultBgColor)
            targetView.background = defaultDrawable
            listener.onBackgroundApplied(defaultDrawable)
        }
    }

    private fun animateBackgroundAlpha(
        drawable: BitmapDrawable,
        fromAlpha: Int,
        toAlpha: Int,
        onEnd: (() -> Unit)? = null
    ) {
        bgAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.cancel()
        }
        bgAnimator = ValueAnimator.ofInt(fromAlpha, toAlpha).apply {
            duration = Constants.Timing.BG_TRANSITION_DURATION_MS
            interpolator = android12Interpolator
            addUpdateListener { animation ->
                drawable.alpha = animation.animatedValue as Int
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var wasCancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        wasCancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!wasCancelled) {
                            onEnd()
                        }
                    }
                })
            }
            start()
        }
    }

    
    fun cancelBackgroundTransition() {
        bgAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.cancel()
        }
        bgAnimator = null
    }

    

    
    fun cleanup() {
        bgAnimator?.cancel()
        bgAnimator = null
        recycleBitmaps()
    }
}
