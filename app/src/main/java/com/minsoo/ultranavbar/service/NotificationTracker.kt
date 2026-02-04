package com.minsoo.ultranavbar.service

import android.util.Log

/**
 * Notification state tracker to prevent unnecessary blinking.
 */
class NotificationTracker {

    companion object {
        private const val TAG = "NotificationTracker"
        private const val MAX_TRACKED_NOTIFICATIONS = 200

        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.bluetooth",
            "com.android.providers.downloads"
        )

        private val TRANSIENT_CATEGORIES = setOf(
            "transport",
            "progress",
            "sys",
            "service"
        )
    }

    // LRU처럼 "최근 사용"을 맨 뒤로 보내기 위해 LinkedHashSet 사용
    private val activeNotificationKeys: LinkedHashSet<String> = LinkedHashSet()
    private val seenNotificationKeys: LinkedHashSet<String> = LinkedHashSet()

    fun processNotificationEvent(
        key: String?,
        packageName: String?,
        category: String?,
        isOngoing: Boolean,
        isRemoval: Boolean
    ): Boolean {
        if (key.isNullOrEmpty()) return false

        Log.d(TAG, "Process notification: key=$key, pkg=$packageName, category=$category, ongoing=$isOngoing, removal=$isRemoval")

        if (isRemoval) {
            activeNotificationKeys.remove(key)
            Log.d(TAG, "Notification removed: $key")
            return false
        }

        if (packageName != null && IGNORED_PACKAGES.contains(packageName)) {
            Log.d(TAG, "Ignored system package: $packageName")
            return false
        }

        if (isOngoing) {
            Log.d(TAG, "Ignored ongoing notification: $key")
            return false
        }

        if (category != null && TRANSIENT_CATEGORIES.contains(category)) {
            Log.d(TAG, "Ignored transient category: $category")
            return false
        }

        // 이미 본 알림이면: active에는 넣되, "최근"으로 갱신
        if (seenNotificationKeys.contains(key)) {
            Log.d(TAG, "Already seen notification: $key")
            touchActive(key)
            trimIfNeeded()
            return false
        }

        // 이미 활성 알림이면 업데이트일 뿐: active의 "최근"만 갱신
        if (activeNotificationKeys.contains(key)) {
            Log.d(TAG, "Notification update (not new): $key")
            touchActive(key)
            trimIfNeeded()
            return false
        }

        // 새 알림
        touchActive(key)
        Log.d(TAG, "New notification detected: $key")
        trimIfNeeded()
        return true
    }

    fun markAllAsSeen() {
        // active에 있는 것들을 seen으로 옮기되, seen의 "최근"으로 갱신
        for (k in activeNotificationKeys) {
            touchSeen(k)
        }
        trimIfNeeded()
        Log.d(TAG, "Marked ${activeNotificationKeys.size} notifications as seen")
    }

    fun hasUnseenNotifications(): Boolean {
        return activeNotificationKeys.any { !seenNotificationKeys.contains(it) }
    }

    fun getActiveCount(): Int = activeNotificationKeys.size

    fun getUnseenCount(): Int = activeNotificationKeys.count { !seenNotificationKeys.contains(it) }

    fun clear() {
        activeNotificationKeys.clear()
        seenNotificationKeys.clear()
        Log.d(TAG, "Notification tracker cleared")
    }

    private fun touchActive(key: String) {
        // LinkedHashSet은 add만 하면 기존 위치가 갱신되지 않으니 remove 후 add로 "최근" 갱신
        activeNotificationKeys.remove(key)
        activeNotificationKeys.add(key)
    }

    private fun touchSeen(key: String) {
        seenNotificationKeys.remove(key)
        seenNotificationKeys.add(key)
    }

    private fun trimIfNeeded() {
        trimSetToLastN(seenNotificationKeys, MAX_TRACKED_NOTIFICATIONS)
        trimSetToLastN(activeNotificationKeys, MAX_TRACKED_NOTIFICATIONS)
    }

    private fun trimSetToLastN(set: LinkedHashSet<String>, max: Int) {
        val size = set.size
        if (size <= max) return

        // "마지막 N개(가장 최근 것)"만 유지
        val keep = set.toList().takeLast(max)
        set.clear()
        set.addAll(keep)

        Log.d(TAG, "Trimmed ${size - max} old entries")
    }
}
