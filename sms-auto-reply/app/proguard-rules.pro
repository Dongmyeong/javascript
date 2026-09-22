# Keep kotlinx.serialization generated serializers if minification is ever enabled.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.dongmyeong.smsautoreply.**$$serializer { *; }
-keepclassmembers class com.dongmyeong.smsautoreply.** { *** Companion; }
-keepclasseswithmembers class com.dongmyeong.smsautoreply.** { kotlinx.serialization.KSerializer serializer(...); }
