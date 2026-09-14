# Tsuyu Messenger ProGuard & R8 Optimization Rules

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Optimize and strip dead code
-repackageclasses ''
-allowaccessmodification

# Keep annotations, signatures, and reflection points
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# App classes and data models (Firebase RTDB needs model reflection)
-keep class com.tsuyu.messenger.data.** { *; }
-keepclassmembers class com.tsuyu.messenger.data.** {
    public <init>(...);
    public <methods>;
    public <fields>;
}

-keep class com.tsuyu.messenger.crypto.** { *; }
-keepclassmembers class com.tsuyu.messenger.crypto.** {
    public <init>(...);
    public <methods>;
    public <fields>;
}

-keep class com.tsuyu.messenger.service.** { *; }
-keep class com.tsuyu.messenger.media.** { *; }
-keep class com.tsuyu.messenger.util.** { *; }
-keep class com.tsuyu.messenger.ui.** { *; }

# Custom Views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# WebRTC native JNI & wrapper
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Firebase SDKs
-keep class com.google.firebase.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Bouncy Castle minimal keep rules for X25519 / Ed25519
-keep class org.bouncycastle.crypto.agreement.X25519Agreement { *; }
-keep class org.bouncycastle.crypto.generators.X25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.AsymmetricCipherKeyPair { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-dontwarn org.bouncycastle.**

# CameraX
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# AndroidX Support
-keep class androidx.appcompat.widget.** { *; }
-keep class androidx.cardview.widget.** { *; }
-keep class androidx.recyclerview.widget.** { *; }
-keep class androidx.core.** { *; }
-dontwarn androidx.**

# Suppress generic warnings
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
