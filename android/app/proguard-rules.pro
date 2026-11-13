# LiveMonitor Android — R8 rules
#
# The app keeps no reflective entry points except Android components declared in
# the manifest (R8 keeps those automatically). We intentionally do NOT add
# broad "-keep class **" rules.

# Kotlin metadata / coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# OkHttp: the platform client is referenced directly; keep one optional
# JPMS-related attribute out of the release binary.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep line number information for crash diagnosis without leaking source code
# (source file names are still stripped by R8's default behavior).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
