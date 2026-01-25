package com.minsoo.ultranavbar.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object WallpaperProcessor {

    private const val TAG = "WallpaperProcessor"

    /**
     * 배경화면에서 네비바 배경 이미지 생성
     * @param isDarkMode 다크 모드 전용 배경 생성 여부
     */
    suspend fun generate(context: Context, isLandscape: Boolean, isDarkMode: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperDrawable = wallpaperManager.drawable

            if (wallpaperDrawable == null) {
                Log.w(TAG, "Wallpaper drawable is null, cannot generate background.")
                return@withContext false
            }

            // 실제 디스플레이 크기를 가져와서 방향에 맞게 계산
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val realScreenWidth = bounds.width()
            val realScreenHeight = bounds.height()

            val targetWidth = if (isLandscape) realScreenWidth else realScreenHeight
            val targetHeight = if (isLandscape) realScreenHeight else realScreenWidth

            // 1. Drawable을 목표 크기의 Bitmap으로 변환
            val wallpaperBitmap = wallpaperDrawable.toBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

            // 2. Scrim 그라데이션 적용
            val resultBitmap = applyScrim(wallpaperBitmap, targetHeight)

            // 3. 크롭 및 저장 (다크 모드 여부 전달)
            val success = ImageCropUtil.cropAndSave(context, resultBitmap, isLandscape, isDarkMode)

            // 사용된 비트맵 메모리 해제
            wallpaperBitmap.recycle()
            resultBitmap.recycle()

            Log.d(TAG, "Generated background: isLandscape=$isLandscape, isDarkMode=$isDarkMode, success=$success")
            return@withContext success
        } catch (e: IOException) {
            Log.e(TAG, "Failed to generate wallpaper background", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during generation", e)
            return@withContext false
        }
    }

    /**
     * 비트맵에 상단 및 하단 scrim 그라데이션을 적용
     */
    private fun applyScrim(source: Bitmap, height: Int): Bitmap {
        val resultBitmap = source.copy(source.config, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint()

        // 상단 그라데이션 (25% 높이, 20% 불투명도의 검은색 -> 투명)
        val topShader = LinearGradient(
            0f, 0f, 0f, height * 0.25f,
            Color.argb(51, 0, 0, 0), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        paint.shader = topShader
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), height * 0.25f, paint)

        // 하단 그라데이션 (25% 높이, 20% 불투명도의 검은색 -> 투명)
        val bottomShader = LinearGradient(
            0f, height * 0.75f, 0f, height.toFloat(),
            Color.TRANSPARENT, Color.argb(51, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
        paint.shader = bottomShader
        canvas.drawRect(0f, height * 0.75f, canvas.width.toFloat(), height.toFloat(), paint)

        return resultBitmap
    }
}
