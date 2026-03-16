package com.minsoo.ultranavbar.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object CustomAppIconStore {
    private const val TAG = "CustomAppIconStore"
    private const val ICON_DIR = "custom_app_icons"
    private const val MAX_ICON_EDGE_PX = 512

    fun saveIconFromUri(context: Context, packageName: String, uri: Uri): Boolean {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return false

        return try {
            val bitmap = decodeScaledBitmap(context, uri) ?: return false
            val normalizedBitmap = trimTransparentBounds(bitmap)
            if (normalizedBitmap != bitmap) {
                bitmap.recycle()
            }
            val file = getIconFile(context, normalizedPackage)
            FileOutputStream(file).use { output ->
                normalizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            normalizedBitmap.recycle()
            Log.i(TAG, "Saved custom icon for $normalizedPackage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom icon for $normalizedPackage", e)
            false
        }
    }

    fun loadDrawable(context: Context, packageName: String): Drawable? {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return null

        return try {
            val file = getIconFile(context, normalizedPackage)
            if (!file.exists()) return null

            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom icon for $normalizedPackage", e)
            null
        }
    }

    fun hasCustomIcon(context: Context, packageName: String): Boolean {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return false
        return getIconFile(context, normalizedPackage).exists()
    }

    fun deleteCustomIcon(context: Context, packageName: String): Boolean {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return false

        val file = getIconFile(context, normalizedPackage)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    fun getCustomIconCount(context: Context): Int {
        val dir = getIconDirectory(context)
        return dir.listFiles()?.count { it.isFile } ?: 0
    }

    private fun getIconDirectory(context: Context): File {
        return File(context.filesDir, ICON_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun getIconFile(context: Context, packageName: String): File {
        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(getIconDirectory(context), "$safeName.png")
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null

        val maxEdge = maxOf(decoded.width, decoded.height)
        if (maxEdge <= MAX_ICON_EDGE_PX) {
            return decoded
        }

        val scale = MAX_ICON_EDGE_PX.toFloat() / maxEdge.toFloat()
        val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled != decoded) {
            decoded.recycle()
        }
        return scaled
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth > MAX_ICON_EDGE_PX * 2 || currentHeight > MAX_ICON_EDGE_PX * 2) {
            currentWidth /= 2
            currentHeight /= 2
            inSampleSize *= 2
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun trimTransparentBounds(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap

        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((bitmap.getPixel(x, y) ushr 24) == 0) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }

        if (maxX < minX || maxY < minY) return bitmap
        if (minX == 0 && minY == 0 && maxX == bitmap.width - 1 && maxY == bitmap.height - 1) {
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
