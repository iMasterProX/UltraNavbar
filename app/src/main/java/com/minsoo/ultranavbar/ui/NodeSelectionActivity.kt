package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * UI 요소(노드) 선택 Activity
 *
 * 접근성 서비스 기반의 자동 클릭 기능을 위해
 * 접근성 노드 정보를 저장합니다. (권장)
 *
 * UX 플로우:
 * 1단계: 플로팅 버튼 표시 → 사용자가 원하는 앱으로 이동
 * 2단계: 플로팅 버튼 탭 → 노드 선택 오버레이 표시
 * 3단계: 화면 탭 → 해당 위치의 노드 정보 표시
 * 4단계: 확인 버튼 탭 → 노드 정보 저장
 */
class NodeSelectionActivity : Activity() {

    companion object {
        const val EXTRA_BUTTON = "button" // "A" or "B"
        private const val TAG = "NodeSelection"
    }

    private lateinit var settings: SettingsManager
    private var buttonName: String = "A"
    private var floatingButton: View? = null
    private var overlayView: View? = null

    // 선택된 노드 정보
    private var selectedNodeId: String? = null
    private var selectedNodeText: String? = null
    private var selectedNodeClass: String? = null
    private var selectedNodeDesc: String? = null
    private var selectedNodePackage: String? = null
    private var selectedNodeDisplayName: String? = null

    // UI 요소 참조
    private var confirmButton: TextView? = null
    private var nodeInfoText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsManager.getInstance(this)
        buttonName = intent.getStringExtra(EXTRA_BUTTON) ?: "A"

        // 접근성 서비스 확인
        if (!NavBarAccessibilityService.isRunning()) {
            Toast.makeText(this, R.string.node_selection_accessibility_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 1단계: 플로팅 버튼 표시
        showFloatingButton()

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
                setColor(Color.parseColor("#4CAF50")) // 녹색으로 구분
                setStroke(4, Color.WHITE)
            }
            background = bg
            elevation = 8f
        }

        // 버튼 텍스트
        val buttonText = TextView(this).apply {
            text = "버튼$buttonName\nUI선택"
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
            showNodeSelectionOverlay()
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
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
                getString(R.string.node_selection_instruction),
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 2단계: 노드 선택 오버레이
     */
    private fun showNodeSelectionOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 전체 화면을 덮는 반투명 오버레이
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(60, 0, 0, 0))
        }

        // 안내 텍스트 (상단)
        val instructionText = TextView(this).apply {
            text = getString(R.string.node_selection_overlay_hint)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.argb(100, 0, 0, 0))
        }

        val textParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        container.addView(instructionText, textParams)

        // 선택된 노드 정보 표시 (중앙)
        val infoText = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.argb(150, 33, 150, 243))
            visibility = View.GONE
        }
        nodeInfoText = infoText

        val infoParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        container.addView(infoText, infoParams)

        // 버튼 컨테이너 (하단)
        val buttonContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(100, 0, 0, 0))
            setPadding(24, 20, 24, 20)
        }

        // 확인 버튼 (처음에는 비활성)
        val confirmText = TextView(this).apply {
            text = getString(R.string.node_selection_confirm)
            setTextColor(Color.argb(100, 255, 255, 255))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            val bg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.argb(100, 76, 175, 80))
            }
            background = bg
            isEnabled = false
            setOnClickListener {
                if (selectedNodeId != null || selectedNodeText != null || selectedNodeDesc != null) {
                    saveNodeInfo()
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
            text = getString(R.string.node_selection_cancel)
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
                findNodeAtPosition(x, y)
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
     * 해당 위치의 접근성 노드 찾기
     */
    private fun findNodeAtPosition(x: Float, y: Float) {
        val service = NavBarAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, R.string.node_selection_accessibility_required, Toast.LENGTH_SHORT).show()
            return
        }

        // 루트 노드에서 시작하여 해당 위치의 노드 찾기
        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Toast.makeText(this, R.string.node_selection_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val targetNode = findClickableNodeAtPosition(rootNode, x.toInt(), y.toInt())
        rootNode.recycle()

        if (targetNode != null) {
            // 노드 정보 추출
            selectedNodeId = targetNode.viewIdResourceName
            selectedNodeText = targetNode.text?.toString()
            selectedNodeClass = targetNode.className?.toString()
            selectedNodeDesc = targetNode.contentDescription?.toString()
            selectedNodePackage = targetNode.packageName?.toString()

            // 표시용 이름 생성
            selectedNodeDisplayName = when {
                !selectedNodeText.isNullOrEmpty() -> "\"${selectedNodeText}\""
                !selectedNodeDesc.isNullOrEmpty() -> "[${selectedNodeDesc}]"
                !selectedNodeId.isNullOrEmpty() -> selectedNodeId!!.substringAfterLast("/")
                else -> selectedNodeClass?.substringAfterLast(".") ?: "Unknown"
            }

            // UI 업데이트
            updateNodeInfoDisplay()
            enableConfirmButton()

            targetNode.recycle()
        } else {
            Toast.makeText(this, R.string.node_selection_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 재귀적으로 해당 위치의 클릭 가능한 노드 찾기
     */
    private fun findClickableNodeAtPosition(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        // 해당 위치가 노드 범위 안에 있는지 확인
        if (!rect.contains(x, y)) {
            return null
        }

        // 자식 노드들 먼저 확인 (더 구체적인 노드 우선)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAtPosition(child, x, y)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }

        // 현재 노드가 클릭 가능하면 반환
        if (node.isClickable || node.isCheckable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        return null
    }

    /**
     * 노드 정보 표시 업데이트
     */
    private fun updateNodeInfoDisplay() {
        nodeInfoText?.apply {
            text = getString(R.string.node_selection_info, selectedNodeDisplayName)
            visibility = View.VISIBLE
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
                setColor(Color.parseColor("#4CAF50"))
            }
            background = bg
        }
    }

    /**
     * 노드 정보 저장
     */
    private fun saveNodeInfo() {
        if (buttonName == "A") {
            settings.penANodeId = selectedNodeId
            settings.penANodeText = selectedNodeText
            settings.penANodeClass = selectedNodeClass
            settings.penANodeDesc = selectedNodeDesc
            settings.penANodePackage = selectedNodePackage
            settings.penAActionType = "NODE_CLICK"
        } else {
            settings.penBNodeId = selectedNodeId
            settings.penBNodeText = selectedNodeText
            settings.penBNodeClass = selectedNodeClass
            settings.penBNodeDesc = selectedNodeDesc
            settings.penBNodePackage = selectedNodePackage
            settings.penBActionType = "NODE_CLICK"
        }

        // 브릿지 Activity를 시스템 설정에 등록
        registerBridgeActivityToSystem()

        Toast.makeText(
            this,
            getString(R.string.node_selection_saved, selectedNodeDisplayName),
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
                android.provider.Settings.Global.putString(contentResolver, "a_button_component_name", componentName)
                android.provider.Settings.Global.putInt(contentResolver, "a_button_setting", 1)
            } else {
                android.provider.Settings.Global.putString(contentResolver, "b_button_component_name", componentName)
                android.provider.Settings.Global.putInt(contentResolver, "b_button_setting", 1)
            }

            android.util.Log.d(TAG, "Registered bridge activity: $componentName")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to register bridge activity", e)
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
        removeFloatingButton()
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cleanup()
        super.onBackPressed()
    }
}
