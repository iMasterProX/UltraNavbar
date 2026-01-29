# Changelog

## v0.2.1 (2026-01-29)

### Bug Fixes

- **Notification Button Blinking**: Fixed multiple issues with the notification button blinking incorrectly
  - No longer blinks for already seen notifications
  - No longer blinks for transient/temporary notifications
  - No longer blinks when there are no actual new notifications
  - Added `NotificationTracker` class to properly track notification states
  - Opening the notification panel now marks all notifications as seen
  - Added 500ms debounce protection to prevent rapid blink triggers

### New Features

- **Custom Navigation Bar Toggle**: Added ability to completely enable/disable the custom navigation bar
  - New toggle switch at the top of Navigation Bar settings
  - When disabled, the overlay is completely hidden (no hotspot either)

- **Persistent Battery Notification**: Added option to always show keyboard battery level in notifications
  - New toggle in Keyboard settings: "Battery Status Notification"
  - Shows an ongoing notification with current battery percentage
  - Low-priority notification that doesn't disturb the user
  - Automatically updates when battery level changes

### Improvements

- **Home Background Guide Mode**: Redesigned the "Auto" background generation feature to "Guide" mode
  - Renamed "Auto" button to "Guide"
  - Close button now hides the control panel instead of closing the app
  - Users can take a screenshot while only the wallpaper preview is visible
  - Tap anywhere on the screen to exit after taking a screenshot
  - Updated instructions to guide users through the new workflow

- **Google Assistant Launch**: Improved Google Assistant invocation to properly show the bottom popup interface
  - Now tries multiple methods to launch the actual Assistant overlay
  - Falls back gracefully if preferred method is unavailable

### Technical Changes

- Added `NotificationTracker.kt` for proper notification state management
- Added `navbarEnabled` setting to `SettingsManager`
- Added `batteryPersistentNotificationEnabled` setting to `SettingsManager`
- Added new notification channel `keyboard_battery_status` for persistent notifications
- Updated `KeyboardBatteryMonitor` with persistent notification support
