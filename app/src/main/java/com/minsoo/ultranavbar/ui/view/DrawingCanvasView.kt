package com.minsoo.ultranavbar.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 펜 테스트용 그리기 캔버스
 */
class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 10f
    }

    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private var currentPath = Path()
    private val paths = mutableListOf<PathData>()
    private val undonePaths = mutableListOf<PathData>()

    private var currentX = 0f
    private var currentY = 0f
    private var isEraserMode = false
    private var brushSize = 10f
    private var normalColor = Color.BLACK // 기본 브러시 색상 저장
    private var backgroundColor = Color.TRANSPARENT

    init {
        // 하드웨어 가속 비활성화 (CLEAR 모드를 위해)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    data class PathData(
        val path: Path,
        val color: Int,
        val width: Float,
        val isEraser: Boolean
    )

    fun setPaintColor(color: Int) {
        normalColor = color
        paint.color = color
    }

    fun setEraserMode(enabled: Boolean) {
        isEraserMode = enabled
        if (!enabled) {
            // 브러시 모드로 돌아갈 때 원래 색상 복원
            paint.color = normalColor
        }
    }

    fun increaseBrushSize() {
        brushSize = (brushSize + 5f).coerceAtMost(50f)
        paint.strokeWidth = brushSize
    }

    fun decreaseBrushSize() {
        brushSize = (brushSize - 5f).coerceAtLeast(5f)
        paint.strokeWidth = brushSize
    }

    fun getCurrentBrushSize(): Int = brushSize.toInt()

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeAt(paths.size - 1))
            redrawAllPaths()
            invalidate()
            android.util.Log.d("DrawingCanvasView", "Undo: paths size=${paths.size}, undone size=${undonePaths.size}")
        } else {
            android.util.Log.d("DrawingCanvasView", "Undo: No paths to undo")
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeAt(undonePaths.size - 1))
            redrawAllPaths()
            invalidate()
            android.util.Log.d("DrawingCanvasView", "Redo: paths size=${paths.size}, undone size=${undonePaths.size}")
        } else {
            android.util.Log.d("DrawingCanvasView", "Redo: No paths to redo")
        }
    }

    fun clear() {
        paths.clear()
        undonePaths.clear()
        // 비트맵 완전히 지우기
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
        android.util.Log.d("DrawingCanvasView", "Clear: all paths cleared")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawCanvas = Canvas(canvasBitmap!!)
            // 새 비트맵은 이미 투명하므로 별도 지우기 불필요
            redrawAllPaths()
            android.util.Log.d("DrawingCanvasView", "onSizeChanged: w=$w, h=$h, paths=${paths.size}")
        }
    }

    private fun redrawAllPaths() {
        drawCanvas?.let { canvas ->
            // 캔버스를 완전히 지우기 (TRANSPARENT drawColor는 아무것도 안함)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            android.util.Log.d("DrawingCanvasView", "redrawAllPaths: redrawing ${paths.size} paths")

            for (pathData in paths) {
                paint.color = pathData.color
                paint.strokeWidth = pathData.width

                if (pathData.isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    paint.xfermode = null
                }

                canvas.drawPath(pathData.path, paint)
            }
            paint.xfermode = null

            // 원래 색상 복원
            paint.color = normalColor
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 비트맵에 그린 내용 표시
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // 현재 그리는 경로 (실시간)
        paint.strokeWidth = brushSize
        if (isEraserMode) {
            paint.color = backgroundColor
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            paint.color = normalColor
            paint.xfermode = null
        }
        canvas.drawPath(currentPath, paint)
        paint.xfermode = null
    }

    // 드로잉 시작 위치 (실제 드로잉 여부 판단용)
    private var startX = 0f
    private var startY = 0f
    private var hasMoved = false
    private var buttonWasPressedDuringTouch = false  // 터치 중 버튼이 눌렸는지 추적

    /**
     * 스타일러스 사이드 버튼이 눌렸는지 확인
     * BUTTON_PRIMARY (1)은 펜촉이 화면에 닿은 것이므로 제외
     * BUTTON_SECONDARY (2), BUTTON_TERTIARY (4), STYLUS_PRIMARY (32), STYLUS_SECONDARY (64)만 체크
     */
    private fun isStylusButtonPressed(event: MotionEvent): Boolean {
        val buttonState = event.buttonState
        // 사이드 버튼 마스크: SECONDARY(2) | TERTIARY(4) | STYLUS_PRIMARY(32) | STYLUS_SECONDARY(64) = 102
        val sideButtonMask = MotionEvent.BUTTON_SECONDARY or
                             MotionEvent.BUTTON_TERTIARY or
                             MotionEvent.BUTTON_STYLUS_PRIMARY or
                             MotionEvent.BUTTON_STYLUS_SECONDARY
        return (buttonState and sideButtonMask) != 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 스타일러스 사이드 버튼이 눌린 상태인지 확인
        val isSideButtonPressed = isStylusButtonPressed(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 사이드 버튼이 눌린 상태로 시작하면 이 터치는 드로잉이 아님
                if (isSideButtonPressed) {
                    android.util.Log.d("DrawingCanvasView", "Ignoring: side button pressed on DOWN, buttonState=${event.buttonState}")
                    buttonWasPressedDuringTouch = true
                    return false
                }

                buttonWasPressedDuringTouch = false
                currentPath = Path()
                currentPath.moveTo(event.x, event.y)
                currentX = event.x
                currentY = event.y
                startX = event.x
                startY = event.y
                hasMoved = false
                undonePaths.clear() // 새로 그리기 시작하면 redo 기록 삭제
                android.util.Log.d("DrawingCanvasView", "ACTION_DOWN at (${event.x}, ${event.y}), buttonState=${event.buttonState}")
            }
            MotionEvent.ACTION_MOVE -> {
                // 터치 중간에 사이드 버튼이 눌리면 이 터치는 무효화
                if (isSideButtonPressed && !buttonWasPressedDuringTouch) {
                    android.util.Log.d("DrawingCanvasView", "Side button pressed during MOVE, invalidating touch")
                    buttonWasPressedDuringTouch = true
                }

                // 이미 버튼이 눌렸던 터치면 경로에 추가하지 않음
                if (buttonWasPressedDuringTouch) {
                    return true
                }

                val x = event.x
                val y = event.y

                // 최소 이동 거리 확인 (5픽셀 이상 움직여야 드로잉으로 인정)
                val dx = x - startX
                val dy = y - startY
                if (dx * dx + dy * dy > 25) { // 5^2 = 25
                    hasMoved = true
                }
                currentPath.quadTo(currentX, currentY, (x + currentX) / 2, (y + currentY) / 2)
                currentX = x
                currentY = y
            }
            MotionEvent.ACTION_UP -> {
                android.util.Log.d("DrawingCanvasView", "ACTION_UP: hasMoved=$hasMoved, buttonWasPressed=$buttonWasPressedDuringTouch, buttonState=${event.buttonState}")

                // 버튼이 눌렸던 터치이거나 움직임이 없으면 경로 저장 안함
                if (buttonWasPressedDuringTouch || !hasMoved) {
                    android.util.Log.d("DrawingCanvasView", "Discarding path: button=$buttonWasPressedDuringTouch, moved=$hasMoved")
                    currentPath = Path()
                    hasMoved = false
                    buttonWasPressedDuringTouch = false
                    invalidate()
                    return true
                }

                currentPath.lineTo(currentX, currentY)

                // 경로를 paths에 저장
                paths.add(PathData(currentPath, normalColor, brushSize, isEraserMode))
                android.util.Log.d("DrawingCanvasView", "Path added: paths size=${paths.size}")

                // 비트맵에도 그리기
                drawCanvas?.let { canvas ->
                    paint.strokeWidth = brushSize
                    if (isEraserMode) {
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    } else {
                        paint.color = normalColor
                        paint.xfermode = null
                    }
                    canvas.drawPath(currentPath, paint)
                    paint.xfermode = null
                }

                currentPath = Path()
                hasMoved = false
                buttonWasPressedDuringTouch = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // 취소 시 현재 경로 버리기
                android.util.Log.d("DrawingCanvasView", "ACTION_CANCEL")
                currentPath = Path()
                hasMoved = false
                buttonWasPressedDuringTouch = false
            }
        }

        invalidate()
        return true
    }
}
