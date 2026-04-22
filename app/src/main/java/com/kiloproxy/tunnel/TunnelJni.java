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
     * Returns cumulative bytes sent (upload) as an unsigned 32-bit value
     * promoted to long. Safe for values up to 4 GiB.
     *
     * The native side packs stats as: high-32 = TX (sent), low-32 = RX (received).
     * We use Integer.toUnsignedLong() to guarantee the result is always >= 0,
     * even when the raw 32-bit value has bit-31 set (would look negative as int).
     */
    public static long getBytesSent() {
        long packed = TProxyService.GetStats(false);
        return Integer.toUnsignedLong((int) (packed >>> 32));
    }

    /**
     * Returns cumulative bytes received (download) as an unsigned 32-bit value
     * promoted to long. Safe for values up to 4 GiB.
     */
    public static long getBytesReceived() {
        long packed = TProxyService.GetStats(false);
        return Integer.toUnsignedLong((int) packed);
    }

    /**
     * Returns both stats in one native call to ensure consistency.
     * Using Integer.toUnsignedLong avoids sign-extension when bit-31 is set.
     *
     * @return long[]{bytesSent, bytesReceived}
     */
    public static long[] getStats() {
        long packed = TProxyService.GetStats(false);
        return new long[]{
            Integer.toUnsignedLong((int) (packed >>> 32)),  // TX / sent
            Integer.toUnsignedLong((int) packed)            // RX / received
        };
    }

    /**
     * Returns true if the native library loaded successfully.
     */
    public static boolean isAvailable() {
        return TProxyService.isAvailable();
    }
}
