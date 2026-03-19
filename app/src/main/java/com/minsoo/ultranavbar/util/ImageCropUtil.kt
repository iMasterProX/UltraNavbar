package com.minsoo.ultranavbar.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.minsoo.ultranavbar.settings.SettingsManager
import java.io.File
import java.io.FileOutputStream

/**
 * 스크린샷에서 하단 영역을 크롭하여 네비바 배경으로 사용
 *
 * 크롭 높이 정책:
 *  일반 모드  : 시스템 네비게이션 바 높이 (기기별 다름, 예: 72px)
 *  QQPlus 홈 : QQPLUS_NAVBAR_HEIGHT_PX (120px 고정)
 *
 *  cropAndSave() 는 두 버전을 동시에 저장하고,
 *  loadBackgroundBitmap() 은 isQQPlus 파라미터로 어느 버전을 불러올지 결정한다.
 */
object ImageCropUtil {

    private const val TAG = "ImageCropUtil"
    private const val FALLBACK_CROP_HEIGHT_PX = 72

    /** QQPlus 런처 홈화면 전용 네비바 높이 (고정값) */
    const val QQPLUS_NAVBAR_HEIGHT_PX = 120

    // 일반 배경 파일명
    const val LANDSCAPE_BG_FILENAME       = "navbar_bg_landscape.png"
    const val PORTRAIT_BG_FILENAME        = "navbar_bg_portrait.png"

    // 다크 모드 전용 배경 파일명
    const val DARK_LANDSCAPE_BG_FILENAME  = "navbar_bg_dark_landscape.png"
    const val DARK_PORTRAIT_BG_FILENAME   = "navbar_bg_dark_portrait.png"

    // QQPlus 런처 홈화면 전용 배경 파일명 (120px 크롭)
    const val QQPLUS_LANDSCAPE_BG_FILENAME      = "navbar_bg_qqplus_landscape.png"
    const val QQPLUS_PORTRAIT_BG_FILENAME       = "navbar_bg_qqplus_portrait.png"

    // QQPlus 런처 홈화면 전용 다크 배경 파일명
    const val QQPLUS_DARK_LANDSCAPE_BG_FILENAME = "navbar_bg_qqplus_dark_landscape.png"
    const val QQPLUS_DARK_PORTRAIT_BG_FILENAME  = "navbar_bg_qqplus_dark_portrait.png"

    // ===== 내부 유틸 =====

    private fun getNavigationBarHeight(context: Context, isLandscape: Boolean): Int {
        val res = context.resources
        val resName = if (isLandscape) "navigation_bar_height_landscape" else "navigation_bar_height"
        val id = res.getIdentifier(resName, "dimen", "android")
        return if (id > 0) {
            res.getDimensionPixelSize(id)
        } else {
            Log.w(TAG, "Navigation bar height not found, using fallback: $FALLBACK_CROP_HEIGHT_PX")
            FALLBACK_CROP_HEIGHT_PX
        }
    }

    /**
     * 파일명 결정
     * @param isQQPlus QQPlus 런처 홈화면 전용 여부 (120px 크롭)
     */
    private fun getFilename(isLandscape: Boolean, isDarkMode: Boolean, isQQPlus: Boolean = false): String {
        return when {
            isQQPlus &&  isDarkMode &&  isLandscape -> QQPLUS_DARK_LANDSCAPE_BG_FILENAME
            isQQPlus &&  isDarkMode && !isLandscape -> QQPLUS_DARK_PORTRAIT_BG_FILENAME
            isQQPlus && !isDarkMode &&  isLandscape -> QQPLUS_LANDSCAPE_BG_FILENAME
            isQQPlus && !isDarkMode && !isLandscape -> QQPLUS_PORTRAIT_BG_FILENAME
            isDarkMode &&  isLandscape              -> DARK_LANDSCAPE_BG_FILENAME
            isDarkMode && !isLandscape              -> DARK_PORTRAIT_BG_FILENAME
            !isDarkMode &&  isLandscape             -> LANDSCAPE_BG_FILENAME
            else                                    -> PORTRAIT_BG_FILENAME
        }
    }

    // ===== 저장 =====

    /**
     * URI에서 이미지를 로드하고 하단을 크롭하여 저장.
     * 일반 버전(시스템 네비바 높이)과 QQPlus 버전(120px)을 동시에 저장한다.
     */
    fun cropAndSaveFromUri(
        context: Context,
        uri: Uri,
        isLandscape: Boolean,
        isDarkMode: Boolean = false
    ): Boolean {
        return try {
            val originalBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI")
                return false
            }
            val result = cropAndSave(context, originalBitmap, isLandscape, isDarkMode)
            originalBitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping image from URI", e)
            false
        }
    }

    /**
     * 비트맵에서 하단을 크롭하여 저장.
     *  - 일반 버전 (시스템 네비바 높이) → 기존 파일명에 저장
     *  - QQPlus 버전 (120px 고정)       → qqplus_* 파일명에 저장
     */
    fun cropAndSave(
        context: Context,
        bitmap: Bitmap,
        isLandscape: Boolean,
        isDarkMode: Boolean = false
    ): Boolean {
        return try {
            val width  = bitmap.width
            val height = bitmap.height

            // 1) 일반 버전 저장 (시스템 네비바 높이)
            val normalH = getNavigationBarHeight(context, isLandscape)
            val normalY = (height - normalH).coerceAtLeast(0)
            val normalActualH = minOf(normalH, height)
            Log.d(TAG, "Normal crop: ${width}x${height} y=$normalY h=$normalActualH dark=$isDarkMode land=$isLandscape")
            val normalBmp = Bitmap.createBitmap(bitmap, 0, normalY, width, normalActualH)
            val normalFile = getFilename(isLandscape, isDarkMode, isQQPlus = false)
            saveBitmapToFile(context, normalBmp, normalFile)
            normalBmp.recycle()

            val settings = SettingsManager.getInstance(context)
            if (isDarkMode) {
                if (isLandscape) settings.homeBgDarkLandscape = normalFile
                else             settings.homeBgDarkPortrait  = normalFile
            } else {
                if (isLandscape) settings.homeBgLandscape = normalFile
                else             settings.homeBgPortrait  = normalFile
            }
            Log.i(TAG, "Saved normal crop: $normalFile (${width}x${normalActualH})")

            // 2) QQPlus 버전 저장 (120px 고정)
            val qqH = QQPLUS_NAVBAR_HEIGHT_PX
            val qqY = (height - qqH).coerceAtLeast(0)
            val qqActualH = minOf(qqH, height)
            Log.d(TAG, "QQPlus crop: ${width}x${height} y=$qqY h=$qqActualH (${qqH}px fixed)")
            val qqBmp  = Bitmap.createBitmap(bitmap, 0, qqY, width, qqActualH)
            val qqFile = getFilename(isLandscape, isDarkMode, isQQPlus = true)
            saveBitmapToFile(context, qqBmp, qqFile)
            qqBmp.recycle()
            Log.i(TAG, "Saved QQPlus crop: $qqFile (${width}x${qqActualH})")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping and saving image", e)
            false
        }
    }

    private fun saveBitmapToFile(context: Context, bitmap: Bitmap, filename: String) {
        FileOutputStream(File(context.filesDir, filename)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ===== 로드 =====

    /**
     * 저장된 배경 이미지 로드.
     * @param isQQPlus true 시 qqplus_* 파일 우선 로드, 없으면 일반 파일로 폴백.
     */
    fun loadBackgroundBitmap(
        context: Context,
        isLandscape: Boolean,
        isDarkMode: Boolean = false,
        isQQPlus: Boolean = false
    ): Bitmap? {
        return try {
            if (isQQPlus) {
                val qqFile = File(context.filesDir, getFilename(isLandscape, isDarkMode, isQQPlus = true))
                if (qqFile.exists()) {
                    Log.d(TAG, "Loading QQPlus background: ${qqFile.name}")
                    return BitmapFactory.decodeFile(qqFile.absolutePath)
                }
                Log.d(TAG, "QQPlus background not found (${qqFile.name}), falling back to normal")
            }
            val file = File(context.filesDir, getFilename(isLandscape, isDarkMode, isQQPlus = false))
            if (!file.exists()) {
                Log.d(TAG, "Background image not found: ${file.name}")
                return null
            }
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading background image", e)
            null
        }
    }

    // ===== 존재 확인 =====

    fun hasBackgroundImage(
        context: Context,
        isLandscape: Boolean,
        isDarkMode: Boolean = false,
        isQQPlus: Boolean = false
    ): Boolean = File(context.filesDir, getFilename(isLandscape, isDarkMode, isQQPlus)).exists()

    // ===== 삭제 =====

    /**
     * 배경 이미지 삭제. 일반 + QQPlus 파일을 함께 삭제한다.
     */
    fun deleteBackgroundImage(
        context: Context,
        isLandscape: Boolean,
        isDarkMode: Boolean = false
    ): Boolean {
        val settings = SettingsManager.getInstance(context)
        if (isDarkMode) {
            if (isLandscape) settings.homeBgDarkLandscape = null
            else             settings.homeBgDarkPortrait  = null
        } else {
            if (isLandscape) settings.homeBgLandscape = null
            else             settings.homeBgPortrait  = null
        }

        val normalFile = File(context.filesDir, getFilename(isLandscape, isDarkMode, isQQPlus = false))
        val qqFile     = File(context.filesDir, getFilename(isLandscape, isDarkMode, isQQPlus = true))

        val normalDeleted = if (normalFile.exists()) normalFile.delete() else true
        val qqDeleted     = if (qqFile.exists())     qqFile.delete()     else true
        if (!qqDeleted) Log.w(TAG, "Failed to delete QQPlus background: ${qqFile.name}")

        return normalDeleted && qqDeleted
    }
}
