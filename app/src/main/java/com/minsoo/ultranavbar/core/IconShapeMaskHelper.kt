package com.minsoo.ultranavbar.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager

object IconShapeMaskHelper {
    @Volatile
    private var squircleMaskBitmap: Bitmap? = null

    fun wrapWithSquircleMask(context: Context, source: Drawable): Drawable {
        val base = cloneDrawable(context, source)
        return SquircleMaskedDrawable(base, getSquircleMaskBitmap(context))
    }

    fun wrapWithShapeMask(
        context: Context,
        source: Drawable,
        shape: SettingsManager.RecentAppsTaskbarIconShape
    ): Drawable {
        return when (shape) {
            SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                wrapWithSquircleMask(context, source)
            }
            SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                RoundedSquareMaskedDrawable(cloneDrawable(context, source), resolveBackdropColor(source))
            }
            SettingsManager.RecentAppsTaskbarIconShape.CIRCLE,
            SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                ShapeMaskedDrawable(cloneDrawable(context, source), shape)
            }
        }
    }

    fun createSquircleRippleMask(context: Context): Drawable {
        return BitmapDrawable(context.resources, getSquircleMaskBitmap(context)).apply {
            gravity = Gravity.FILL
        }
    }

    private fun getSquircleMaskBitmap(context: Context): Bitmap {
        squircleMaskBitmap?.let { return it }
        synchronized(this) {
            squircleMaskBitmap?.let { return it }
            val decoded = BitmapFactory.decodeResource(context.resources, R.drawable.ic_squircle_mask)
                ?: throw IllegalStateException("Unable to decode squircle mask")
            squircleMaskBitmap = decoded
            return decoded
        }
    }

    private fun cloneDrawable(context: Context, source: Drawable): Drawable {
        source.constantState?.newDrawable()?.mutate()?.let { return it }

        val fallbackWidth = getSquircleMaskBitmap(context).width
        val fallbackHeight = getSquircleMaskBitmap(context).height
        val width = source.intrinsicWidth.takeIf { it > 0 } ?: fallbackWidth
        val height = source.intrinsicHeight.takeIf { it > 0 } ?: fallbackHeight

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val originalBounds = Rect(source.bounds)
        source.setBounds(0, 0, width, height)
        source.draw(canvas)
        source.setBounds(originalBounds)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun resolveBackdropColor(source: Drawable): Int {
        val bitmap = (source as? BitmapDrawable)?.bitmap ?: return Color.TRANSPARENT
        if (bitmap.width <= 0 || bitmap.height <= 0) return Color.TRANSPARENT

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = pixel ushr 24
                if (alpha >= 24) {
                    red += Color.red(pixel).toLong()
                    green += Color.green(pixel).toLong()
                    blue += Color.blue(pixel).toLong()
                    count += 1
                }
                x += stepX
            }
            y += stepY
        }

        if (count == 0L) return Color.TRANSPARENT
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private class SquircleMaskedDrawable(
        private val source: Drawable,
        private val maskBitmap: Bitmap
    ) : Drawable() {
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        override fun draw(canvas: Canvas) {
            val dst = bounds
            if (dst.isEmpty) return

            val layer = canvas.saveLayer(
                dst.left.toFloat(),
                dst.top.toFloat(),
                dst.right.toFloat(),
                dst.bottom.toFloat(),
                null
            )
            source.bounds = dst
            source.draw(canvas)
            canvas.drawBitmap(maskBitmap, null, dst, maskPaint)
            canvas.restoreToCount(layer)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            source.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            source.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            source.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = source.intrinsicWidth

        override fun getIntrinsicHeight(): Int = source.intrinsicHeight
    }

    private class ShapeMaskedDrawable(
        private val source: Drawable,
        private val shape: SettingsManager.RecentAppsTaskbarIconShape
    ) : Drawable() {
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        }
        private val rectF = RectF()

        override fun draw(canvas: Canvas) {
            val dst = bounds
            if (dst.isEmpty) return

            rectF.set(dst)
            val layer = canvas.saveLayer(
                dst.left.toFloat(),
                dst.top.toFloat(),
                dst.right.toFloat(),
                dst.bottom.toFloat(),
                null
            )
            source.bounds = dst
            source.draw(canvas)

            when (shape) {
                SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                    val radius = minOf(dst.width(), dst.height()) * Constants.Dimension.TASKBAR_SQUARE_RADIUS_RATIO
                    canvas.drawRoundRect(rectF, radius, radius, maskPaintForShape())
                }
                SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> {
                    canvas.drawOval(rectF, maskPaintForShape())
                }
                SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                    val radius = minOf(dst.width(), dst.height()) * 0.22f
                    canvas.drawRoundRect(rectF, radius, radius, maskPaintForShape())
                }
                SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> Unit
            }

            canvas.restoreToCount(layer)
        }

        private fun maskPaintForShape(): Paint {
            shapePaint.xfermode = maskPaint.xfermode
            return shapePaint
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            source.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            source.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            source.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = source.intrinsicWidth

        override fun getIntrinsicHeight(): Int = source.intrinsicHeight
    }

    private class RoundedSquareMaskedDrawable(
        private val source: Drawable,
        backdropColor: Int
    ) : Drawable() {
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backdropColor
        }
        private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            xfermode = maskPaint.xfermode
        }
        private val rectF = RectF()

        override fun draw(canvas: Canvas) {
            val dst = bounds
            if (dst.isEmpty) return

            rectF.set(dst)
            val radius = minOf(dst.width(), dst.height()) * Constants.Dimension.TASKBAR_SQUARE_RADIUS_RATIO
            val layer = canvas.saveLayer(
                dst.left.toFloat(),
                dst.top.toFloat(),
                dst.right.toFloat(),
                dst.bottom.toFloat(),
                null
            )

            if ((backdropPaint.color ushr 24) != 0) {
                canvas.drawRoundRect(rectF, radius, radius, backdropPaint)
            }
            source.bounds = dst
            source.draw(canvas)
            canvas.drawRoundRect(rectF, radius, radius, shapePaint)
            canvas.restoreToCount(layer)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            source.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            source.alpha = alpha
            backdropPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            source.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = source.intrinsicWidth

        override fun getIntrinsicHeight(): Int = source.intrinsicHeight
    }
}
