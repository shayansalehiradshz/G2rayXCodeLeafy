# ProGuard rules for G2Ray Client
-keep class com.g2ray.client.network.** { *; }
-keep class libXray.** { *; }
-keep class libxray.** { *; }
-keep class com.g2ray.client.vpn.HevTunController { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AccountRepository uses Moshi Kotlin reflection; these names and constructors
# are part of the on-disk JSON contract, including across app upgrades.
-keep class com.g2ray.client.model.G2RayAccount { *; }
-keep class com.g2ray.client.model.G2RayApiKey { *; }
-keep class com.g2ray.client.model.StoredG2RayProfile { *; }
-keep class com.g2ray.client.model.ActiveSelection { *; }
-keep class com.g2ray.client.model.WorkerControlPlane { *; }
-keep enum com.g2ray.client.model.CodespaceWakeMode { *; }
-keep enum com.g2ray.client.model.ApiKeyKind { *; }
-keep enum com.g2ray.client.model.Transport { *; }

# Optional compile-time annotations and alternative desktop TLS providers are
# referenced by dependencies but are not packaged or selected on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
