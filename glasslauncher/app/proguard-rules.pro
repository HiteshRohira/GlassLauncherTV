# Release R8 rules. Keep JNI callbacks in the AirPlay consumer rules.
# Additional entry points started by class name must be kept explicitly.

-verbose
-keepattributes *Annotation*
-dontwarn androidx.test.**
-dontwarn org.junit.**
-dontwarn junit.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**

# The phone remote's key injector is started by name through app_process
# (see KeyInjector), so R8 must keep its class name and main().
-keep class tv.glass.launcher.KeyInjector {
    public static void main(java.lang.String[]);
}
