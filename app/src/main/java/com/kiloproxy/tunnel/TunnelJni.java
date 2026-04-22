package com.kiloproxy.tunnel;

/**
 * Clean API wrapper around TProxyService (the raw JNI class).
 * The rest of the codebase calls TunnelJni, never TProxyService directly.
 */
public class TunnelJni {

    /**
     * Start the tunnel. Blocks until quit() is called.
     * Always call this on a dedicated background thread.
     *
     * @param configStr YAML configuration string (UTF-8)
     * @param tunFd     File descriptor from ParcelFileDescriptor.getFd()
     * @return 0 on clean exit, -1 on error
     */
    public static int mainFromStr(String configStr, int tunFd) {
        return TProxyService.StartService(configStr, tunFd);
    }

    /**
     * Signal the tunnel to stop. Non-blocking.
     * mainFromStr() will return shortly after this.
     */
    public static void quit() {
        TProxyService.StopService();
    }

    /**
     * Returns cumulative bytes sent (upload).
     */
    public static long getBytesSent() {
        return (TProxyService.GetStats(false) >>> 32) & 0xFFFFFFFFL;
    }

    /**
     * Returns cumulative bytes received (download).
     */
    public static long getBytesReceived() {
        return TProxyService.GetStats(false) & 0xFFFFFFFFL;
    }

    /**
     * Returns both stats in one native call to ensure consistency.
     * @return long[]{bytesSent, bytesReceived}
     */
    public static long[] getStats() {
        long packed = TProxyService.GetStats(false);
        return new long[]{(packed >>> 32) & 0xFFFFFFFFL, packed & 0xFFFFFFFFL};
    }

    /**
     * Returns true if the native library loaded successfully.
     */
    public static boolean isAvailable() {
        return TProxyService.isAvailable();
    }
}
