-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.seenama.tv.**$$serializer { *; }
-keepclassmembers class com.seenama.tv.** { *** Companion; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep class names so the wildcard baseline profile (src/main/baseline-prof.txt) matches app code.
# Shrinking and optimization still run.
-dontobfuscate
