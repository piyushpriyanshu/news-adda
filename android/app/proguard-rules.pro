# Feed models are (de)serialized by kotlinx.serialization.
-keep class com.newsadda.app.data.model.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
