# Proguard rules for NXTShift

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Firebase
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
