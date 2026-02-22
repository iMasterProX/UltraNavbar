package com.minsoo.ultranavbar.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager
import kotlin.math.abs
import kotlin.math.sqrt

class NavbarAppsPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: PanelActionListener
) {
    companion object {
        private const val TAG = "NavbarAppsPanel"
        const val GRID_COLUMNS = 3
        const val MAX_APPS = 10
        const val ICON_SIZE_DP = 48
        const val ICON_PADDING_DP = 12
        const val PANEL_PADDING_DP = 16
        const val PANEL_CORNER_RADIUS_DP = 16f
        const val LABEL_TEXT_SIZE_SP = 11f
        const val PANEL_MAX_WIDTH_DP = 250
    }

    interface PanelActionListener {
        fun onAppTapped(packageName: String)
        fun onAppDraggedToSplit(packageName: String)
        fun onDragStateChanged(isDragging: Boolean, progress: Float)
        fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float)
        fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float)
        fun onDragEnd()
        fun onAddAppRequested()
        fun isOnHomeScreen(): Boolean
        fun isSplitScreenDragEnabled(): Boolean
    }

    private var panelView: View? = null
    private var backdropView: View? = null
    private var isVisible = false
    private var isDragActive = false

    fun show(anchorX: Int, anchorY: Int, isSwapped: Boolean = false) {
        if (isVisible) {
            hide(reason = "show_while_visible")
            return
        }

        // 혹시 남아있는 이전 패널 즉시 정리
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            panelView = null
        }
        backdropView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            backdropView = null
        }

        val settings = SettingsManager.getInstance(context)
        val items = settings.navbarAppsItems

        createBackdrop()

        val panel = createPanelView(items)
        panelView = panel

        val panelWidthPx = context.dpToPx(PANEL_MAX_WIDTH_DP)
        // 버튼 반전 시 왼쪽에 표시, 아니면 오른쪽에 표시
        val horizontalGravity = if (isSwapped) Gravity.START else Gravity.END
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = panelWidthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or horizontalGravity
            x = context.dpToPx(8)
            y = anchorY + context.dpToPx(4)
        }

        try {
            windowManager.addView(panel, params)
            isVisible = true
            Log.d(TAG, "show() opened panel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show panel", e)
        }
    }

    fun hide(immediate: Boolean = false, reason: String = "unspecified") {
        if (!isVisible) {
            Log.d(TAG, "hide() ignored: already hidden (reason=$reason)")
            return
        }

        Log.d(TAG, "hide() start (immediate=$immediate, reason=$reason)")
        isVisible = false
        isDragActive = false

        val panel = panelView
        val backdrop = backdropView
        panelView = null
        backdropView = null

        removeViewSafely(panel)
        removeViewSafely(backdrop)
        Log.d(TAG, "hide() complete (reason=$reason)")
    }

    /**
     * WindowManager에서 안전하게 뷰 제거
     */
    private fun removeViewSafely(view: View?) {
        view ?: return
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        } catch (_: Exception) {}
    }

    /**
     * 시각적으로만 숨기기 (alpha=0) - 드래그 중 터치 이벤트를 계속 받기 위해
     * INVISIBLE은 터치 이벤트 전달을 끊으므로 alpha=0 사용
     */
    private fun hideVisually() {
        panelView?.alpha = 0f
        backdropView?.alpha = 0f
    }

    /**
     * 드래그 종료 시 호출 - 실제 hide() 수행
     */
    fun finishDrag() {
        if (isDragActive) {
            isDragActive = false
            hide(reason = "finish_drag")
        }
    }

    fun isShowing(): Boolean = isVisible

    @SuppressLint("ClickableViewAccessibility")
    private fun createBackdrop() {
        val backdrop = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> true
                    MotionEvent.ACTION_UP -> {
                        if (!isDragActive) {
                            hide(reason = "backdrop_tap")
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        backdropView = backdrop

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(backdrop, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backdrop", e)
        }
    }

    private fun isDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanelView(items: List<String>): View {
        val paddingPx = context.dpToPx(PANEL_PADDING_DP)
        val iconSizePx = context.dpToPx(ICON_SIZE_DP)
        val iconPaddingPx = context.dpToPx(ICON_PADDING_DP)
        val iconShape = SettingsManager.getInstance(context).recentAppsTaskbarIconShape

        // 네비바와 동일한 배경색: 라이트=WHITE, 다크=BLACK
        val dark = isDarkMode()
        val bgColor = if (dark) Color.BLACK else Color.WHITE
        val textColor = if (dark) Color.WHITE else Color.DKGRAY
        val secondaryTextColor = if (dark) 0xFFAAAAAA.toInt() else 0xFF888888.toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = context.dpToPx(PANEL_CORNER_RADIUS_DP).toFloat()
            }
            elevation = context.dpToPx(8).toFloat()
        }

        // 빈 상태 안내 메시지
        if (items.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = context.getString(R.string.navbar_apps_empty)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(paddingPx, paddingPx, paddingPx, context.dpToPx(8))
                setTextColor(secondaryTextColor)
            }
            container.addView(emptyView)
        }

        val grid = GridLayout(context).apply {
            columnCount = GRID_COLUMNS
        }

        var cellIndex = 0
        for (item in items) {
            val isShortcut = item.startsWith("shortcut:")
            val cellView = createAppCell(
                item,
                isShortcut,
                iconSizePx,
                iconPaddingPx,
                textColor,
                iconShape
            )
            val col = cellIndex % GRID_COLUMNS
            val row = cellIndex / GRID_COLUMNS
            cellView.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f)
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
            }
            grid.addView(cellView)
            cellIndex++
        }

        val addCell = createAddButton(iconSizePx, iconPaddingPx)
        val addCol = cellIndex % GRID_COLUMNS
        val addRow = cellIndex / GRID_COLUMNS
        addCell.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(addRow, 1f),
            GridLayout.spec(addCol, 1f)
        ).apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
        }
        grid.addView(addCell)

        container.addView(grid)
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createAppCell(
        item: String,
        isShortcut: Boolean,
        iconSizePx: Int,
        paddingPx: Int,
        textColor: Int,
        iconShape: SettingsManager.RecentAppsTaskbarIconShape
    ): View {
        val pm = context.packageManager
        val packageName = if (isShortcut) item.removePrefix("shortcut:") else item

        val icon = try {
            if (isShortcut) {
                pm.getApplicationIcon(packageName.split("/").firstOrNull() ?: packageName)
            } else {
                pm.getApplicationIcon(packageName)
            }
        } catch (e: Exception) {
            null
        } ?: return View(context)

        val label: CharSequence = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)
        } catch (e: Exception) {
            packageName.split(".").last()
        }

        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val iconView = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            applyIconShape(this, iconShape)
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = LABEL_TEXT_SIZE_SP
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(4)
            }
        }

        cell.addView(iconView)
        cell.addView(labelView)

        setupCellTouchListener(cell, iconView, packageName, isShortcut)

        return cell
    }

    private fun applyIconShape(
        iconView: ImageView,
        iconShape: SettingsManager.RecentAppsTaskbarIconShape
    ) {
        iconView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0) return

                when (iconShape) {
                    SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> {
                        outline.setOval(0, 0, width, height)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                        outline.setRect(0, 0, width, height)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                        val radius = minOf(width, height) * 0.38f
                        outline.setRoundRect(0, 0, width, height, radius)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                        val radius = minOf(width, height) * 0.22f
                        outline.setRoundRect(0, 0, width, height, radius)
                    }
                }
            }
        }
        iconView.clipToOutline = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCellTouchListener(
        cell: View,
        iconView: ImageView,
        packageName: String,
        isShortcut: Boolean
    ) {
        val moveThresholdPx = context.dpToPx(10)
        val splitTriggerPx = context.dpToPx(80)
        val longPressTimeMs = 400L

        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false
        var hasMoved = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (!hasMoved && !isShortcut && !listener.isOnHomeScreen() && listener.isSplitScreenDragEnabled()) {
                longPressTriggered = true
                isDragging = true
                isDragActive = true
                // 드래그 오버레이 생성 먼저 (아이콘 drawable/위치 캡처에 VISIBLE 상태 필요)
                listener.onDragStateChanged(true, 0f)
                listener.onDragStart(iconView, startRawX, startRawY)
                // 드래그 시작 후 패널을 시각적으로만 숨기기 (터치 이벤트 계속 수신)
                hideVisually()
            }
        }

        cell.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    hasMoved = false
                    longPressTriggered = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    if (!isShortcut) {
                        view.postDelayed(longPressRunnable, longPressTimeMs)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startRawX
                    val deltaY = startRawY - event.rawY

                    if (!hasMoved && (abs(deltaX) > moveThresholdPx || abs(deltaY) > moveThresholdPx)) {
                        hasMoved = true
                        if (!longPressTriggered) {
                            view.removeCallbacks(longPressRunnable)
                        }
                    }

                    if (isDragging) {
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                        val rawProgress = (distance / splitTriggerPx).coerceAtLeast(0f)
                        // 아이콘 크기: distance 기반 (zone과 무관하게 부드럽게)
                        val scale = 1f + (rawProgress.coerceAtMost(1f) * 0.3f)
                        listener.onDragIconUpdate(event.rawX, event.rawY, scale)
                        // 오버레이: zone 밖이면 서서히 사라짐
                        val zoneFactor = splitZoneFactor(event.rawX, event.rawY)
                        listener.onDragStateChanged(true, rawProgress * zoneFactor)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)

                    if (isDragging) {
                        val deltaX = event.rawX - startRawX
                        val deltaY = startRawY - event.rawY
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                        val zoneFactor = splitZoneFactor(event.rawX, event.rawY)
                        val shouldLaunchSplit = distance > splitTriggerPx && zoneFactor > 0.5f

                        listener.onDragStateChanged(false, 0f)
                        listener.onDragEnd()

                        if (distance > splitTriggerPx && !shouldLaunchSplit) {
                            Toast.makeText(context, R.string.split_screen_zone_cancelled, Toast.LENGTH_SHORT).show()
                        }

                        // 드래그 종료 → 패널 실제 제거
                        finishDrag()

                        if (shouldLaunchSplit) {
                            listener.onAppDraggedToSplit(packageName)
                        }
                    } else if (!hasMoved && !longPressTriggered) {
                        listener.onAppTapped(packageName)
                        hide(reason = "app_tap")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        listener.onDragStateChanged(false, 0f)
                        listener.onDragEnd()
                        finishDrag()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createAddButton(iconSizePx: Int, paddingPx: Int): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val primaryColor = resolveThemeColor(
            com.google.android.material.R.attr.colorPrimary,
            0xFF6750A4.toInt()
        )
        val onPrimaryColor = resolveThemeColor(
            com.google.android.material.R.attr.colorOnPrimary,
            0xFFFFFFFF.toInt()
        )

        // Material 3 스타일 + 아이콘을 Canvas로 직접 그림
        val addView = object : View(context) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryColor
            }
            private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = onPrimaryColor
                strokeWidth = context.dpToPx(2).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val radius = (width.coerceAtMost(height)) / 2f
                canvas.drawCircle(cx, cy, radius, bgPaint)
                val armLen = radius * 0.4f
                canvas.drawLine(cx - armLen, cy, cx + armLen, cy, plusPaint)
                canvas.drawLine(cx, cy - armLen, cx, cy + armLen, plusPaint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            setOnClickListener {
                hide(reason = "add_button")
                listener.onAddAppRequested()
            }
        }

        cell.addView(addView)
        cell.addView(TextView(context).apply {
            text = ""
            textSize = LABEL_TEXT_SIZE_SP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(4) }
        })

        return cell
    }

    /**
     * 손가락 위치의 분할화면 영역 진입도 (0.0 ~ 1.0)
     * 가로: 오른쪽 절반이 split zone, 경계 근처에서 부드럽게 전환
     * 세로: 아래쪽 절반이 split zone
     * 0.0 = 완전히 영역 밖, 1.0 = 완전히 영역 안
     */
    private fun splitZoneFactor(rawX: Float, rawY: Float): Float {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val isLandscape = screenWidth > screenHeight

        // 경계 전환 구간: 화면 크기의 10% (부드러운 전환용)
        val transitionPx = if (isLandscape) screenWidth * 0.1f else screenHeight * 0.1f
        val midPoint = if (isLandscape) screenWidth / 2f else screenHeight / 2f
        val pos = if (isLandscape) rawX else rawY

        // midPoint 기준: 이전 transitionPx/2 ~ 이후 transitionPx/2 구간에서 0→1 전환
        return ((pos - midPoint + transitionPx / 2f) / transitionPx).coerceIn(0f, 1f)
    }

    private fun resolveThemeColor(attrResId: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrResId, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }
}
