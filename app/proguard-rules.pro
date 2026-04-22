# Keep the JNI bridge class — its name is hardcoded in the native .so symbols
-keep class com.proxytunnel.tunnel.TProxyService { *; }

# Keep VPN service and model classes
-keep class com.proxytunnel.service.** { *; }
-keep class com.proxytunnel.model.**  { *; }

# Gson needs the model class structure
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
