# kotlinx.serialization, OkHttp, Media3 and Coil ship their own consumer rules.
# Keep serializers for our models (used by DataStore, downloads and type-safe navigation).
-keepclassmembers @kotlinx.serialization.Serializable class io.github.deadeyebarb.tonearm.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# NewPipeExtractor (YouTube Music fallback): Rhino runs YouTube's player JavaScript reflectively.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Rhino's JSON converters, javax.script engine and invokedynamic linker reference desktop-only
# classes; none of them run on Android (Rhino interprets there).
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# Its protobuf messages (how YouTube Music albums and playlists go on past the first page) are read field by field,
# by name, at runtime: R8 mustn't rename or drop their fields ("Field browseId_ for … not found" otherwise).
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
