package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 자동 터치 포인트 설정 Activity
 *
 * UX 플로우:
 * 1단계: 플로팅 버튼 표시 → 사용자가 원하는 앱으로 이동
 * 2단계: 플로팅 버튼 탭 → 터치 위치 지정 오버레이 표시
 * 3단계: 화면 탭 → 파란 원으로 위치 표시
 * 4단계: 확인 버튼 탭 → 좌표 저장
 */
class TouchPointSetupActivity : Activity() {

    companion object {
        const val EXTRA_BUTTON = "button" // "A" or "B"
        const val EXTRA_DIRECT_START = "direct_start"
        private const val INDICATOR_SIZE = 60 // 파란 원 크기 (px)
    }

    private lateinit var settings: SettingsManager
    private var buttonName: String = "A"
    private var floatingButton: View? = null
    private var overlayView: View? = null
    private var indicatorView: View? = null

    // 선택된 좌표
    private var selectedX: Float = -1f
    private var selectedY: Float = -1f

    // 확인 버튼 참조
    private var confirmButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsManager.getInstance(this)
        buttonName = intent.getStringExtra(EXTRA_BUTTON) ?: "A"
        val directStart = intent.getBooleanExtra(EXTRA_DIRECT_START, false)

        if (directStart) {
            // 재설정 직진입: 바로 좌표 선택 단계
            showTouchPointOverlay()
        } else {
            // 1단계: 플로팅 버튼 표시
            showFloatingButton()
        }

        // Activity는 투명하게 유지하고 백그라운드로
        moveTaskToBack(true)
    }

    /**
     * 1단계: 플로팅 버튼 표시
     */
    private fun showFloatingButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 플로팅 버튼 컨테이너
        val container = FrameLayout(this)

        // 원형 버튼
        val button = FrameLayout(this).apply {
            val size = 140
            layoutParams = FrameLayout.LayoutParams(size, size)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2196F3"))
                setStroke(4, Color.WHITE)
            }
            background = bg
            elevation = 8f
        }

        // 버튼 텍스트
        val buttonText = TextView(this).apply {
            text = "버튼$buttonName\n위치지정"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        button.addView(buttonText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // 취소 버튼 (X)
        val cancelButton = FrameLayout(this).apply {
            val size = 40
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = -4
                topMargin = -4
            }

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F44336"))
            }
            background = bg
            elevation = 10f
        }

        val cancelText = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        cancelButton.addView(cancelText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 메인 컨테이너에 버튼들 추가
        val mainContainer = FrameLayout(this)
        mainContainer.addView(button)
        mainContainer.addView(cancelButton)

        container.addView(mainContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // 버튼 클릭 → 2단계로 전환
        button.setOnClickListener {
            removeFloatingButton()
            showTouchPointOverlay()
        }

        // 취소 버튼 클릭
        cancelButton.setOnClickListener {
            cleanup()
            finish()
        }

        // 드래그 가능하게 설정
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (dx * dx + dy * dy > 100) { // 10px 이상 이동시 드래그
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    wm.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, params)
            floatingButton = container

            Toast.makeText(this,
                "원하는 앱으로 이동한 후\n파란 버튼을 탭하세요",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 2단계: 터치 위치 지정 오버레이
     */
    private fun showTouchPointOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 전체 화면을 덮는 반투명 오버레이 (투명도 높임)
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(60, 0, 0, 0))  // 120 → 60 (더 투명하게)
        }

        // 안내 텍스트 (상단)
        val instructionText = TextView(this).apply {
            text = "버튼 $buttonName 자동 터치 위치를 탭하세요"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.argb(100, 0, 0, 0))  // 200 → 100 (더 투명하게)
        }

        val textParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        container.addView(instructionText, textParams)

        // 버튼 컨테이너 (하단)
        val buttonContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(100, 0, 0, 0))  // 200 → 100 (더 투명하게)
            setPadding(24, 20, 24, 20)
        }

        // 확인 버튼 (처음에는 비활성)
        val confirmText = TextView(this).apply {
            text = getString(R.string.touch_point_confirm)
            setTextColor(Color.argb(100, 255, 255, 255))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            val bg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.argb(100, 33, 150, 243))
            }
            background = bg
            isEnabled = false
            setOnClickListener {
                if (selectedX >= 0 && selectedY >= 0) {
                    saveTouchPoint(selectedX, selectedY)
                }
            }
        }
        confirmButton = confirmText

        val confirmParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        buttonContainer.addView(confirmText, confirmParams)

        // 취소 버튼 (왼쪽)
        val cancelText = TextView(this).apply {
            text = getString(R.string.touch_point_cancel)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                cleanup()
                finish()
            }
        }

        val cancelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        buttonContainer.addView(cancelText, cancelParams)

        val buttonContainerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        container.addView(buttonContainer, buttonContainerParams)

        // 터치 이벤트 처리
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX
                val y = event.rawY
                updateIndicatorPosition(x, y)
                true
            } else {
                false
            }
        }

        // 오버레이 파라미터
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 파란 원 인디케이터 위치 업데이트
     */
    private fun updateIndicatorPosition(x: Float, y: Float) {
        selectedX = x
        selectedY = y

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 기존 인디케이터 제거
        removeIndicator()

        // 새 인디케이터 생성
        val indicator = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 33, 150, 243))
                setStroke(4, Color.WHITE)
            }
            background = bg
        }

        val params = WindowManager.LayoutParams(
            INDICATOR_SIZE,
            INDICATOR_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - INDICATOR_SIZE / 2).toInt()
            this.y = (y - INDICATOR_SIZE / 2).toInt()
        }

        try {
            wm.addView(indicator, params)
            indicatorView = indicator

            // 확인 버튼 활성화
            enableConfirmButton()
        } catch (e: Exception) {
            android.util.Log.e("TouchPointSetup", "Failed to show indicator", e)
        }
    }

    /**
     * 확인 버튼 활성화
     */
    private fun enableConfirmButton() {
        confirmButton?.apply {
            setTextColor(Color.WHITE)
            isEnabled = true
            val bg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#2196F3"))
            }
            background = bg
        }
    }

    /**
     * 인디케이터 제거
     */
    private fun removeIndicator() {
        indicatorView?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
            indicatorView = null
        }
    }

    private fun saveTouchPoint(x: Float, y: Float) {
        if (buttonName == "A") {
            settings.penATouchX = x
            settings.penATouchY = y
            settings.penAActionType = "TOUCH_POINT"
        } else {
            settings.penBTouchX = x
            settings.penBTouchY = y
            settings.penBActionType = "TOUCH_POINT"
        }

        // 시스템 설정에 브릿지 Activity 등록
        registerBridgeActivityToSystem()

        // 저장 완료 메시지
        Toast.makeText(
            this,
            getString(R.string.touch_point_saved, buttonName, x.toInt(), y.toInt()),
            Toast.LENGTH_SHORT
        ).show()

        cleanup()
        setResult(RESULT_OK)
        finish()
    }

    /**
     * 브릿지 Activity를 시스템 설정에 등록
     */
    private fun registerBridgeActivityToSystem() {
        try {
            val bridgeActivity = if (buttonName == "A") {
                "com.minsoo.ultranavbar.ui.PenButtonABridgeActivity"
            } else {
                "com.minsoo.ultranavbar.ui.PenButtonBBridgeActivity"
            }
            val componentName = "com.minsoo.ultranavbar/$bridgeActivity"

            if (buttonName == "A") {
                Settings.Global.putString(contentResolver, "a_button_component_name", componentName)
                Settings.Global.putInt(contentResolver, "a_button_setting", 1)
            } else {
                Settings.Global.putString(contentResolver, "b_button_component_name", componentName)
                Settings.Global.putInt(contentResolver, "b_button_setting", 1)
            }

            android.util.Log.d("TouchPointSetup", "Registered bridge activity: $componentName")
        } catch (e: Exception) {
            android.util.Log.e("TouchPointSetup", "Failed to register bridge activity", e)
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
            floatingButton = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
            overlayView = null
        }
    }

    private fun cleanup() {
        removeIndicator()
        removeFloatingButton()
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onBackPressed() {
        cleanup()
        super.onBackPressed()
    }
}
