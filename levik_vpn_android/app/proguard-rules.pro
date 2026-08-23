-keep class libXray.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes *Annotation*

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
