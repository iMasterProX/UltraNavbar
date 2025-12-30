package com.minsoo.ultranavbar.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class WallpaperPreviewActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var previewControlsCard: View
    private lateinit var sliderPreviewFilterOverlay: Slider
    private lateinit var txtPreviewFilterOverlayValue: TextView

    private var previewFilterDrawable: BitmapDrawable? = null

    private data class PreviewLayer(val layer: LayerDrawable, val filterDrawable: BitmapDrawable?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_preview)

        hideBottomNavigationBar()

        imagePreview = findViewById(R.id.image_preview)
        previewControlsCard = findViewById(R.id.previewControlsCard)
        sliderPreviewFilterOverlay = findViewById(R.id.sliderPreviewFilterOverlay)
        txtPreviewFilterOverlayValue = findViewById(R.id.txtPreviewFilterOverlayValue)

        sliderPreviewFilterOverlay.valueFrom = 0f
        sliderPreviewFilterOverlay.valueTo = 100f
        sliderPreviewFilterOverlay.stepSize = 1f

        val initialOpacity = SettingsManager.getInstance(this).previewFilterOpacity
        sliderPreviewFilterOverlay.value = initialOpacity.toFloat()
        updatePreviewFilterOverlayValue(initialOpacity)

        sliderPreviewFilterOverlay.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            SettingsManager.getInstance(this).previewFilterOpacity = percent
            updatePreviewFilterOverlayValue(percent)
            updatePreviewFilterAlpha(percent)
        }

        imagePreview.setOnClickListener {
            togglePreviewControls()
        }

        Toast.makeText(
            this,
            "지금 보이는 화면을 캡처하여 '이미지 선택'으로 지정해주세요.",
            Toast.LENGTH_LONG
        ).show()

        loadWallpaperPreview()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBottomNavigationBar()
    }

    private fun hideBottomNavigationBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun loadWallpaperPreview() {
        imagePreview.visibility = View.INVISIBLE

        lifecycleScope.launch {
            val previewLayer = generatePreviewDrawable()
            if (previewLayer != null) {
                previewFilterDrawable = previewLayer.filterDrawable
                imagePreview.setImageDrawable(previewLayer.layer)
                updatePreviewFilterAlpha(SettingsManager.getInstance(this@WallpaperPreviewActivity).previewFilterOpacity)
                imagePreview.visibility = View.VISIBLE
            } else {
                Toast.makeText(
                    this@WallpaperPreviewActivity,
                    "배경화면 미리보기를 불러오는데 실패했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun togglePreviewControls() {
        previewControlsCard.visibility =
            if (previewControlsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun updatePreviewFilterOverlayValue(value: Int) {
        txtPreviewFilterOverlayValue.text = "${value}%"
    }

    private fun updatePreviewFilterAlpha(value: Int) {
        val alpha = (value.coerceIn(0, 100) * 255) / 100
        previewFilterDrawable?.alpha = alpha
        imagePreview.invalidate()
    }

    /**
     * 요구사항 반영:
     * 1) centerCrop로 "꽉 차게" (넘치는 부분은 화면 밖으로 잘림)
     * 2) 그 결과를 중앙 기준 10% 추가 확대
     * 3) 스크림(그림자) 오버레이 유지 (단, 위/아래 일부 영역에만 적용 + 알파 조절)
     */
    private suspend fun generatePreviewDrawable(): PreviewLayer? = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(this@WallpaperPreviewActivity)
            val wallpaperDrawable = wm.drawable ?: return@withContext null

            val isLandscape =
                resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE

            // 기존 컨셉 유지(가로/세로 타겟 캔버스 크기)
            val landscapeWidth = 2000
            val landscapeHeight = 1200
            val targetWidth = if (isLandscape) landscapeWidth else landscapeHeight
            val targetHeight = if (isLandscape) landscapeHeight else landscapeWidth

            // 원본 비율 그대로 비트맵화
            val srcW = wallpaperDrawable.intrinsicWidth.coerceAtLeast(1)
            val srcH = wallpaperDrawable.intrinsicHeight.coerceAtLeast(1)
            val srcBitmap = wallpaperDrawable.toBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)

            // 1차: centerCrop로 꽉 채우기(여백 없음)
            val filled = centerCropBitmap(
                source = srcBitmap,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
            srcBitmap.recycle()

            // 2차: 그 상태에서 중앙 기준 10% 확대
            val zoomed = applyCenterZoomCrop(filled, zoomFactor = 1.10f)
            filled.recycle()

            val zoomedDrawable = BitmapDrawable(resources, zoomed)

            // 그림자(스크림)
            val bottomScrim = ContextCompat.getDrawable(
                this@WallpaperPreviewActivity,
                R.drawable.launcher_scrim_bottom_up
            )?.mutate()

            val topScrim = ContextCompat.getDrawable(
                this@WallpaperPreviewActivity,
                R.drawable.launcher_scrim_top_down
            )?.mutate()

            val filterOpacityPercent =
                SettingsManager.getInstance(this@WallpaperPreviewActivity).previewFilterOpacity.coerceIn(0, 100)
            val filterAlpha = (filterOpacityPercent * 255) / 100
            val filterDrawable = run {
                val filterBitmap = BitmapFactory.decodeResource(resources, R.drawable.filter) ?: return@run null
                val scaledFilter =
                    if (filterBitmap.width != targetWidth || filterBitmap.height != targetHeight) {
                        val scaled = Bitmap.createScaledBitmap(filterBitmap, targetWidth, targetHeight, true)
                        filterBitmap.recycle()
                        scaled
                    } else {
                        filterBitmap
                    }
                BitmapDrawable(resources, scaledFilter).apply { alpha = filterAlpha }
            }

            return@withContext if (bottomScrim != null && topScrim != null) {
                // “검은 필터”처럼 보이지 않도록 스크림 강도(알파) 낮춤
                bottomScrim.alpha = 120
                topScrim.alpha = 90

                val layer = if (filterDrawable != null) {
                    LayerDrawable(arrayOf(zoomedDrawable, bottomScrim, topScrim, filterDrawable))
                } else {
                    LayerDrawable(arrayOf(zoomedDrawable, bottomScrim, topScrim))
                }

                // 스크림을 화면 전체가 아니라 “아래/위 일부”에만 적용 (은은한 그림자 느낌)
                val bottomH = (targetHeight * 0.35f).toInt().coerceAtLeast(1) // 아래 35%
                val topH = (targetHeight * 0.20f).toInt().coerceAtLeast(1)    // 위 20%

                // index: 0=wallpaper, 1=bottomScrim, 2=topScrim
                layer.setLayerInset(1, 0, targetHeight - bottomH, 0, 0) // bottom
                layer.setLayerInset(2, 0, 0, 0, targetHeight - topH)    // top
                if (filterDrawable != null) {
                    layer.setLayerSize(3, targetWidth, targetHeight)
                    layer.setLayerInset(3, 0, 0, 0, 0)
                }

                PreviewLayer(layer, filterDrawable)
            } else {
                val layer = if (filterDrawable != null) {
                    LayerDrawable(arrayOf(zoomedDrawable, filterDrawable))
                } else {
                    LayerDrawable(arrayOf(zoomedDrawable))
                }
                if (filterDrawable != null) {
                    layer.setLayerSize(1, targetWidth, targetHeight)
                    layer.setLayerInset(1, 0, 0, 0, 0)
                }
                PreviewLayer(layer, filterDrawable)
            }
        } catch (e: Exception) {
            Log.e("WallpaperPreview", "Error generating preview drawable", e)
            return@withContext null
        }
    }

    /**
     * centerCrop: target를 꽉 채우도록 확대/축소 후 중앙 정렬,
     * 넘치는 부분은 target 밖으로 나가면서 잘리는 방식(여백 없음).
     */
    private fun centerCropBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val scale = max(
            targetWidth.toFloat() / source.width.toFloat(),
            targetHeight.toFloat() / source.height.toFloat()
        )

        val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(1)

        val left = ((targetWidth - scaledW) / 2f).toInt()
        val top = ((targetHeight - scaledH) / 2f).toInt()

        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = Rect(left, top, left + scaledW, top + scaledH)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, srcRect, dstRect, paint)

        return result
    }

    /**
     * 중앙 기준 확대:
     * zoomFactor=1.10 => 중앙 (1/1.10) 영역을 잘라서 다시 원래 크기로 늘림
     */
    private fun applyCenterZoomCrop(source: Bitmap, zoomFactor: Float): Bitmap {
        val factor = zoomFactor.coerceAtLeast(1.0f)

        val cropW = (source.width / factor).toInt().coerceAtLeast(1)
        val cropH = (source.height / factor).toInt().coerceAtLeast(1)

        val left = ((source.width - cropW) / 2f).toInt().coerceAtLeast(0)
        val top = ((source.height - cropH) / 2f).toInt().coerceAtLeast(0)

        val srcRect = Rect(left, top, left + cropW, top + cropH)
        val dstRect = Rect(0, 0, source.width, source.height)

        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, srcRect, dstRect, paint)

        return result
    }
}
