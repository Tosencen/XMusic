# Add project specific ProGuard rules here.

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses, RuntimeVisibleAnnotations, AnnotationDefault, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.xmgo.music.** { *** Companion; }
-keepclasseswithmembers class com.xmgo.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xmgo.music.** implements kotlinx.serialization.KSerializer { *; }

# Rhino（插件脚本引擎，运行期反射调用宿主/自省成员）
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.**
-dontwarn java.beans.**

# General Android
-keepclassmembers class * {
    native <methods>;
}
