package com.minsoo.ultranavbar.model

import android.view.KeyEvent

/**
 * 펜 버튼 액션 타입
 */
enum class PenButtonActionType {
    NONE,           // 비활성화
    APP,            // 앱 실행
    SHORTCUT,       // 앱 바로가기
    PAINT_FUNCTION  // 페인팅 기능
}

/**
 * 페인팅 앱 표준 기능
 */
enum class PaintFunction(
    val displayNameKey: String,
    val keyCode: Int,
    val metaState: Int = 0
) {
    UNDO("pen_paint_function_undo", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
    REDO("pen_paint_function_redo", KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
    ERASER_TOGGLE("pen_paint_function_eraser", KeyEvent.KEYCODE_E, 0),
    BRUSH_SIZE_UP("pen_paint_function_brush_up", KeyEvent.KEYCODE_RIGHT_BRACKET, 0),
    BRUSH_SIZE_DOWN("pen_paint_function_brush_down", KeyEvent.KEYCODE_LEFT_BRACKET, 0),
    COLOR_PICKER("pen_paint_function_color_picker", KeyEvent.KEYCODE_I, 0);

    companion object {
        fun fromName(name: String?): PaintFunction? {
            if (name == null) return null
            return try {
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * 펜 버튼 설정 데이터 클래스
 */
data class PenButtonConfig(
    val actionType: PenButtonActionType,
    val appPackage: String? = null,
    val appActivity: String? = null,
    val shortcutPackage: String? = null,
    val shortcutId: String? = null,
    val paintFunction: PaintFunction? = null
)
