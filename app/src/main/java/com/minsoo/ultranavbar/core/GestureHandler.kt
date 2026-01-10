package com.minsoo.ultranavbar.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.minsoo.ultranavbar.settings.SettingsManager


class GestureHandler(
    private val context: Context,
    private val listener: GestureListener
) {
    companion object {
        private const val TAG = "GestureHandler"
    }

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val swipeThresholdPx: Int

    
    private var _isGestureShown: Boolean = false
    val isGestureShown: Boolean get() = _isGestureShown

    private var gestureShowTime: Long = 0
    private var gestureAutoHideRunnable: Runnable? = null

    
    private var touchStartY: Float = 0f

    init {
        swipeThresholdPx = context.dpToPx(Constants.Dimension.SWIPE_THRESHOLD_DP)
    }

    
    interface GestureListener {
        fun onSwipeUpDetected()
        fun onSwipeDownDetected()
        fun onGestureAutoHide()
        fun onGestureOverlayTapped()  
    }

    

    
    fun setupHotspotTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            if (shouldIgnoreTouch(event)) {
                return@setOnTouchListener false
            }
            handleHotspotTouch(event)
        }
    }

    private fun handleHotspotTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = touchStartY - event.rawY 
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Hotspot swipe up detected")
                    listener.onSwipeUpDetected()
                    touchStartY = event.rawY 
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val deltaY = touchStartY - event.rawY
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Hotspot swipe up on release")
                    listener.onSwipeUpDetected()
                }
                return true
            }
        }
        return false
    }

    

    
    fun setupGestureOverlayTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            handleGestureOverlayTouch(event)
        }
    }

    private fun handleGestureOverlayTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - touchStartY 
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Gesture overlay swipe down detected")
                    listener.onSwipeDownDetected()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val deltaY = event.rawY - touchStartY
                
                if (deltaY < swipeThresholdPx) {
                    Log.d(TAG, "Gesture overlay tapped, requesting hide")
                    listener.onGestureOverlayTapped()
                }
                return true
            }
        }
        return false
    }

    

    private fun shouldIgnoreTouch(event: MotionEvent): Boolean {
        return settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
    }

    
    fun setupButtonTouchListener(button: View) {
        button.setOnTouchListener { _, event ->
            if (shouldIgnoreTouch(event)) {
                return@setOnTouchListener true 
            }
            false 
        }
    }

    

    
    fun markGestureShow() {
        _isGestureShown = true
        gestureShowTime = SystemClock.elapsedRealtime()
        scheduleAutoHide()
    }

    
    fun hideGestureOverlay() {
        _isGestureShown = false
        cancelAutoHide()
    }

    
    fun getGestureElapsedTime(): Long {
        return SystemClock.elapsedRealtime() - gestureShowTime
    }

    

    private fun scheduleAutoHide() {
        cancelAutoHide()
        gestureAutoHideRunnable = Runnable {
            if (_isGestureShown) {
                Log.d(TAG, "Gesture auto-hide triggered after ${Constants.Timing.GESTURE_AUTO_HIDE_MS}ms")
                listener.onGestureAutoHide()
                _isGestureShown = false
            }
        }
        handler.postDelayed(gestureAutoHideRunnable!!, Constants.Timing.GESTURE_AUTO_HIDE_MS)
    }

    private fun cancelAutoHide() {
        gestureAutoHideRunnable?.let { handler.removeCallbacks(it) }
        gestureAutoHideRunnable = null
    }

    

    
    fun canAutoHide(): Boolean {
        if (!_isGestureShown) return true

        val elapsed = getGestureElapsedTime()
        return elapsed > Constants.Timing.GESTURE_AUTO_HIDE_MS
    }

    

    fun cleanup() {
        cancelAutoHide()
    }
}
