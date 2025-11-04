# Reflection is used solely to discover the platform-specific JNI loader classes: Class.forName(...) + getMethod("load") in src/jvmMain/kotlin/fr/acinq/secp256k1/Secp256k1Jvm.kt:24-26, and again inside the Android loader
# fallback in jni/android/src/main/java/fr/acinq/secp256k1/jni/NativeSecp256k1AndroidLoader.kt:20-22.
# TODO: Remove this file after upstream ships consumer rules
 -keep class fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader { public static fr.acinq.secp256k1.Secp256k1 load(); }