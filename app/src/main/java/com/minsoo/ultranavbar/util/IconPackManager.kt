package com.minsoo.ultranavbar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser

object IconPackManager {
    private const val TAG = "IconPackManager"

    private val iconPackActions = listOf(
        "com.novalauncher.THEME",
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme"
    )

    data class IconPackInfo(
        val packageName: String,
        val label: CharSequence
    )

    private data class IconPackEntry(
        val packageName: String,
        val componentToDrawable: Map<String, String>
    )

    private val iconPackCache = mutableMapOf<String, IconPackEntry>()

    fun getInstalledIconPacks(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val results = linkedMapOf<String, IconPackInfo>()

        for (action in iconPackActions) {
            val intent = Intent(action)
            val resolveInfos = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                emptyList()
            }

            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo?.packageName ?: continue
                if (results.containsKey(packageName)) continue
                val label = try {
                    resolveInfo.loadLabel(pm)
                } catch (_: Exception) {
                    packageName
                }
                results[packageName] = IconPackInfo(packageName, label)
            }
        }

        return results.values.sortedBy { it.label.toString().lowercase() }
    }

    fun loadDrawableForPackage(
        context: Context,
        iconPackPackage: String,
        targetPackageName: String
    ): Drawable? {
        if (iconPackPackage.isBlank() || targetPackageName.isBlank()) return null

        return try {
            val packageContext = context.createPackageContext(iconPackPackage, Context.CONTEXT_IGNORE_SECURITY)
            val packageManager = context.packageManager
            val launchComponent = packageManager.getLaunchIntentForPackage(targetPackageName)?.component
            val entry = getOrParseIconPack(packageContext, iconPackPackage) ?: return null

            val drawableName = resolveDrawableName(entry, launchComponent, targetPackageName) ?: return null
            val resId = packageContext.resources.getIdentifier(drawableName, "drawable", iconPackPackage)
            if (resId == 0) return null
            packageContext.resources.getDrawable(resId, packageContext.theme)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon pack drawable: pack=$iconPackPackage app=$targetPackageName", e)
            null
        }
    }

    private fun getOrParseIconPack(context: Context, iconPackPackage: String): IconPackEntry? {
        iconPackCache[iconPackPackage]?.let { return it }

        return try {
            val mapping = parseAppFilter(context.resources, iconPackPackage)
            IconPackEntry(iconPackPackage, mapping).also {
                iconPackCache[iconPackPackage] = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse icon pack: $iconPackPackage", e)
            null
        }
    }

    private fun parseAppFilter(resources: Resources, iconPackPackage: String): Map<String, String> {
        val mappings = mutableMapOf<String, String>()

        val parser = try {
            resources.assets.openXmlResourceParser("appfilter.xml")
        } catch (_: Exception) {
            val xmlId = resources.getIdentifier("appfilter", "xml", iconPackPackage)
            if (xmlId != 0) resources.getXml(xmlId) else null
        } ?: return emptyMap()

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val normalized = normalizeComponent(component)
                    if (!normalized.isNullOrBlank() && !drawable.isNullOrBlank()) {
                        mappings[normalized] = drawable
                    }
                }
                eventType = parser.next()
            }
        } finally {
            try {
                parser.close()
            } catch (_: Exception) {
            }
        }

        return mappings
    }

    private fun resolveDrawableName(
        entry: IconPackEntry,
        launchComponent: ComponentName?,
        packageName: String
    ): String? {
        val flattened = launchComponent?.flattenToShortString()?.lowercase()
        if (!flattened.isNullOrBlank()) {
            entry.componentToDrawable[flattened]?.let { return it }
        }

        val prefix = packageName.lowercase() + "/"
        return entry.componentToDrawable.entries.firstOrNull { (component, _) ->
            component.startsWith(prefix)
        }?.value
    }

    private fun normalizeComponent(component: String?): String? {
        val raw = component?.trim().orEmpty()
        if (!raw.startsWith("ComponentInfo{")) return null
        return raw.removePrefix("ComponentInfo{")
            .removeSuffix("}")
            .trim()
            .lowercase()
    }
}
