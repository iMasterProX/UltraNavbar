package com.minsoo.ultranavbar.service

import android.util.Log

/**
 * 알림 상태를 추적하여 불필요한 깜빡임을 방지
 * - 이미 확인한 알림에는 깜빡이지 않음
 * - 시스템 알림 필터링 (android, systemui, bluetooth 패키지)
 * - 일시적인 알림에도 깜빡이지 않음
 */
class NotificationTracker {

    companion object {
        private const val TAG = "NotificationTracker"

        // 시스템/일시적 알림으로 간주할 패키지
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.bluetooth",
            "com.android.providers.downloads"
        )

        // 일시적 알림 카테고리
        private val TRANSIENT_CATEGORIES = setOf(
            "transport",
            "progress",
            "sys",
            "service"
        )
    }

    // 현재 활성 알림 키 (StatusBarNotification.key)
    private val activeNotificationKeys = mutableSetOf<String>()

    // 이미 본(확인한) 알림 키
    private val seenNotificationKeys = mutableSetOf<String>()

    /**
     * 알림 이벤트 처리
     * @param key 알림 고유 키
     * @param packageName 알림을 보낸 패키지명
     * @param category 알림 카테고리 (nullable)
     * @param isOngoing ongoing 알림 여부
     * @param isRemoval 알림 제거 이벤트인지
     * @return 새로운 알림이면 true (깜빡임 트리거), 아니면 false
     */
    fun processNotificationEvent(
        key: String?,
        packageName: String?,
        category: String?,
        isOngoing: Boolean,
        isRemoval: Boolean
    ): Boolean {
        if (key.isNullOrEmpty()) {
            return false
        }

        Log.d(TAG, "Process notification: key=$key, pkg=$packageName, category=$category, ongoing=$isOngoing, removal=$isRemoval")

        // 알림 제거 이벤트
        if (isRemoval) {
            activeNotificationKeys.remove(key)
            // 본 알림 목록에서는 제거하지 않음 (같은 알림이 다시 오면 깜빡이지 않도록)
            Log.d(TAG, "Notification removed: $key")
            return false
        }

        // 시스템 패키지 필터링
        if (packageName != null && IGNORED_PACKAGES.contains(packageName)) {
            Log.d(TAG, "Ignored system package: $packageName")
            return false
        }

        // ongoing 알림 필터링 (배터리 상태, 미디어 재생 등)
        if (isOngoing) {
            Log.d(TAG, "Ignored ongoing notification: $key")
            return false
        }

        // 일시적 카테고리 필터링
        if (category != null && TRANSIENT_CATEGORIES.contains(category)) {
            Log.d(TAG, "Ignored transient category: $category")
            return false
        }

        // 이미 본 알림이면 깜빡이지 않음
        if (seenNotificationKeys.contains(key)) {
            Log.d(TAG, "Already seen notification: $key")
            activeNotificationKeys.add(key)
            return false
        }

        // 이미 활성 알림이면 업데이트일 뿐, 새 알림 아님
        if (activeNotificationKeys.contains(key)) {
            Log.d(TAG, "Notification update (not new): $key")
            return false
        }

        // 새 알림
        activeNotificationKeys.add(key)
        Log.d(TAG, "New notification detected: $key")
        return true
    }

    /**
     * 알림 패널이 열렸을 때 호출
     * 모든 활성 알림을 "본 것"으로 처리
     */
    fun markAllAsSeen() {
        seenNotificationKeys.addAll(activeNotificationKeys)
        Log.d(TAG, "Marked ${activeNotificationKeys.size} notifications as seen")
    }

    /**
     * 확인하지 않은 새 알림이 있는지 확인
     */
    fun hasUnseenNotifications(): Boolean {
        return activeNotificationKeys.any { !seenNotificationKeys.contains(it) }
    }

    /**
     * 활성 알림 개수
     */
    fun getActiveCount(): Int = activeNotificationKeys.size

    /**
     * 확인하지 않은 알림 개수
     */
    fun getUnseenCount(): Int = activeNotificationKeys.count { !seenNotificationKeys.contains(it) }

    /**
     * 상태 초기화 (서비스 재시작 시)
     */
    fun clear() {
        activeNotificationKeys.clear()
        seenNotificationKeys.clear()
        Log.d(TAG, "Notification tracker cleared")
    }
}
