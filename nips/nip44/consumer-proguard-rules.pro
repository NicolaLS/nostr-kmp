# Reflection is used solely to discover the platform-specific JNI loader classes: Class.forName(...) + getMethod("load") in src/jvmMain/kotlin/fr/acinq/secp256k1/Secp256k1Jvm.kt:24-26, and again inside the Android loader
# fallback in jni/android/src/main/java/fr/acinq/secp256k1/jni/NativeSecp256k1AndroidLoader.kt:20-22.
# TODO: Remove this file after upstream ships consumer rules
 -keep class fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader { public static fr.acinq.secp256k1.Secp256k1 load(); }

# TODO: kotlin-multiplatform-libsodium fix
-keep class com.ionspin.kotlin.crypto.JnaLibsodiumInterface { *; }
-keep class com.ionspin.kotlin.crypto.Hash256State { *; }
-keep class com.ionspin.kotlin.crypto.Hash512State { *; }
-keep class com.ionspin.kotlin.crypto.Blake2bState { *; }
-keep class com.ionspin.kotlin.crypto.Ed25519SignatureState { *; }
-keep class com.ionspin.kotlin.crypto.SecretStreamXChaCha20Poly1305State { *; }

-keep class com.sun.jna.** { *; }
-keep class com.sun.jna.ptr.** { *; }
-keep class com.sun.jna.win32.** { *; }  # harmless on Android, avoids missing desktop helpers
-dontwarn com.sun.jna.**