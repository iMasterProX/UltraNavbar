package com.minsoo.ultranavbar.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.minsoo.ultranavbar.model.NavAction


class ButtonManager(
    private val context: Context,
    private val listener: ButtonActionListener
) {
    companion object {
        private const val TAG = "ButtonManager"
    }

    
    private val _allButtons = mutableListOf<ImageButton>()
    val allButtons: List<ImageButton> get() = _allButtons

    
    private var _panelButton: ImageButton? = null
    val panelButton: ImageButton? get() = _panelButton

    private var _backButton: ImageButton? = null
    val backButton: ImageButton? get() = _backButton

    
    private var currentColor: Int = -1

    
    interface ButtonActionListener {
        fun onButtonClick(action: NavAction)
        fun onButtonLongClick(action: NavAction): Boolean
        fun shouldIgnoreTouch(toolType: Int): Boolean
    }

    

    
    fun createNavButton(
        action: NavAction,
        iconResId: Int,
        sizePx: Int,
        initialColor: Int
    ): ImageButton {
        currentColor = initialColor

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

            
            val rippleColor = ColorStateList.valueOf(0x33808080) 
            val maskDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = sizePx / 2f
                setColor(Color.GRAY)
            }
            background = RippleDrawable(rippleColor, null, maskDrawable)

            elevation = context.dpToPx(4).toFloat()
            stateListAnimator = null

            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(iconResId)
            setColorFilter(initialColor)
            contentDescription = action.displayName

            
            _allButtons.add(this)

            
                NavAction.NOTIFICATIONS -> _panelButton = this
                NavAction.BACK -> _backButton = this
                else -> {}
            }

            
                if (listener.shouldIgnoreTouch(event.getToolType(0))) {
                    return@setOnTouchListener true
                }
                false
            }

            
                listener.onButtonClick(action)
            }

            
                listener.onButtonLongClick(action)
            }
        }
    }

    
    fun createSpacer(widthPx: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        }
    }

    
    fun addSpacerToGroup(parent: ViewGroup, widthPx: Int) {
        parent.addView(createSpacer(widthPx))
    }

    

    
    fun updateAllButtonColors(color: Int, force: Boolean = false) {
        if (!force && currentColor == color) return
        currentColor = color

        _allButtons.forEach { button ->
            button.setColorFilter(color)
        }

        Log.d(TAG, "All button colors updated to ${getColorName(color)} (force=$force, buttons=${_allButtons.size})")
    }

    private fun getColorName(color: Int): String {
        return when (color) {
            Color.WHITE -> "WHITE"
            Color.BLACK -> "BLACK"
            Color.DKGRAY -> "DARK_GRAY"
            else -> "0x${Integer.toHexString(color)}"
        }
    }

    

    
    fun updatePanelButtonState(isOpen: Boolean, animate: Boolean = true) {
        val rotation = if (isOpen) Constants.Rotation.PANEL_OPEN else Constants.Rotation.PANEL_CLOSED

        _panelButton?.let { button ->
            if (animate) {
                button.animate()
                    .rotation(rotation)
                    .setDuration(Constants.Timing.ANIMATION_DURATION_MS)
                    .start()
            } else {
                button.rotation = rotation
            }
        }
    }

    
    fun updatePanelButtonDescription(isOpen: Boolean, openText: String, closeText: String) {
        _panelButton?.contentDescription = if (isOpen) closeText else openText
    }

    

    
    fun updateBackButtonRotation(isImeVisible: Boolean, animate: Boolean = true) {
        val targetRotation = if (isImeVisible) {
            Constants.Rotation.IME_ACTIVE
        } else {
            Constants.Rotation.IME_INACTIVE
        }

        _backButton?.let { button ->
            button.animate().cancel()
            if (animate) {
                button.animate()
                    .rotation(targetRotation)
                    .setDuration(Constants.Timing.ANIMATION_DURATION_MS)
                    .start()
            } else {
                button.rotation = targetRotation
            }
        }
    }

    

    
    fun clear() {
        _allButtons.clear()
        _panelButton = null
        _backButton = null
        
    }
}
