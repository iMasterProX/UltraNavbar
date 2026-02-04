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
 */
object ImageCropUtil {

    private const val TAG = "ImageCropUtil"
    private const val FALLBACK_CROP_HEIGHT_PX = 72

    // 저장 파일명
    const val LANDSCAPE_BG_FILENAME = "navbar_bg_landscape.png"
    const val PORTRAIT_BG_FILENAME = "navbar_bg_portrait.png"

    // 다크 모드 전용 배경 파일명
    const val DARK_LANDSCAPE_BG_FILENAME = "navbar_bg_dark_landscape.png"
    const val DARK_PORTRAIT_BG_FILENAME = "navbar_bg_dark_portrait.png"

    /**
     * 기기의 실제 네비게이션 바 높이를 가져옴
     */
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
     * URI에서 이미지를 로드하고 하단 72px를 크롭하여 저장
     *
     * @param context Context
     * @param uri 이미지 URI
     * @param isLandscape 가로 모드 여부
     * @param isDarkMode 다크 모드 전용 배경 여부
     * @return 저장 성공 여부
     */
    fun cropAndSaveFromUri(context: Context, uri: Uri, isLandscape: Boolean, isDarkMode: Boolean = false): Boolean {
        return try {
            // 이미지 로드
            val originalBitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
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
     * 비트맵에서 하단 네비바 높이만큼 크롭하여 저장
     * @param isDarkMode 다크 모드 전용 배경 여부
     */
    fun cropAndSave(context: Context, bitmap: Bitmap, isLandscape: Boolean, isDarkMode: Boolean = false): Boolean {
        return try {
            val cropHeight = getNavigationBarHeight(context, isLandscape)
            val width = bitmap.width
            val height = bitmap.height

            // 하단에서 네비바 높이만큼 크롭
            val y = (height - cropHeight).coerceAtLeast(0)
            val actualCropHeight = minOf(cropHeight, height)

            Log.d(TAG, "Cropping: original=${width}x${height}, crop y=$y, cropHeight=$actualCropHeight (navbar height for ${if (isLandscape) "landscape" else "portrait"}), isDarkMode=$isDarkMode")

            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                0,                  // x
                y,                  // y
                width,              // width
                actualCropHeight    // height
            )

            // 내부 저장소에 저장
            val filename = getFilename(isLandscape, isDarkMode)
            val file = File(context.filesDir, filename)
            FileOutputStream(file).use { outputStream ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            croppedBitmap.recycle()

            // 설정에 파일명 저장
            val settings = SettingsManager.getInstance(context)
            if (isDarkMode) {
                if (isLandscape) {
                    settings.homeBgDarkLandscape = filename
                } else {
                    settings.homeBgDarkPortrait = filename
                }
            } else {
                if (isLandscape) {
                    settings.homeBgLandscape = filename
                } else {
                    settings.homeBgPortrait = filename
                }
            }

            Log.i(TAG, "Saved cropped image: $filename (${width}x${actualCropHeight})")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error cropping and saving image", e)
            false
        }
    }

    /**
     * 파일명 결정
     */
    private fun getFilename(isLandscape: Boolean, isDarkMode: Boolean): String {
        return when {
            isDarkMode && isLandscape -> DARK_LANDSCAPE_BG_FILENAME
            isDarkMode && !isLandscape -> DARK_PORTRAIT_BG_FILENAME
            !isDarkMode && isLandscape -> LANDSCAPE_BG_FILENAME
            else -> PORTRAIT_BG_FILENAME
        }
    }

    /**
     * 저장된 배경 이미지 로드
     * @param isDarkMode 다크 모드 전용 배경 로드 여부
     */
    fun loadBackgroundBitmap(context: Context, isLandscape: Boolean, isDarkMode: Boolean = false): Bitmap? {
        return try {
            val filename = getFilename(isLandscape, isDarkMode)
            val file = File(context.filesDir, filename)

            if (!file.exists()) {
                Log.d(TAG, "Background image not found: $filename")
                return null
            }

            BitmapFactory.decodeFile(file.absolutePath)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading background image", e)
            null
        }
    }

    /**
     * 배경 이미지 존재 여부 확인
     * @param isDarkMode 다크 모드 전용 배경 확인 여부
     */
    fun hasBackgroundImage(context: Context, isLandscape: Boolean, isDarkMode: Boolean = false): Boolean {
        val filename = getFilename(isLandscape, isDarkMode)
        return File(context.filesDir, filename).exists()
    }

    /**
     * 배경 이미지 삭제
     * @param isDarkMode 다크 모드 전용 배경 삭제 여부
     */
    fun deleteBackgroundImage(context: Context, isLandscape: Boolean, isDarkMode: Boolean = false): Boolean {
        val filename = getFilename(isLandscape, isDarkMode)
        val file = File(context.filesDir, filename)

        val settings = SettingsManager.getInstance(context)
        if (isDarkMode) {
            if (isLandscape) {
                settings.homeBgDarkLandscape = null
            } else {
                settings.homeBgDarkPortrait = null
            }
        } else {
            if (isLandscape) {
                settings.homeBgLandscape = null
            } else {
                settings.homeBgPortrait = null
            }
        }

        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }
}