package com.minsoo.ultranavbar.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.minsoo.ultranavbar.settings.SettingsManager
import java.io.File
import java.io.FileOutputStream


object ImageCropUtil {

    private const val TAG = "ImageCropUtil"

    
    const val PORTRAIT_BG_FILENAME = "navbar_bg_portrait.png"

    
    fun cropAndSaveFromUri(context: Context, uri: Uri, isLandscape: Boolean): Boolean {
        return try {
            
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

    
    fun cropAndSave(context: Context, bitmap: Bitmap, isLandscape: Boolean): Boolean {
        return try {
            val cropHeight = SettingsManager.CROP_HEIGHT_PX
            val width = bitmap.width
            val height = bitmap.height

            
            
            val y = (height - cropHeight).coerceAtLeast(0)
            val actualCropHeight = minOf(cropHeight, height)

            Log.d(TAG, "Cropping: original=${width}x${height}, crop y=$y, cropHeight=$actualCropHeight")

            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                0,                  
                y,                  
                width,              
                actualCropHeight    
            )

            
            val file = File(context.filesDir, filename)
            val outputStream = FileOutputStream(file)
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()
            croppedBitmap.recycle()

            
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

    
    fun hasBackgroundImage(context: Context, isLandscape: Boolean): Boolean {
        val filename = if (isLandscape) LANDSCAPE_BG_FILENAME else PORTRAIT_BG_FILENAME
        return File(context.filesDir, filename).exists()
    }

    
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