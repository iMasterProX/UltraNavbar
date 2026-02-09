package com.minsoo.ultranavbar.core

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import java.util.concurrent.atomic.AtomicInteger

/**
 * 분할화면 실행 헬퍼 (단순화 버전)
 *
 * 접근성 서비스 기반으로 분할화면 실행:
 * 1. GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN으로 분할화면 모드 진입
 * 2. FLAG_ACTIVITY_LAUNCH_ADJACENT로 두 번째 앱 실행
 */
object SplitScreenHelper {
    private const val TAG = "SplitScreenHelper"
    private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
    private const val SPLIT_RECOVERY_GRACE_MS = 5000L
    private const val WARM_LAUNCH_DELAY_MS = 1000L
    private const val SPLIT_RETRY_DELAY_MS = 700L
    private const val MAX_SPLIT_RETRY = 2
    private const val SPLIT_FOCUS_DELAY_MS = 200L
    private const val SPLIT_FOCUS_COLD_EXTRA_MS = 200L
    private const val SPLIT_READY_COLD_EXTRA_MS = 1500L
    private const val SPLIT_FALLBACK_STALE_MS = 5000L
    private const val LAUNCHER_CACHE_TTL_MS = 30000L
    private const val SPLIT_STICKY_MS = 5000L
    private const val SPLIT_PANE_MIN_AREA_RATIO = 0.15f
    private val handler = Handler(Looper.getMainLooper())
    private val launchTokenCounter = AtomicInteger(0)
    @Volatile
    private var activeLaunchToken: Int = 0

    // 접근성 서비스 참조 (NavBarAccessibilityService에서 설정)
    private var accessibilityService: AccessibilityService? = null

    // 분할화면 활성 상태 추적 (UI 표시용)
    @Volatile
    private var isSplitScreenActive = false
    @Volatile
    private var lastSplitRequestAt: Long = 0L
    @Volatile
    private var splitUsedFlag: Boolean = false
    @Volatile
    private var secondaryLaunchOptionsAvailable: Boolean? = null
    @Volatile
    private var lastSplitFallbackPackage: String = ""
    @Volatile
    private var lastSplitFallbackAt: Long = 0L
    @Volatile
    private var lastLaunchFailure: SplitLaunchFailure = SplitLaunchFailure.NONE
    @Volatile
    private var lastSplitDetectedAt: Long = 0L
    @Volatile
    private var lastSplitActivatedAt: Long = 0L
    @Volatile
    private var cachedLauncherPackages: Set<String> = emptySet()
    @Volatile
    private var cachedLauncherPackagesAt: Long = 0L
    @Volatile
    private var appLabelCache: MutableMap<String, String> = mutableMapOf()
    @Volatile
    private var hiddenApiUnblocked: Boolean = false

    enum class SplitLaunchFailure {
        NONE,
        TARGET_UNSUPPORTED,
        CURRENT_UNSUPPORTED,
        NO_PRIMARY,
        SERVICE_UNAVAILABLE,
        SPLIT_NOT_READY,
        LAUNCH_FAILED,
        SPLIT_FAILED,
        EXCEPTION
    }

    fun getLastLaunchFailure(): SplitLaunchFailure = lastLaunchFailure

    fun setAccessibilityService(service: AccessibilityService?) {
        accessibilityService = service
    }

    private fun nextLaunchToken(): Int {
        val token = launchTokenCounter.incrementAndGet()
        activeLaunchToken = token
        return token
    }

    private fun isLaunchTokenValid(token: Int): Boolean {
        return activeLaunchToken == token
    }

    /**
     * 분할화면이 현재 활성 상태인지 확인
     */
    fun isSplitScreenActive(): Boolean = isSplitScreenActive

    /**
     * 분할화면 활성 상태 설정 (NavBarAccessibilityService에서 호출)
     */
    fun setSplitScreenActive(active: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (active) {
            lastSplitActivatedAt = now
            lastSplitDetectedAt = now
            if (!isSplitScreenActive) {
                Log.d(TAG, "Split screen active: $isSplitScreenActive -> true")
                isSplitScreenActive = true
            }
        } else {
            // 스티키 기간: false 이벤트 무시. 다만 여전히 활성이라면 타이머 연장.
            if (now - lastSplitActivatedAt < SPLIT_STICKY_MS) {
                if (isSplitActiveNow()) {
                    lastSplitActivatedAt = now
                    lastSplitDetectedAt = now
                    Log.d(TAG, "Ignoring split false; still active, sticky refreshed (${now - lastSplitActivatedAt}ms < $SPLIT_STICKY_MS)")
                } else {
                    Log.d(TAG, "Ignoring split false within sticky window (${now - lastSplitActivatedAt}ms < $SPLIT_STICKY_MS)")
                }
                return
            }
            // 스티키 종료 후에도 실제 분할이면 false 전환 보류
            if (isSplitActiveNow()) {
                lastSplitActivatedAt = now
                lastSplitDetectedAt = now
                Log.d(TAG, "Split false ignored; split still detected after sticky")
                return
            }
            if (isSplitScreenActive) {
                Log.d(TAG, "Split screen active: true -> false")
                isSplitScreenActive = false
            }
        }
    }

    /**
     * 분할화면 상태 강제 리셋
     */
    fun forceResetSplitScreenState() {
        Log.d(TAG, "Force reset split screen state")
        isSplitScreenActive = false
        splitUsedFlag = false
        activeLaunchToken = launchTokenCounter.incrementAndGet()
        lastSplitRequestAt = 0L
        lastSplitFallbackPackage = ""
        lastSplitFallbackAt = 0L
        lastLaunchFailure = SplitLaunchFailure.NONE
        lastSplitDetectedAt = 0L
        lastSplitActivatedAt = 0L
    }

    // 하위 호환성을 위한 더미 메서드들
    fun markSplitScreenUsed() {
        splitUsedFlag = true
        lastSplitRequestAt = SystemClock.elapsedRealtime()
    }

    fun wasSplitScreenUsedAndReset(): Boolean {
        val used = splitUsedFlag
        splitUsedFlag = false
        return used
    }

    fun isInRecoveryGracePeriod(): Boolean {
        val last = lastSplitRequestAt
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last < SPLIT_RECOVERY_GRACE_MS
    }

    private fun resolveSplitReadyTimeoutMs(targetWarm: Boolean, primaryWarm: Boolean): Long {
        val base = Constants.Timing.SPLIT_SCREEN_LAUNCH_DELAY_MS
        return when {
            targetWarm && primaryWarm -> WARM_LAUNCH_DELAY_MS
            targetWarm || primaryWarm -> base
            else -> base + SPLIT_READY_COLD_EXTRA_MS
        }
    }

    private fun resolveFocusDelayMs(targetWarm: Boolean): Long {
        return if (targetWarm) SPLIT_FOCUS_DELAY_MS else SPLIT_FOCUS_DELAY_MS + SPLIT_FOCUS_COLD_EXTRA_MS
    }

    /**
     * 앱이 분할화면(멀티윈도우)을 지원하는지 확인
     */
    fun isResizeableActivity(context: Context, packageName: String, className: String? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        return try {
            val pm = context.packageManager
            val activityInfo = resolveActivityInfoForSplit(pm, packageName, className) ?: return false

            val resizeModeField = ActivityInfo::class.java.getField("resizeMode")
            val resizeMode = resizeModeField.getInt(activityInfo)
            // RESIZE_MODE_UNRESIZEABLE = 0 일 때만 분할화면 불가
            val isResizeable = resizeMode != 0
            val classSuffix = if (!className.isNullOrBlank()) "/$className" else ""
            Log.d(TAG, "isResizeableActivity: $packageName$classSuffix, resizeMode=$resizeMode, result=$isResizeable")
            isResizeable
        } catch (e: Exception) {
            Log.w(TAG, "isResizeableActivity check failed for $packageName", e)
            true // Treat as resizeable on errors to avoid false negatives.
        }
    }

    private fun resolveActivityInfoForSplit(
        pm: PackageManager,
        packageName: String,
        className: String?
    ): ActivityInfo? {
        val normalizedClass = className?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith(".")) packageName + it else it
        }
        if (!normalizedClass.isNullOrBlank()) {
            try {
                return pm.getActivityInfo(ComponentName(packageName, normalizedClass), 0)
            } catch (_: Exception) {
                // Fall back to launch intent resolution.
            }
        }

        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        val component = intent.component ?: return null
        return pm.getActivityInfo(component, 0)
    }

    /**
     * 분할화면 실행 (단순화)
     *
     * 주의: 이 함수 호출 전에 isResizeableActivity()로 분할화면 지원 여부를 확인해야 함
     */
    fun launchSplitScreen(
        context: Context,
        targetPackage: String,
        launchContext: NavBarAccessibilityService.SplitLaunchContext,
        fallbackPrimaryPackage: String? = null
    ): Boolean {
        val currentPackage = launchContext.currentPackage
        lastLaunchFailure = SplitLaunchFailure.NONE
        val launchToken = nextLaunchToken()
        Log.d(
            TAG,
            "Launch split screen: target=$targetPackage, current=$currentPackage, " +
                "home=${launchContext.isOnHomeScreen}, recents=${launchContext.isRecentsVisible}, " +
                "visibleNonLauncher=${launchContext.hasVisibleNonLauncherApp}, splitCached=${launchContext.isSplitScreenMode}"
        )

        // 분할화면 지원 여부 재확인 (안전장치)
        if (!isResizeableActivity(context, targetPackage)) {
            Log.w(TAG, "Target app does not support split screen: $targetPackage")
            lastLaunchFailure = SplitLaunchFailure.TARGET_UNSUPPORTED
            return false
        }

        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "Accessibility service not available, launching app normally")
            launchAppNormally(context, targetPackage)
            lastLaunchFailure = SplitLaunchFailure.SERVICE_UNAVAILABLE
            return false
        }

        return try {
            val navService = service as? NavBarAccessibilityService
            val resolvedCurrent = when {
                currentPackage.isNotEmpty() -> currentPackage
                else -> navService?.resolveForegroundPackageForSplit() ?: ""
            }
            val resolvedCurrentClassName = when {
                resolvedCurrent.isNotEmpty() && currentPackage == resolvedCurrent -> launchContext.currentClassName
                resolvedCurrent.isNotEmpty() -> navService?.resolveForegroundClassForSplit(resolvedCurrent) ?: ""
                else -> ""
            }
            if (resolvedCurrentClassName.isNotEmpty()) {
                Log.d(TAG, "Resolved current class for split: $resolvedCurrentClassName")
            }
            val targetWarm = isAppProcessRunning(context, targetPackage)
            val focusDelayMs = resolveFocusDelayMs(targetWarm)

            // 홈/런처에서 primary 없는 상태면 바로 중단
            if (resolvedCurrent.isEmpty() && launchContext.isOnHomeScreen) {
                Log.w(TAG, "No primary app available (home/launcher). Aborting split launch.")
                lastLaunchFailure = SplitLaunchFailure.NO_PRIMARY
                showSplitFailureToast(context)
                return false
            }

            val splitSelectionVisible = navService?.isSplitSelectionVisibleForSplit() == true
            val selectionEligible = splitSelectionVisible || launchContext.isRecentsVisible
            val splitActiveNow = isSplitActiveNow()
            val recentlyActive = wasSplitActiveRecently()
            val isSplitActive = launchContext.isSplitScreenMode ||
                splitActiveNow ||
                splitSelectionVisible ||
                (recentlyActive && (launchContext.isRecentsVisible || splitSelectionVisible))
            if (isSplitActive) {
                markSplitScreenUsed()
                if (lastSplitFallbackPackage.isEmpty() && resolvedCurrent.isNotEmpty()) {
                    recordSplitFallbackPackage(context, resolvedCurrent)
                }
                val options = if (secondaryLaunchOptionsAvailable != false) {
                    buildSecondaryLaunchOptions()
                } else {
                    null
                }
                val replaceDelayMs = if (!selectionEligible) 0L else focusDelayMs
                if (selectionEligible) {
                    val selected = launchFromSelection(context, service, targetPackage)
                    if (selected) {
                        return true
                    }
                    if (options == null) {
                        lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                        return false
                    }
                }
                val launched = launchToSecondary(
                    context,
                    service,
                    targetPackage,
                    replaceDelayMs,
                    launchToken,
                    targetWarm,
                    optionsOverride = options,
                    allowOptions = false
                )
                if (!launched) {
                    lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                }
                return launched
            }

            val hasPrimary =
                launchContext.hasVisibleNonLauncherApp ||
                (resolvedCurrent.isNotEmpty() &&
                    !isLauncherPackage(context, resolvedCurrent) &&
                    resolvedCurrent != "com.android.systemui")

            if (!hasPrimary) {
                val primaryCandidate = fallbackPrimaryPackage?.takeIf { it.isNotEmpty() && it != targetPackage }
                if (primaryCandidate.isNullOrEmpty()) {
                    Log.w(TAG, "No visible primary app for split, skipping launch")
                    lastLaunchFailure = SplitLaunchFailure.NO_PRIMARY
                    return false
                }

                if (!isResizeableActivity(context, primaryCandidate)) {
                    Log.w(TAG, "Fallback primary does not support split screen: $primaryCandidate")
                    lastLaunchFailure = SplitLaunchFailure.CURRENT_UNSUPPORTED
                    return false
                }

                Log.d(TAG, "Launching fallback primary for split: $primaryCandidate")
                recordSplitFallbackPackage(context, primaryCandidate)
                launchAppNormally(context, primaryCandidate)

                val primaryWarm = isAppProcessRunning(context, primaryCandidate)
                val primaryWaitMs = if (primaryWarm) {
                    Constants.Timing.SPLIT_SCREEN_LAUNCH_DELAY_MS
                } else {
                    Constants.Timing.SPLIT_SCREEN_LAUNCH_DELAY_MS * 2
                }
                val splitReadyTimeoutMs = resolveSplitReadyTimeoutMs(targetWarm, primaryWarm)
                waitForPackageVisible(
                    primaryCandidate,
                    primaryWaitMs,
                    launchToken,
                    onReady = {
                        toggleThenLaunch(
                            context,
                            service,
                            targetPackage,
                            splitReadyTimeoutMs,
                            launchToken
                        )
                    },
                    onTimeout = {
                        lastLaunchFailure = SplitLaunchFailure.NO_PRIMARY
                        restorePrimaryIfPossible(context, targetPackage)
                        showSplitFailureToast(context)
                    }
                )
                return true
            }

            if (resolvedCurrent.isNotEmpty() &&
                !isLauncherPackage(context, resolvedCurrent) &&
                resolvedCurrent != "com.android.systemui" &&
                !isResizeableActivity(context, resolvedCurrent, resolvedCurrentClassName)
            ) {
                Log.w(TAG, "Current app does not support split screen: $resolvedCurrent")
                lastLaunchFailure = SplitLaunchFailure.CURRENT_UNSUPPORTED
                return false
            }

            if (resolvedCurrent.isNotEmpty()) {
                recordSplitFallbackPackage(context, resolvedCurrent)
            }
            val primaryWarm = resolvedCurrent.isNotEmpty() && isAppProcessRunning(context, resolvedCurrent)
            val delayMs = resolveSplitReadyTimeoutMs(targetWarm, primaryWarm)
            markSplitScreenUsed()
            toggleThenLaunch(context, service, targetPackage, delayMs, launchToken)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Split screen failed", e)
            lastLaunchFailure = SplitLaunchFailure.EXCEPTION
            false
        }
    }

    /**
     * 앱 일반 실행 (분할화면 없이)
     */
    private fun launchAppNormally(context: Context, targetPackage: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        if (intent == null) {
            Log.e(TAG, "Cannot find launch intent: $targetPackage")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.d(TAG, "Launched normally: $targetPackage")
    }

    private fun waitForPackageVisible(
        packageName: String,
        timeoutMs: Long,
        launchToken: Int,
        onReady: () -> Unit,
        onTimeout: () -> Unit
    ) {
        if (!isLaunchTokenValid(launchToken)) {
            return
        }
        val service = accessibilityService
        if (service == null) {
            onTimeout()
            return
        }

        val startAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (!isLaunchTokenValid(launchToken)) {
                    return
                }
                if (isPackageVisible(service, packageName)) {
                    val elapsed = SystemClock.elapsedRealtime() - startAt
                    Log.d(TAG, "Primary visible after ${elapsed}ms: $packageName")
                    onReady()
                    return
                }
                if (SystemClock.elapsedRealtime() - startAt >= timeoutMs) {
                    Log.w(TAG, "Primary not visible within ${timeoutMs}ms: $packageName")
                    onTimeout()
                    return
                }
                handler.postDelayed(this, 100L)
            }
        }
        handler.post(poll)
    }

    private fun isPackageVisible(service: AccessibilityService, packageName: String): Boolean {
        val windowList = try { service.windows.toList() } catch (e: Exception) { return false }
        if (windowList.isEmpty()) return false

        for (window in windowList) {
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg == packageName) {
                return true
            }
        }

        return false
    }

    private fun launchFromSelection(
        context: Context,
        service: AccessibilityService,
        targetPackage: String
    ): Boolean {
        val navService = service as? NavBarAccessibilityService ?: return false
        val label = getAppLabel(context, targetPackage)
        val candidates = linkedSetOf<String>()
        if (!label.isNullOrEmpty()) {
            candidates.add(label)
            candidates.add(label.replace(" ", ""))
        }
        candidates.add(targetPackage)

        val selectionPackage = navService.getLauncherWindowPackageForSplit()
        val selectionPackages = linkedSetOf<String>()
        if (!selectionPackage.isNullOrEmpty()) {
            selectionPackages.add(selectionPackage)
        }
        selectionPackages.addAll(navService.getVisibleLauncherPackagesForSplit())
        if (selectionPackages.isEmpty()) {
            Log.w(TAG, "Selection UI package not found for split selection")
            return false
        }
        Log.d(TAG, "Selection packages for split: $selectionPackages")

        for (pkg in selectionPackages) {
            for (candidate in candidates) {
                if (candidate.isBlank()) continue
                val clicked = navService.performNodeClick(
                    nodeId = null,
                    nodeText = candidate,
                    nodeDesc = candidate,
                    nodePackage = pkg
                )
                if (clicked) {
                    Log.d(TAG, "Selected target from launcher/recents: $candidate ($targetPackage) in $pkg")
                    return true
                }
            }
        }

        Log.w(TAG, "Failed to select target from launcher/recents: $targetPackage (pkgs=$selectionPackages)")
        return false
    }

    private fun ensureHiddenApiAccess() {
        // Android 12+에서 public API가 제공되므로 별도 예외 처리 없이 통과시킨다.
        hiddenApiUnblocked = true
    }

    private fun getAppLabel(context: Context, packageName: String): String? {
        val cached = appLabelCache[packageName]
        if (!cached.isNullOrEmpty()) return cached
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val label = context.packageManager.getApplicationLabel(info)?.toString()?.trim()
            if (!label.isNullOrEmpty()) {
                appLabelCache[packageName] = label
            }
            label
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 분할화면 토글 후 앱 실행
     */
    private fun toggleThenLaunch(
        context: Context,
        service: AccessibilityService,
        targetPackage: String,
        maxWaitMs: Long,
        launchToken: Int,
        attempt: Int = 1
    ) {
        if (!isLaunchTokenValid(launchToken)) {
            return
        }
        val toggled = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        if (!toggled) {
            Log.w(TAG, "Toggle split screen request rejected")
            lastLaunchFailure = SplitLaunchFailure.SPLIT_NOT_READY
            restorePrimaryIfPossible(context, targetPackage)
            showSplitFailureToast(context)
            return
        }
        markSplitScreenUsed()
        Log.d(TAG, "Toggled split screen (requested=$toggled)")

        val navService = service as? NavBarAccessibilityService
        val targetWarm = isAppProcessRunning(context, targetPackage)
        val focusDelayMs = resolveFocusDelayMs(targetWarm)

        // Split ?? ??/?? ??? ????? ?? (?? maxWaitMs)
        val startAt = SystemClock.elapsedRealtime()
        val launched = booleanArrayOf(false)
        val lastSelectionAttemptAt = longArrayOf(0L)
        val poll = object : Runnable {
            override fun run() {
                if (launched[0]) return
                if (!isLaunchTokenValid(launchToken)) {
                    return
                }
                val elapsed = SystemClock.elapsedRealtime() - startAt
                val splitActive = isSplitActiveNow()
                val recentsReady = navService?.isRecentsVisibleForSplit() == true
                val selectionReady = navService?.isSplitSelectionVisibleForSplit() == true
                val resolvedPrimary = navService?.resolveForegroundPackageForSplit()
                if (!resolvedPrimary.isNullOrEmpty() && lastSplitFallbackPackage.isEmpty()) {
                    recordSplitFallbackPackage(context, resolvedPrimary)
                }
                // 홈에 머무르고 있으면 분할 준비로 보지 않음
                val homeReady = navService?.isOnHomeScreenForSplit() == true &&
                    !resolvedPrimary.isNullOrEmpty() &&
                    !isLauncherPackage(context, resolvedPrimary)
                // 실제 분할 UI가 보일 때만 진행: 활성/선택/최근 준비 상태가 하나라도 true 여야 한다.
                // 분할 UI가 실제로 나타났는지 판단. 활성/선택/최근 또는 런처가 전면에 있고
                // 포그라운드 앱이 해석된 경우를 신호로 본다.
                val selectionEligible = selectionReady || recentsReady
                val options = if (secondaryLaunchOptionsAvailable != false) {
                    buildSecondaryLaunchOptions()
                } else {
                    null
                }

                if (selectionEligible) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSelectionAttemptAt[0] >= 250L) {
                        lastSelectionAttemptAt[0] = now
                        val selected = launchFromSelection(context, service, targetPackage)
                        if (selected) {
                            launched[0] = true
                            Log.d(
                                TAG,
                                "Split ready after ${elapsed}ms (active=${splitActive}, recents=${recentsReady}, selection=${selectionReady}, home=${homeReady}), launched via selection"
                            )
                            setSplitScreenActive(true)
                            lastSplitDetectedAt = SystemClock.elapsedRealtime()
                            markSplitScreenUsed()
                            scheduleSplitRetryIfNeeded(context, service, targetPackage, maxWaitMs, launchToken, attempt)
                            return
                        }
                    }
                    if (options == null) {
                        if (elapsed >= maxWaitMs) {
                            launched[0] = true
                            Log.w(
                                TAG,
                                "Split not ready after ${elapsed}ms (active=${splitActive}, recents=${recentsReady}, selection=${selectionReady}, home=${homeReady})"
                            )
                            lastLaunchFailure = SplitLaunchFailure.SPLIT_NOT_READY
                            restorePrimaryIfPossible(context, targetPackage)
                            showSplitFailureToast(context)
                            return
                        }
                        handler.postDelayed(this, 100L)
                        return
                    }
                }

                if (!homeReady && splitActive) {
                    launched[0] = true
                    Log.d(
                        TAG,
                        "Split ready after ${elapsed}ms (active=${splitActive}, recents=${recentsReady}, selection=${selectionReady}, home=${homeReady}), launching adjacent"
                    )
                    val adjacentDelayMs = if (!selectionEligible) 0L else focusDelayMs
                    val launchedOk = launchToSecondary(
                        context,
                        service,
                        targetPackage,
                        adjacentDelayMs,
                        launchToken,
                        targetWarm,
                        optionsOverride = options,
                        allowOptions = false
                    )
                    if (!launchedOk) {
                        lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                        Log.w(TAG, "Failed to launch adjacent while split ready")
                        restorePrimaryIfPossible(context, targetPackage)
                        showSplitFailureToast(context)
                        return
                    }
                    setSplitScreenActive(true)
                    lastSplitDetectedAt = SystemClock.elapsedRealtime()
                    markSplitScreenUsed()
                    scheduleSplitRetryIfNeeded(context, service, targetPackage, maxWaitMs, launchToken, attempt)
                    return
                }
                if (elapsed >= maxWaitMs) {
                    launched[0] = true
                    Log.w(
                        TAG,
                        "Split not ready after ${elapsed}ms (active=$splitActive, recents=$recentsReady, selection=$selectionReady, home=$homeReady)"
                    )
                    lastLaunchFailure = SplitLaunchFailure.SPLIT_NOT_READY
                    restorePrimaryIfPossible(context, targetPackage)
                    showSplitFailureToast(context)
                    return
                }
                handler.postDelayed(this, 100L)
            }
        }
        handler.postDelayed(poll, 100L)
    }

    private fun launchToSecondary(
        context: Context,
        service: AccessibilityService,
        targetPackage: String,
        focusDelayMs: Long,
        launchToken: Int,
        isTargetWarm: Boolean,
        optionsOverride: android.os.Bundle? = null,
        allowOptions: Boolean = true
    ): Boolean {
        if (!isLaunchTokenValid(launchToken)) return false

        val options = optionsOverride ?: if (allowOptions) buildSecondaryLaunchOptions() else null
        if (options != null) {
            val focusResult = (service as? NavBarAccessibilityService)?.let {
                focusSplitPane(it, SplitPane.PRIMARY, forceTap = true)
            }
            val didFocus = focusResult?.didFocus == true
            val baseDelayMs = if (isTargetWarm) 0L else focusDelayMs
            val delayMs = if (didFocus) maxOf(baseDelayMs, focusDelayMs) else baseDelayMs
            Log.d(TAG, "Launching secondary with ActivityOptions (delay=${delayMs}ms, focusAdjusted=$didFocus)")
            handler.postDelayed({
                if (!isLaunchTokenValid(launchToken)) {
                    return@postDelayed
                }
                if (!launchAdjacent(context, targetPackage, preferSecondary = true, optionsOverride = options)) {
                    lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                    Log.w(TAG, "Failed to launch adjacent with secondary options")
                }
            }, maxOf(0L, delayMs))
            return true
        }

        if (focusPrimaryAndLaunch(context, service, targetPackage, focusDelayMs, launchToken)) {
            Log.d(TAG, "Secondary options unavailable; focused primary for adjacent launch")
            return true
        }

        if (!isLaunchTokenValid(launchToken)) return false
        return launchAdjacent(context, targetPackage, preferSecondary = true)
    }

    private fun focusSplitPane(
        service: NavBarAccessibilityService,
        targetPane: SplitPane,
        forceTap: Boolean
    ): FocusResult {
        val paneInfo = resolveSplitPaneBounds(service)
        if (paneInfo == null) {
            if (!forceTap) return FocusResult(false, false)
            val bounds = getScreenBounds(service)
            if (bounds.width() <= 0 || bounds.height() <= 0) return FocusResult(false, false)
            val isLandscape = bounds.width() >= bounds.height()
            val x = when (targetPane) {
                SplitPane.PRIMARY -> bounds.left + (bounds.width() * if (isLandscape) 0.15f else 0.5f)
                SplitPane.SECONDARY -> bounds.left + (bounds.width() * if (isLandscape) 0.85f else 0.5f)
            }
            val y = when (targetPane) {
                SplitPane.PRIMARY -> bounds.top + (bounds.height() * if (isLandscape) 0.5f else 0.15f)
                SplitPane.SECONDARY -> bounds.top + (bounds.height() * if (isLandscape) 0.5f else 0.9f)
            }
            Log.d(TAG, "Focusing $targetPane pane at ($x, $y) [fallback]")
            val tapped = service.performTap(x, y)
            return FocusResult(tapped, tapped)
        }

        val targetBounds = if (targetPane == SplitPane.PRIMARY) paneInfo.primary else paneInfo.secondary
        val targetPackage = if (targetPane == SplitPane.PRIMARY) paneInfo.primaryPackage else paneInfo.secondaryPackage
        Log.d(
            TAG,
            "Split pane info: primary=${paneInfo.primaryPackage}, secondary=${paneInfo.secondaryPackage}, focused=${paneInfo.focusedPane}"
        )

        if (paneInfo.focusedPane == targetPane) {
            Log.d(TAG, "Split pane already focused: $targetPane")
            return FocusResult(true, false)
        }
        if (paneInfo.focusedPane == null && !forceTap) {
            Log.d(TAG, "Split pane focus unknown; skipping focus request")
            return FocusResult(true, false)
        }

        val focusedByAction = requestAccessibilityFocus(service, targetBounds, targetPackage)
        if (focusedByAction) {
            Log.d(TAG, "Focused $targetPane via accessibility focus")
            return FocusResult(true, true)
        }

        val x = targetBounds.exactCenterX()
        val y = targetBounds.exactCenterY()
        Log.d(TAG, "Focusing $targetPane pane at ($x, $y)")
        val tapped = service.performTap(x, y)
        return FocusResult(tapped, tapped)
    }

    private fun requestAccessibilityFocus(
        service: NavBarAccessibilityService,
        targetBounds: Rect,
        targetPackage: String
    ): Boolean {
        if (targetPackage.isEmpty()) return false
        val centerX = targetBounds.exactCenterX().toInt()
        val centerY = targetBounds.exactCenterY().toInt()
        val windowList = try { service.windows.toList() } catch (e: Exception) { return false }
        for (window in windowList) {
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (!bounds.contains(centerX, centerY)) continue
            val root = try { window.root } catch (e: Exception) { null } ?: continue
            val pkg = root.packageName?.toString()
            if (pkg.isNullOrEmpty() || pkg != targetPackage) {
                root.recycle()
                continue
            }
            val focused = try {
                root.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
                    root.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) {
                false
            }
            root.recycle()
            if (focused) return true
        }
        return false
    }

    private fun focusPrimaryAndLaunch(
        context: Context,
        service: AccessibilityService,
        targetPackage: String,
        delayMs: Long,
        launchToken: Int
    ): Boolean {
        if (!isLaunchTokenValid(launchToken)) return false
        val navService = service as? NavBarAccessibilityService ?: return false
        val focusResult = focusSplitPane(navService, SplitPane.PRIMARY, forceTap = true)
        if (!focusResult.success) return false

        handler.postDelayed({
            if (!isLaunchTokenValid(launchToken)) {
                return@postDelayed
            }
            if (!launchAdjacent(context, targetPackage, preferSecondary = true)) {
                lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                Log.w(TAG, "Failed to launch adjacent after focusing primary pane")
            }
        }, maxOf(0L, delayMs))
        return true
    }

    private fun focusSecondaryAndLaunch(
        context: Context,
        service: AccessibilityService,
        targetPackage: String,
        launchToken: Int
    ): Boolean {
        if (!isLaunchTokenValid(launchToken)) return false
        val navService = service as? NavBarAccessibilityService ?: return false
        val focusResult = focusSplitPane(navService, SplitPane.SECONDARY, forceTap = true)
        if (!focusResult.success) return false

        handler.postDelayed({
            if (!isLaunchTokenValid(launchToken)) {
                return@postDelayed
            }
            if (!launchAdjacent(context, targetPackage, preferSecondary = true)) {
                lastLaunchFailure = SplitLaunchFailure.LAUNCH_FAILED
                Log.w(TAG, "Failed to launch adjacent after focusing secondary pane")
            }
        }, SPLIT_FOCUS_DELAY_MS)
        return true
    }

    private fun getScreenBounds(service: AccessibilityService): Rect {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }
    }

    private enum class SplitPane {
        PRIMARY,
        SECONDARY
    }

    private data class SplitPaneInfo(
        val primary: Rect,
        val secondary: Rect,
        val primaryPackage: String,
        val secondaryPackage: String,
        val focusedPane: SplitPane?
    )

    private data class FocusResult(
        val success: Boolean,
        val didFocus: Boolean
    )

    private fun resolveSplitPaneBounds(service: AccessibilityService): SplitPaneInfo? {
        val windowList = try { service.windows.toList() } catch (e: Exception) { return null }
        if (windowList.isEmpty()) return null

        val screen = getScreenBounds(service)
        if (screen.width() <= 0 || screen.height() <= 0) return null
        val isLandscape = screen.width() >= screen.height()

        data class Candidate(
            val bounds: Rect,
            val packageName: String,
            val focused: Boolean,
            val active: Boolean,
            val areaRatio: Float
        )

        val candidates = mutableListOf<Candidate>()
        for (window in windowList) {
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val widthRatio = bounds.width().toFloat() / screen.width()
            val heightRatio = bounds.height().toFloat() / screen.height()
            val areaRatio = widthRatio * heightRatio
            if (areaRatio < SPLIT_PANE_MIN_AREA_RATIO) continue
            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg.isNullOrEmpty()) continue
            if (pkg == service.packageName || pkg == "com.android.systemui") continue
            if (isLauncherPackage(service, pkg)) continue
            candidates.add(
                Candidate(
                    bounds = Rect(bounds),
                    packageName = pkg,
                    focused = window.isFocused,
                    active = window.isActive,
                    areaRatio = areaRatio
                )
            )
        }

        if (candidates.size < 2) return null
        val topCandidates = candidates
            .sortedByDescending { it.areaRatio }
            .take(2)
        if (topCandidates.size < 2) return null
        val sorted = if (isLandscape) {
            topCandidates.sortedBy { it.bounds.exactCenterX() }
        } else {
            topCandidates.sortedBy { it.bounds.exactCenterY() }
        }
        val primary = sorted.first()
        val secondary = sorted.last()
        val focusedPane = when {
            primary.focused -> SplitPane.PRIMARY
            secondary.focused -> SplitPane.SECONDARY
            primary.active && !secondary.active -> SplitPane.PRIMARY
            secondary.active && !primary.active -> SplitPane.SECONDARY
            else -> null
        }
        return SplitPaneInfo(
            primary = primary.bounds,
            secondary = secondary.bounds,
            primaryPackage = primary.packageName,
            secondaryPackage = secondary.packageName,
            focusedPane = focusedPane
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scheduleSplitRetryIfNeeded(
        context: Context,
        service: AccessibilityService,
        targetPackage: String,
        maxWaitMs: Long,
        launchToken: Int,
        attempt: Int
    ) {
        val delayMs = maxOf(SPLIT_RETRY_DELAY_MS * 2, maxWaitMs)
        handler.postDelayed({
            if (!isLaunchTokenValid(launchToken)) {
                return@postDelayed
            }
            val recentlyActive = wasSplitActiveRecently(4000)
            if (!isSplitActiveNow() && !isSplitScreenActive() && !recentlyActive) {
                Log.w(TAG, "Split still not active after launch")
                lastLaunchFailure = SplitLaunchFailure.SPLIT_FAILED
                restorePrimaryIfPossible(context, targetPackage)
            }
        }, delayMs)
    }

    private fun recordSplitFallbackPackage(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        if (packageName == "com.android.systemui") return
        if (isLauncherPackage(context, packageName)) return
        lastSplitFallbackPackage = packageName
        lastSplitFallbackAt = SystemClock.elapsedRealtime()
    }

    private fun restorePrimaryIfPossible(context: Context, targetPackage: String? = null) {
        val pkg = lastSplitFallbackPackage
        if (pkg.isEmpty()) return
        val age = SystemClock.elapsedRealtime() - lastSplitFallbackAt
        if (age > SPLIT_FALLBACK_STALE_MS) return
        if (pkg == "com.android.systemui") return
        if (isLauncherPackage(context, pkg)) return
        if (!targetPackage.isNullOrEmpty() && pkg == targetPackage) return
        Log.d(TAG, "Restoring primary app after split failure: $pkg")
        launchAppNormally(context, pkg)
    }

    private fun showSplitNotSupportedToast(context: Context) {
        handler.post {
            Toast.makeText(
                context,
                context.getString(R.string.split_screen_not_supported_generic),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSplitFailureToast(context: Context) {
        // 비동기 분할화면 실패 시 (toggleThenLaunch 내부 등) 토스트 표시
        // lastLaunchFailure에 따라 적절한 메시지 선택
        val failure = lastLaunchFailure
        when (failure) {
            SplitLaunchFailure.TARGET_UNSUPPORTED,
            SplitLaunchFailure.CURRENT_UNSUPPORTED -> {
                showSplitNotSupportedToast(context)
            }
            SplitLaunchFailure.NONE -> {
                // 실패 원인 없음 — 토스트 불필요
            }
            else -> {
                handler.post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.split_screen_failed_generic),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * FLAG_ACTIVITY_LAUNCH_ADJACENT로 앱 실행
     */
    private fun launchAdjacent(
        context: Context,
        packageName: String,
        preferSecondary: Boolean,
        optionsOverride: android.os.Bundle? = null
    ): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )

        try {
            val options = optionsOverride ?: if (preferSecondary) buildSecondaryLaunchOptions() else null
            if (options != null) {
                context.startActivity(intent, options)
            } else {
                context.startActivity(intent)
            }
            Log.d(TAG, "Launched adjacent: $packageName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch adjacent: $packageName", e)
            return false
        }
    }

    /**
     * 런처 패키지인지 확인
     */
    private fun isLauncherPackage(context: Context, packageName: String): Boolean {
        return try {
            getLauncherPackages(context).contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun getLauncherPackages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedLauncherPackages.isNotEmpty() &&
            now - cachedLauncherPackagesAt < LAUNCHER_CACHE_TTL_MS
        ) {
            return cachedLauncherPackages
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        val extraLaunchers = setOf(
            "com.android.launcher3",
            "com.android.quickstep",
            "com.google.android.apps.nexuslauncher",
            "com.lge.launcher3",
            "com.lge.launcher",
            "com.samsung.android.launcher",
            "com.miui.home",
            "com.oneplus.launcher"
        )

        val launcherPackages = try {
            val resolveInfos = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            val packages = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toMutableSet()
            if (packages.isEmpty()) {
                val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                resolveInfo?.activityInfo?.packageName?.let { packages.add(it) }
            }
            packages.addAll(extraLaunchers)
            packages.toSet()
        } catch (e: Exception) {
            extraLaunchers
        }

        cachedLauncherPackages = launcherPackages
        cachedLauncherPackagesAt = now
        return launcherPackages
    }

    fun wasSplitActiveRecently(graceMs: Long = 4000L): Boolean {
        val last = lastSplitDetectedAt
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last < graceMs
    }

    fun isSplitActiveOrRecent(graceMs: Long = 5000L): Boolean {
        return isSplitScreenActive || wasSplitActiveRecently(graceMs)
    }

    private fun isSplitActiveNow(): Boolean {
        val service = accessibilityService ?: return false
        val windowList = try { service.windows.toList() } catch (e: Exception) { return false }
        if (windowList.isEmpty()) return false

        val displayMetrics = service.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val distinctPackages = mutableSetOf<String>()
        var hasSmallResizableWindow = false

        for (window in windowList) {
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg != null &&
                pkg != service.packageName &&
                pkg != "com.android.systemui" &&
                !isLauncherPackage(service, pkg)
            ) {
                distinctPackages.add(pkg)
            }

            val widthRatio = bounds.width().toFloat() / screenWidth
            val heightRatio = bounds.height().toFloat() / screenHeight
            if (widthRatio < 0.9f || heightRatio < 0.9f) {
                // 리사이즈 불가 앱의 다이얼로그/팝업은 분할화면으로 판단하지 않음
                if (pkg != null && isResizeableActivity(service, pkg)) {
                    hasSmallResizableWindow = true
                }
            }
        }

        if (hasSmallResizableWindow) {
            lastSplitDetectedAt = SystemClock.elapsedRealtime()
            return true
        }

        if (distinctPackages.size >= 2) {
            lastSplitDetectedAt = SystemClock.elapsedRealtime()
            return true
        }

        return false
    }

    private fun isAppProcessRunning(context: Context, packageName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = activityManager.runningAppProcesses ?: return false
        return processes.any { process ->
            process.processName == packageName ||
                (process.pkgList?.contains(packageName) == true)
        }
    }

    private fun buildSecondaryLaunchOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
    
        val available = secondaryLaunchOptionsAvailable
        if (available == false) return null
    
        return try {
            ensureHiddenApiAccess()
            val options = ActivityOptions.makeBasic()
            val clazz = ActivityOptions::class.java
            var applied = false

            try {
                val setWindowingMode = clazz.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                setWindowingMode.invoke(options, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)
                applied = true
            } catch (e: Exception) {
                Log.w(TAG, "setLaunchWindowingMode not available", e)
            }

            try {
                val setAdjacent = clazz.getMethod("setLaunchAdjacent", Boolean::class.javaPrimitiveType)
                setAdjacent.invoke(options, true)
                applied = true
            } catch (_: Exception) {
                // Optional API; ignore when unavailable.
            }

            if (!applied) {
                secondaryLaunchOptionsAvailable = false
                null
            } else {
                secondaryLaunchOptionsAvailable = true
                options.toBundle()
            }
        } catch (e: Exception) {
            secondaryLaunchOptionsAvailable = false
            Log.w(TAG, "Secondary launch options unavailable", e)
            null
        }
    }
}
