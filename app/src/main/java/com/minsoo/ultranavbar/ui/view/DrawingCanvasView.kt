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

    private var currentPath = Path()
    private val paths = mutableListOf<PathData>()
    private val undonePaths = mutableListOf<PathData>()

    private var currentX = 0f
    private var currentY = 0f
    private var isEraserMode = false
    private var brushSize = 10f

    data class PathData(
        val path: Path,
        val color: Int,
        val width: Float,
        val isEraser: Boolean
    )

    fun setPaintColor(color: Int) {
        paint.color = color
    }

    fun setEraserMode(enabled: Boolean) {
        isEraserMode = enabled
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
            invalidate()
        }
    }

    fun redo() {
        if (undonePaths.isNotEmpty()) {
            paths.add(undonePaths.removeAt(undonePaths.size - 1))
            invalidate()
        }
    }

    fun clear() {
        paths.clear()
        undonePaths.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 저장된 경로들 그리기
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

        // 현재 그리는 경로
        paint.color = if (isEraserMode) Color.TRANSPARENT else paint.color
        paint.strokeWidth = brushSize
        if (isEraserMode) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            paint.xfermode = null
        }
        canvas.drawPath(currentPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPath.moveTo(x, y)
                currentX = x
                currentY = y
                undonePaths.clear() // 새로 그리기 시작하면 redo 기록 삭제
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.quadTo(currentX, currentY, (x + currentX) / 2, (y + currentY) / 2)
                currentX = x
                currentY = y
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(currentX, currentY)
                paths.add(PathData(currentPath, paint.color, brushSize, isEraserMode))
                currentPath = Path()
            }
        }

        invalidate()
        return true
    }
}
