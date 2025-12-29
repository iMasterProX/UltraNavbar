package com.minsoo.ultranavbar.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.minsoo.ultranavbar.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WallpaperPreviewActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_preview)

        imagePreview = findViewById(R.id.image_preview)
        progressBar = findViewById(R.id.progress_bar)

        // 스크린샷 안내 토스트 메시지 표시
        Toast.makeText(this, "지금 보이는 화면을 캡처하여 '이미지 선택'으로 지정해주세요.", Toast.LENGTH_LONG).show()

        loadWallpaperPreview()
    }

    private fun loadWallpaperPreview() {
        progressBar.visibility = View.VISIBLE
        imagePreview.visibility = View.INVISIBLE

        lifecycleScope.launch {
            val previewDrawable = generatePreviewDrawable()
            if (previewDrawable != null) {
                imagePreview.setImageDrawable(previewDrawable)
            } else {
                Toast.makeText(this@WallpaperPreviewActivity, "배경화면 미리보기를 불러오는데 실패했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
            progressBar.visibility = View.GONE
            imagePreview.visibility = View.VISIBLE
        }
    }

    private suspend fun generatePreviewDrawable(): LayerDrawable? = withContext(Dispatchers.IO) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this@WallpaperPreviewActivity)
            val wallpaperDrawable = wallpaperManager.drawable ?: return@withContext null

            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            // LG 울트라탭 해상도 기준
            val landscapeWidth = 2000
            val landscapeHeight = 1200
            
            val targetWidth = if (isLandscape) landscapeWidth else landscapeHeight
            val targetHeight = if (isLandscape) landscapeHeight else landscapeWidth
            
            val originalBitmap = wallpaperDrawable.toBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

            // QuickStep 줌 효과 시뮬레이션 (중앙 90% 확대)
            val zoomedBitmap = applyZoomCrop(originalBitmap)
            originalBitmap.recycle()

            val zoomedDrawable = BitmapDrawable(resources, zoomedBitmap)
            val bottomScrim = ContextCompat.getDrawable(this@WallpaperPreviewActivity, R.drawable.launcher_scrim_bottom_up)
            val topScrim = ContextCompat.getDrawable(this@WallpaperPreviewActivity, R.drawable.launcher_scrim_top_down)

            if (bottomScrim != null && topScrim != null) {
                return@withContext LayerDrawable(arrayOf(zoomedDrawable, bottomScrim, topScrim))
            } else {
                return@withContext LayerDrawable(arrayOf(zoomedDrawable))
            }
        } catch (e: Exception) {
            Log.e("WallpaperPreview", "Error generating preview drawable", e)
            return@withContext null
        }
    }

    private fun applyZoomCrop(source: Bitmap): Bitmap {
        val zoomFactor = 0.90f
        val newWidth = source.width * zoomFactor
        val newHeight = source.height * zoomFactor
        val left = (source.width - newWidth) / 2f
        val top = (source.height - newHeight) / 2f

        val srcRect = Rect(left.toInt(), top.toInt(), (left + newWidth).toInt(), (top + newHeight).toInt())
        val destRect = Rect(0, 0, source.width, source.height)

        val resultBitmap = Bitmap.createBitmap(source.width, source.height, source.config)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, srcRect, destRect, paint)
        return resultBitmap
    }
}
