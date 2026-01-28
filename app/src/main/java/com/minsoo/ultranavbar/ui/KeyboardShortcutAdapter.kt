package com.minsoo.ultranavbar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.KeyShortcut

class KeyboardShortcutAdapter(
    private var shortcuts: List<KeyShortcut>,
    private val onDelete: (KeyShortcut) -> Unit
) : RecyclerView.Adapter<KeyboardShortcutAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtName)
        val txtKeyCombination: TextView = view.findViewById(R.id.txtKeyCombination)
        val txtAction: TextView = view.findViewById(R.id.txtAction)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyboard_shortcut, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val shortcut = shortcuts[position]

        holder.txtName.text = shortcut.name
        holder.txtKeyCombination.text = shortcut.getDisplayString()

        // Action description
        val actionDesc = when (shortcut.actionType) {
            KeyShortcut.ActionType.APP -> {
                val pm = holder.itemView.context.packageManager
                try {
                    val appInfo = pm.getApplicationInfo(shortcut.actionData, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    shortcut.actionData
                }
            }
            KeyShortcut.ActionType.SETTINGS -> {
                holder.itemView.context.getString(R.string.action_open_settings)
            }
            else -> shortcut.actionData
        }
        holder.txtAction.text = actionDesc

        holder.btnDelete.setOnClickListener {
            onDelete(shortcut)
        }
    }

    override fun getItemCount() = shortcuts.size

    fun updateShortcuts(newShortcuts: List<KeyShortcut>) {
        shortcuts = newShortcuts
        notifyDataSetChanged()
    }
}
