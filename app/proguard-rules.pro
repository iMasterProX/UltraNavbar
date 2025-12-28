# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android SDK tools.

# Keep accessibility service
-keep class com.minsoo.ultranavbar.service.NavBarAccessibilityService { *; }

# Keep model classes
-keep class com.minsoo.ultranavbar.model.** { *; }
