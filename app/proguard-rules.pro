# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android SDK tools.

# Keep accessibility service (AndroidManifest에서 참조)
-keep class com.minsoo.ultranavbar.service.NavBarAccessibilityService { *; }

# Keep model classes (SharedPreferences/JSON 직렬화에 사용)
-keep class com.minsoo.ultranavbar.model.** { *; }

# Keep Application class
-keep class com.minsoo.ultranavbar.UltraNavbarApplication { *; }

# Keep BroadcastReceivers (AndroidManifest에서 참조)
-keep class com.minsoo.ultranavbar.BootReceiver { *; }
-keep class com.minsoo.ultranavbar.widget.KeyboardBatteryWidget { *; }

# Keep custom View (XML에서 참조 가능)
-keep class com.minsoo.ultranavbar.ui.view.DrawingCanvasView { *; }

# Keep Preference Fragments (XML preference에서 참조)
-keep class com.minsoo.ultranavbar.ui.**Fragment { *; }

# R8 full mode 호환성
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
