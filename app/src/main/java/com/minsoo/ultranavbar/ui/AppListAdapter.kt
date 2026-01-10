package com.minsoo.ultranavbar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.minsoo.ultranavbar.R

class AppListAdapter(
    private val selectionMode: String,
    private val onItemClick: (packageName: String) -> Unit,
    private val onItemChecked: (packageName: String, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var apps: List<AppListActivity.AppInfo> = emptyList()
    private var selectedPackages: Set<String> = emptySet()

    fun submitList(apps: List<AppListActivity.AppInfo>, selected: Set<String>) {
        this.apps = apps
        this.selectedPackages = selected.toSet()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.bind(app, selectedPackages.contains(app.packageName))
    }

    override fun getItemCount(): Int = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        private val appName: TextView = itemView.findViewById(R.id.appName)
        private val appPackage: TextView = itemView.findViewById(R.id.appPackage)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)

        fun bind(app: AppListActivity.AppInfo, isSelected: Boolean) {
            app.icon?.let { appIcon.setImageDrawable(it) }
                ?: appIcon.setImageResource(android.R.drawable.sym_def_app_icon)

            appName.text = app.name
            appPackage.text = app.packageName

            // 리스너를 먼저 제거하여 isChecked 설정 시 잘못된 콜백 방지
            checkBox.setOnCheckedChangeListener(null)

            if (selectionMode == AppListActivity.MODE_SINGLE) {
                checkBox.visibility = View.GONE
                itemView.setOnClickListener {
                    onItemClick(app.packageName)
                }
            } else {
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = isSelected
                itemView.setOnClickListener {
                    checkBox.toggle()
                }
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    onItemChecked(app.packageName, isChecked)
                }
            }
        }
    }
}
