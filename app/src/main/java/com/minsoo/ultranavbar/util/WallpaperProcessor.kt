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

    suspend fun generate(context: Context, isLandscape: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperDrawable = wallpaperManager.drawable

            if (wallpaperDrawable == null) {
                Log.w(TAG, "Wallpaper drawable is null, cannot generate background.")
                return@withContext false
            }

            
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val realScreenWidth = bounds.width()
            val realScreenHeight = bounds.height()

            val targetWidth = if (isLandscape) realScreenWidth else realScreenHeight
            val targetHeight = if (isLandscape) realScreenHeight else realScreenWidth
            
            

            
            val resultBitmap = applyScrim(wallpaperBitmap, targetHeight)
            
            

            
            wallpaperBitmap.recycle()
            resultBitmap.recycle()

            return@withContext success
        } catch (e: IOException) {
            Log.e(TAG, "Failed to generate wallpaper background", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during generation", e)
            return@withContext false
        }
    }

    
    private fun applyScrim(source: Bitmap, height: Int): Bitmap {
        val resultBitmap = source.copy(source.config, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint()

        
        val topShader = LinearGradient(
            0f, 0f, 0f, height * 0.25f,
            Color.argb(51, 0, 0, 0), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        paint.shader = topShader
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), height * 0.25f, paint)

        
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
