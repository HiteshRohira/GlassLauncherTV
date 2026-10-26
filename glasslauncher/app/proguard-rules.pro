# Release R8 rules. Keep JNI callbacks in the AirPlay consumer rules.
# Additional entry points started by class name must be kept explicitly.

-verbose
-keepattributes *Annotation*
-dontwarn androidx.test.**
-dontwarn org.junit.**
-dontwarn junit.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
