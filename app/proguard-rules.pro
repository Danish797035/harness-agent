# Preserve source file & line numbers for diagnostics and ChatLog stack traces
-keepattributes SourceFile, LineNumberTable

# Preserve enum values/valueOf/entries & Parcelable/Serializable contracts under R8 (rememberSaveable, serialization)
-keepclassmembers enum com.meenai.mina.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepnames class * implements android.os.Parcelable
-keepclassmembers class com.meenai.mina.** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepnames class com.meenai.mina.** implements java.io.Serializable

# kotlinx.serialization (app + :core models, sealed polymorphic events, @SerialName)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.meenai.mina.**$$serializer {
    *;
}
-keepclassmembers class com.meenai.mina.** {
    *** Companion;
}
-keepclasseswithmembers class com.meenai.mina.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.meenai.mina.** {
    *;
}
-keep @kotlinx.serialization.SerialName class com.meenai.mina.** {
    *;
}

# WebView @JavascriptInterface bridges (ArenaBridge in ArenaImageActivity)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.meenai.mina.image.ArenaImageActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Built-in helper process launched via app_process /system/bin (HelperHandoff.HELPER_CLASS)
-keep class com.meenai.mina.shell.HelperMain {
    public static void main(java.lang.String[]);
}

# AIDL Binder interface for built-in shell helper
-keep class com.meenai.mina.shell.IHarnessShell** {
    *;
}

# Protobuf Lite (OTel detailed traces)
-keep class * extends com.google.protobuf.GeneratedMessageLite {
    *;
}

# Preserve ModelError class names for diagnostics & retry classification
-keep class com.meenai.mina.core.model.ModelError** {
    *;
}

# OkHttp / Okio optional platform dependencies
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Hidden Android framework classes accessed via reflection in shell helper
-dontwarn android.os.SystemProperties
-dontwarn android.app.ActivityThread**
-dontwarn android.app.ActivityTaskManager
-dontwarn android.app.ITaskStackListener**
-dontwarn android.content.IClipboard**
-dontwarn android.content.pm.ILauncherApps**
-dontwarn android.content.pm.UserInfo
-dontwarn android.hardware.display.DisplayManagerGlobal
-dontwarn android.hardware.input.InputManagerGlobal
-dontwarn android.os.IPowerManager**
-dontwarn android.os.ServiceManager
-dontwarn android.view.WindowManagerGlobal
-dontwarn android.window.WindowContainerToken
-dontwarn android.window.WindowContainerTransaction
