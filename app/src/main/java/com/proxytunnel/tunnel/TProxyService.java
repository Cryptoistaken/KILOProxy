package com.proxytunnel.tunnel;

import android.util.Log;

/**
 * JNI bridge to hev-socks5-tunnel native library.
 *
 * IMPORTANT: The class that declares the native methods MUST be named
 * exactly "TProxyService" (no package prefix in the symbol) because
 * the Android.mk in hev-socks5-tunnel hardcodes the JNI symbol names as:
 *   Java_TProxyService_StartService
 *   Java_TProxyService_StopService
 *   Java_TProxyService_GetStats
 *
 * We keep our own package-private wrapper (TunnelJni) that delegates to
 * this inner class so the rest of our code stays clean.
 *
 * The native methods signature (from hev-socks5-tunnel src/hev-jni.c):
 *   jint  StartService(JNIEnv*, jobject, jstring config, jint tun_fd)
 *   void  StopService (JNIEnv*, jobject)
 *   jlong GetStats    (JNIEnv*, jobject, jboolean reset) → packed tx<<32|rx bytes
 */
public class TProxyService {

    private static final String TAG = "KILOProxy/JNI";

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            Log.i(TAG, "libhev-socks5-tunnel.so loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libhev-socks5-tunnel.so: " + e.getMessage());
        }
    }

    /**
     * Start the tunnel. Blocks until StopService() is called.
     * Must be called on a dedicated background thread.
     *
     * @param configStr YAML config string
     * @param tunFd     File descriptor of the TUN device
     * @return 0 on success, -1 on error
     */
    public static native int StartService(String configStr, int tunFd);

    /**
     * Signal the tunnel to stop. Non-blocking — StartService() will
     * return shortly after this is called.
     */
    public static native void StopService();

    /**
     * Get traffic stats packed as: (txBytes << 32) | rxBytes.
     *
     * @param reset if true, resets counters after reading
     */
    public static native long GetStats(boolean reset);

    /** @return true if the .so loaded successfully */
    public static boolean isAvailable() {
        try {
            // Attempt a harmless reflective check — if the class loaded, so did the lib
            TProxyService.class.getDeclaredMethods();
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
}
