package com.minsoo.ultranavbar.core

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import kotlin.math.max
import kotlin.math.roundToLong

object AnimationPerformanceHelper {
    private const val TAG = "AnimPerfHelper"

    enum class QualityTier(
        val frameDelayMs: Long,
        val durationScale: Float,
        val allowHardwareLayers: Boolean,
        val simplifyBackgroundTransitions: Boolean,
        val skipNonEssentialAnimations: Boolean
    ) {
        HIGH(
            frameDelayMs = Constants.Performance.HIGH_QUALITY_FRAME_DELAY_MS,
            durationScale = 1.0f,
            allowHardwareLayers = true,
            simplifyBackgroundTransitions = false,
            skipNonEssentialAnimations = false
        ),
        MEDIUM(
            frameDelayMs = Constants.Performance.MEDIUM_QUALITY_FRAME_DELAY_MS,
            durationScale = 0.95f,
            allowHardwareLayers = false,
            simplifyBackgroundTransitions = false,
            skipNonEssentialAnimations = false
        ),
        LOW(
            frameDelayMs = Constants.Performance.LOW_QUALITY_FRAME_DELAY_MS,
            durationScale = 0.85f,
            allowHardwareLayers = false,
            simplifyBackgroundTransitions = true,
            skipNonEssentialAnimations = false
        ),
        MIN(
            frameDelayMs = Constants.Performance.MIN_QUALITY_FRAME_DELAY_MS,
            durationScale = 0.72f,
            allowHardwareLayers = false,
            simplifyBackgroundTransitions = true,
            skipNonEssentialAnimations = false
        )
    }

    @Volatile
    private var globalSettingsApplied = false

    @Volatile
    private var monitorStarted = false

    @Volatile
    private var currentTier = QualityTier.MEDIUM

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameSamples = LongArray(Constants.Performance.FRAME_SAMPLE_WINDOW_SIZE)

    private var lastFrameTimeNs: Long = 0L
    private var sampleCount = 0
    private var sampleIndex = 0
    private var severeFrameStreak = 0
    private var stableFrameStreak = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onFrame(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun applyGlobalSettings() {
        if (globalSettingsApplied || !Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return
        globalSettingsApplied = true
        startMonitor()
    }

    fun resolveDuration(baseDurationMs: Long): Long {
        if (!Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return baseDurationMs
        val tier = currentTier
        return max(
            Constants.Performance.MIN_ANIMATION_DURATION_MS,
            (baseDurationMs * tier.durationScale).roundToLong()
        )
    }

    fun shouldUseHardwareLayers(): Boolean {
        if (!Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return true
        return currentTier.allowHardwareLayers
    }

    fun shouldSimplifyBackgroundTransitions(): Boolean {
        if (!Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return false
        return currentTier.simplifyBackgroundTransitions
    }

    fun shouldSkipNonEssentialAnimations(): Boolean {
        if (!Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return false
        return currentTier.skipNonEssentialAnimations
    }

    fun getCurrentTier(): QualityTier = currentTier

    private fun startMonitor() {
        if (monitorStarted) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startMonitorOnMain()
        } else {
            mainHandler.post { startMonitorOnMain() }
        }
    }

    private fun startMonitorOnMain() {
        if (monitorStarted) return
        monitorStarted = true
        ValueAnimator.setFrameDelay(currentTier.frameDelayMs)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNs != 0L) {
            val deltaMs = ((frameTimeNanos - lastFrameTimeNs) / 1_000_000L).coerceAtLeast(0L)
            recordSample(deltaMs)
            updateTier(deltaMs)
        }
        lastFrameTimeNs = frameTimeNanos
    }

    private fun recordSample(frameDeltaMs: Long) {
        frameSamples[sampleIndex] = frameDeltaMs
        sampleIndex = (sampleIndex + 1) % frameSamples.size
        if (sampleCount < frameSamples.size) {
            sampleCount++
        }
    }

    private fun averageFrameMs(): Long {
        if (sampleCount == 0) return currentTier.frameDelayMs
        var sum = 0L
        for (i in 0 until sampleCount) {
            sum += frameSamples[i]
        }
        return sum / sampleCount
    }

    private fun updateTier(frameDeltaMs: Long) {
        val avgFrameMs = averageFrameMs()

        if (frameDeltaMs >= Constants.Performance.SEVERE_FRAME_TIME_MS) {
            severeFrameStreak++
            stableFrameStreak = 0
        } else {
            severeFrameStreak = 0
            if (frameDeltaMs <= currentTier.frameDelayMs + 4L) {
                stableFrameStreak++
            } else {
                stableFrameStreak = 0
            }
        }

        when (currentTier) {
            QualityTier.HIGH -> {
                if (avgFrameMs >= Constants.Performance.MEDIUM_TO_LOW_AVG_FRAME_MS ||
                    severeFrameStreak >= Constants.Performance.SEVERE_FRAME_STREAK_FOR_LOW
                ) {
                    setTier(QualityTier.LOW)
                } else if (avgFrameMs >= Constants.Performance.HIGH_TO_MEDIUM_AVG_FRAME_MS) {
                    setTier(QualityTier.MEDIUM)
                }
            }

            QualityTier.MEDIUM -> {
                if (avgFrameMs >= Constants.Performance.MEDIUM_TO_LOW_AVG_FRAME_MS ||
                    severeFrameStreak >= Constants.Performance.SEVERE_FRAME_STREAK_FOR_LOW
                ) {
                    setTier(QualityTier.LOW)
                } else if (stableFrameStreak >= Constants.Performance.STABLE_FRAMES_FOR_UPGRADE &&
                    avgFrameMs <= Constants.Performance.MEDIUM_TO_HIGH_AVG_FRAME_MS
                ) {
                    setTier(QualityTier.HIGH)
                }
            }

            QualityTier.LOW -> {
                if (avgFrameMs >= Constants.Performance.LOW_TO_MIN_AVG_FRAME_MS ||
                    severeFrameStreak >= Constants.Performance.SEVERE_FRAME_STREAK_FOR_MIN
                ) {
                    setTier(QualityTier.MIN)
                } else if (stableFrameStreak >= Constants.Performance.STABLE_FRAMES_FOR_UPGRADE &&
                    avgFrameMs <= Constants.Performance.LOW_TO_MEDIUM_AVG_FRAME_MS
                ) {
                    setTier(QualityTier.MEDIUM)
                }
            }

            QualityTier.MIN -> {
                if (stableFrameStreak >= Constants.Performance.STABLE_FRAMES_FOR_UPGRADE &&
                    avgFrameMs <= Constants.Performance.MIN_TO_LOW_AVG_FRAME_MS
                ) {
                    setTier(QualityTier.LOW)
                }
            }
        }
    }

    private fun setTier(newTier: QualityTier) {
        if (currentTier == newTier) return
        currentTier = newTier
        stableFrameStreak = 0
        severeFrameStreak = 0
        ValueAnimator.setFrameDelay(newTier.frameDelayMs)
        Log.d(TAG, "Animation quality -> ${newTier.name}")
    }

    class FrameThrottle {
        private var lastDispatchUptimeMs: Long = Long.MIN_VALUE

        fun shouldDispatch(animator: ValueAnimator): Boolean {
            if (!Constants.Performance.ADAPTIVE_ANIMATION_MODE_ENABLED) return true

            val tier = currentTier
            val duration = animator.duration
            val finished = duration <= 0L || animator.currentPlayTime >= duration
            if (finished) {
                lastDispatchUptimeMs = SystemClock.uptimeMillis()
                return true
            }
            if (tier.skipNonEssentialAnimations) {
                return false
            }

            val now = SystemClock.uptimeMillis()
            if (lastDispatchUptimeMs == Long.MIN_VALUE || now - lastDispatchUptimeMs >= tier.frameDelayMs) {
                lastDispatchUptimeMs = now
                return true
            }
            return false
        }

        fun reset() {
            lastDispatchUptimeMs = Long.MIN_VALUE
        }
    }
}
