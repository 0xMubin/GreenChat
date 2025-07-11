# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.greenchat.webdextro.protocol.** { *; }
-keep class com.greenchat.webdextro.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
