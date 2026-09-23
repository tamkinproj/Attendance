# ProGuard rules for MuslimEdu Attendance app

# Keep Hilt generated classes
-keep class hilt_aggregated_deps.** { *; }
-keep class * extends dagger.android.AndroidInjector
-keepclasseswithmembers class * {
    @dagger.* <fields>;
}
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}

# Keep data classes
-keepclassmembers class * {
    public <init>(...);
}

# Keep Retrofit
-keep class com.squareup.retrofit2.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep class com.google.gson.** { *; }
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep OkHttp
-keep class okhttp3.** { *; }
-keepclasseswithmembers class okhttp3.** { *; }

# Keep Room database classes
-keep class androidx.room.** { *; }

# Keep ML Kit
-keep class com.google.mlkit.** { *; }

# Keep TensorFlow Lite
-keep class org.tensorflow.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclasseswithmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep BuildConfig
-keep class com.muslimedu.attendance.BuildConfig { *; }

# Keep R (resources)
-keep class com.muslimedu.attendance.R** { *; }
