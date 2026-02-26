package com.minsoo.ultranavbar.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import com.minsoo.ultranavbar.R

object IconShapeMaskHelper {
    @Volatile
    private var squircleMaskBitmap: Bitmap? = null

    fun wrapWithSquircleMask(context: Context, source: Drawable): Drawable {
        val base = cloneDrawable(context, source)
        return SquircleMaskedDrawable(base, getSquircleMaskBitmap(context))
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
}
