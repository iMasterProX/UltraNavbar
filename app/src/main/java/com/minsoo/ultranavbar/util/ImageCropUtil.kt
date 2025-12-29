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

    // 저장 파일명
    const val LANDSCAPE_BG_FILENAME = "navbar_bg_landscape.png"
    const val PORTRAIT_BG_FILENAME = "navbar_bg_portrait.png"

    /**
     * URI에서 이미지를 로드하고 하단 72px를 크롭하여 저장
     *
     * @param context Context
     * @param uri 이미지 URI
     * @param isLandscape 가로 모드 여부
     * @return 저장 성공 여부
     */
    fun cropAndSaveFromUri(context: Context, uri: Uri, isLandscape: Boolean): Boolean {
        return try {
            // 이미지 로드
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI")
                return false
            }

            val result = cropAndSave(context, originalBitmap, isLandscape)
            originalBitmap.recycle()
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error cropping image from URI", e)
            false
        }
    }

    /**
     * 비트맵에서 하단 72px를 크롭하여 저장
     */
    fun cropAndSave(context: Context, bitmap: Bitmap, isLandscape: Boolean): Boolean {
        return try {
            val cropHeight = SettingsManager.CROP_HEIGHT_PX
            val width = bitmap.width
            val height = bitmap.height

            // 하단에서 72px 크롭
            // Int - Int 연산 명시
            val y = (height - cropHeight).coerceAtLeast(0)
            val actualCropHeight = minOf(cropHeight, height)

            Log.d(TAG, "Cropping: original=${width}x${height}, crop y=$y, cropHeight=$actualCropHeight")

            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                0,                  // x
                y,                  // y
                width,              // width
                actualCropHeight    // height
            )

            // 내부 저장소에 저장
            val filename = if (isLandscape) LANDSCAPE_BG_FILENAME else PORTRAIT_BG_FILENAME
            val file = File(context.filesDir, filename)
            val outputStream = FileOutputStream(file)
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()
            croppedBitmap.recycle()

            // 설정에 파일명 저장
            val settings = SettingsManager.getInstance(context)
            if (isLandscape) {
                settings.homeBgLandscape = filename
            } else {
                settings.homeBgPortrait = filename
            }

            Log.i(TAG, "Saved cropped image: $filename (${width}x${actualCropHeight})")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error cropping and saving image", e)
            false
        }
    }

    /**
     * 저장된 배경 이미지 로드
     */
    fun loadBackgroundBitmap(context: Context, isLandscape: Boolean): Bitmap? {
        return try {
            val filename = if (isLandscape) LANDSCAPE_BG_FILENAME else PORTRAIT_BG_FILENAME
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
     */
    fun hasBackgroundImage(context: Context, isLandscape: Boolean): Boolean {
        val filename = if (isLandscape) LANDSCAPE_BG_FILENAME else PORTRAIT_BG_FILENAME
        return File(context.filesDir, filename).exists()
    }

    /**
     * 배경 이미지 삭제
     */
    fun deleteBackgroundImage(context: Context, isLandscape: Boolean): Boolean {
        val filename = if (isLandscape) LANDSCAPE_BG_FILENAME else PORTRAIT_BG_FILENAME
        val file = File(context.filesDir, filename)

        val settings = SettingsManager.getInstance(context)
        if (isLandscape) {
            settings.homeBgLandscape = null
        } else {
            settings.homeBgPortrait = null
        }

        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }
}