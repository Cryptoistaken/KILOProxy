package com.proxytunnel.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.proxytunnel.R;
import com.proxytunnel.model.ProxyProfile;
import com.proxytunnel.tunnel.LocalSocks5Bridge;
import com.proxytunnel.tunnel.TunnelJni;
import com.proxytunnel.ui.MainActivity;
import com.proxytunnel.util.ProfileManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KILOProxy VPN Service — powered by hev-socks5-tunnel (tun2socks)
 *
 * Packet flow:
 *   All device apps
 *       ↓  IP packets
 *   Android TUN device  (10.99.0.1/24, established by VpnService.Builder)
 *       ↓  tun_fd passed to native lib
 *   hev-socks5-tunnel   (lwIP TCP/IP stack, handles TCP + UDP)
 *       ↓  SOCKS5 protocol
 *   LocalSocks5Bridge   (only for HTTP proxy profiles — translates to HTTP CONNECT)
 *       ↓  HTTP CONNECT
 *   ─── OR ───
 *   Real SOCKS5 upstream  (for SOCKS5 proxy profiles, no bridge needed)
 */
public class ProxyVpnService extends VpnService {

    private static final String TAG        = "KILOProxy";
    private static final String CHANNEL_ID = "kiloproxy_channel";
    private static final int    NOTIF_ID   = 1;

    public static final String ACTION_START  = "com.kiloproxy.START";
    public static final String ACTION_STOP   = "com.kiloproxy.STOP";
    public static final String ACTION_STATUS = "com.kiloproxy.STATUS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_ERROR   = "error";

    private static volatile long    bytesSent     = 0;
    private static volatile long    bytesReceived = 0;
    private static volatile boolean isRunning     = false;

    public static boolean isRunning()     { return isRunning; }
    public static long getBytesSent()     { return bytesSent; }
    public static long getBytesReceived() { return bytesReceived; }

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService      tunnelThread;
    private LocalSocks5Bridge    httpBridge;
    private ProxyProfile         activeProfile;
    private final Handler        statsHandler  = new Handler(Looper.getMainLooper());
    private Runnable             statsRunnable;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopVpn(); return START_NOT_STICKY; }
        if (ACTION_START.equals(action)) {
            activeProfile = ProfileManager.getInstance(this).getActiveProfile();
            if (activeProfile == null) {
                broadcastStatus(false, "No proxy profile selected");
                stopSelf(); return START_NOT_STICKY;
            }
            if (!TunnelJni.isAvailable()) {
                broadcastStatus(false, "Native library not found. Run build_native.ps1 first.");
                stopSelf(); return START_NOT_STICKY;
            }
            startVpn();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() { stopVpn(); super.onDestroy(); }

    // ── Start ─────────────────────────────────────────────────────────────────

    private void startVpn() {
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Connecting…"));
        try {
            // 1. Establish TUN interface
            vpnInterface = new Builder()
                .setSession("KILOProxy")
                .addAddress("10.99.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(8500)
                .addDisallowedApplication(getPackageName())
                .establish();

            if (vpnInterface == null) {
                broadcastStatus(false, "VPN permission denied");
                stopSelf(); return;
            }

            // 2. HTTP proxy → start local SOCKS5 bridge; SOCKS5 proxy → direct
            String socks5Host;
            int    socks5Port;
            if (ProxyProfile.TYPE_HTTP.equals(activeProfile.getType())) {
                httpBridge = new LocalSocks5Bridge(activeProfile);
                httpBridge.start();
                socks5Host = "127.0.0.1";
                socks5Port = LocalSocks5Bridge.LOCAL_PORT;
                Log.i(TAG, "HTTP proxy → bridge on 127.0.0.1:" + socks5Port);
            } else {
                httpBridge = null;
                socks5Host = activeProfile.getHost();
                socks5Port = activeProfile.getPort();
            }

            // 3. Build YAML config and launch native tunnel
            String config = buildConfig(socks5Host, socks5Port);
            int    tunFd  = vpnInterface.getFd();

            isRunning = true; bytesSent = 0; bytesReceived = 0;
            tunnelThread = Executors.newSingleThreadExecutor();
            tunnelThread.submit(() -> {
                Log.i(TAG, "hev-socks5-tunnel starting, fd=" + tunFd);
                int rc = TunnelJni.mainFromStr(config, tunFd);
                Log.i(TAG, "hev-socks5-tunnel exited: " + rc);
            });

            startStatsPoller();
            String desc = activeProfile.getType() + " → "
                        + activeProfile.getHost() + ":" + activeProfile.getPort();
            updateNotification("Connected · " + desc);
            broadcastStatus(true, null);
            Log.i(TAG, "VPN started: " + activeProfile.getName());

        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN", e);
            broadcastStatus(false, e.getMessage());
            stopSelf();
        }
    }

    // ── Stats poller ──────────────────────────────────────────────────────────

    private void startStatsPoller() {
        statsRunnable = new Runnable() {
            @Override public void run() {
                if (!isRunning) return;
                bytesSent     = TunnelJni.getBytesSent();
                bytesReceived = TunnelJni.getBytesReceived();
                statsHandler.postDelayed(this, 1000);
            }
        };
        statsHandler.postDelayed(statsRunnable, 1000);
    }

    private void stopStatsPoller() {
        if (statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsRunnable = null;
        }
    }

    // ── YAML config builder ───────────────────────────────────────────────────

    private String buildConfig(String socks5Host, int socks5Port) {
        StringBuilder sb = new StringBuilder();
        sb.append("tunnel:\n");
        sb.append("  mtu: 8500\n");
        sb.append("  ipv4: 10.99.0.1\n");
        sb.append("  ipv6: 'fc00::1'\n");
        sb.append("socks5:\n");
        sb.append("  address: '").append(socks5Host).append("'\n");
        sb.append("  port: ").append(socks5Port).append("\n");
        // Pass credentials only for direct SOCKS5 (bridge handles HTTP auth itself)
        if (ProxyProfile.TYPE_SOCKS5.equals(activeProfile.getType())
                && activeProfile.isRequiresAuth()
                && activeProfile.getUsername() != null
                && !activeProfile.getUsername().isEmpty()) {
            sb.append("  username: '").append(escape(activeProfile.getUsername())).append("'\n");
            sb.append("  password: '").append(escape(activeProfile.getPassword())).append("'\n");
        }
        sb.append("  udp: 'tcp'\n");
        sb.append("misc:\n");
        sb.append("  task-stack-size: 81920\n");
        sb.append("  tcp-buffer-size: 65536\n");
        sb.append("  connect-timeout: 10000\n");
        sb.append("  tcp-read-write-timeout: 300000\n");
        sb.append("  log-level: warn\n");
        return sb.toString();
    }

    /** Escape single-quotes in YAML string values */
    private static String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private void stopVpn() {
        if (!isRunning && vpnInterface == null) return;
        isRunning = false;
        stopStatsPoller();
        try { TunnelJni.quit(); } catch (Throwable ignored) {}
        if (tunnelThread != null) { tunnelThread.shutdownNow(); tunnelThread = null; }
        if (httpBridge   != null) { httpBridge.stop();          httpBridge   = null; }
        try {
            if (vpnInterface != null) { vpnInterface.close(); vpnInterface = null; }
        } catch (IOException e) { Log.e(TAG, "Close VPN error", e); }
        broadcastStatus(false, null);
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "VPN stopped");
    }

    private void broadcastStatus(boolean running, String error) {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_RUNNING, running);
        if (error != null) i.putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "KILOProxy", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Proxy tunnel status");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stopI = new Intent(this, ProxyVpnService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopI,
            PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KILOProxy").setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Disconnect", stop)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
