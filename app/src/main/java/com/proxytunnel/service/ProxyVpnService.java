package com.proxytunnel.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.proxytunnel.R;
import com.proxytunnel.model.ProxyProfile;
import com.proxytunnel.ui.MainActivity;
import com.proxytunnel.util.ProfileManager;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ProxyVpnService - creates a local TUN interface and forwards all TCP traffic
 * through the configured HTTP or SOCKS5 proxy server.
 *
 * Packet flow:
 *   App traffic → TUN device → ProxyVpnService → reads IP packets →
 *   parses TCP header → opens socket to proxy → HTTP CONNECT or SOCKS5 handshake →
 *   relay bytes both ways
 */
public class ProxyVpnService extends VpnService {

    private static final String TAG = "ProxyVpnService";
    private static final String CHANNEL_ID = "proxy_tunnel_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.proxytunnel.START";
    public static final String ACTION_STOP  = "com.proxytunnel.STOP";

    // Broadcast sent back to UI
    public static final String ACTION_STATUS = "com.proxytunnel.STATUS";
    public static final String EXTRA_RUNNING  = "running";
    public static final String EXTRA_ERROR    = "error";

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService threadPool;
    private AtomicBoolean running = new AtomicBoolean(false);
    private ProxyProfile activeProfile;

    // Simple packet counter for display
    private static volatile long bytesSent = 0;
    private static volatile long bytesReceived = 0;
    private static volatile boolean isRunning = false;

    public static boolean isRunning() { return isRunning; }
    public static long getBytesSent() { return bytesSent; }
    public static long getBytesReceived() { return bytesReceived; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            activeProfile = ProfileManager.getInstance(this).getActiveProfile();
            if (activeProfile == null) {
                broadcastStatus(false, "No proxy profile selected");
                stopSelf();
                return START_NOT_STICKY;
            }
            startVpn();
        }
        return START_STICKY;
    }

    private void startVpn() {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"));

        try {
            Builder builder = new Builder();
            builder.setSession("ProxyTunnel");
            // Give the TUN a virtual address; all traffic will be intercepted
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0); // route all IPv4 traffic
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");
            // Exclude our own app to avoid loopback
            builder.addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                broadcastStatus(false, "VPN permission denied");
                stopSelf();
                return;
            }

            running.set(true);
            isRunning = true;
            bytesSent = 0;
            bytesReceived = 0;

            threadPool = Executors.newCachedThreadPool();

            // Main packet reading loop
            threadPool.submit(this::packetLoop);

            updateNotification("Connected via " + activeProfile.getType()
                    + " → " + activeProfile.getDisplayAddress());
            broadcastStatus(true, null);
            Log.i(TAG, "VPN started with profile: " + activeProfile.getName());

        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN", e);
            broadcastStatus(false, e.getMessage());
            stopSelf();
        }
    }

    /**
     * Reads IP packets from the TUN interface.
     * For each TCP connection (identified by dest IP + port), opens a proxy tunnel.
     * NOTE: This is a simplified implementation. A production-grade tun2socks
     * approach is recommended for full protocol support. This handles TCP streams.
     */
    private void packetLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        ByteBuffer packet = ByteBuffer.allocate(32767);

        while (running.get()) {
            try {
                packet.clear();
                int length = in.read(packet.array());
                if (length <= 0) {
                    Thread.sleep(10);
                    continue;
                }
                packet.limit(length);

                // Parse IP header
                byte versionIhl = packet.get(0);
                int version = (versionIhl >> 4) & 0xF;
                if (version != 4) continue; // Only IPv4

                int ihl = (versionIhl & 0xF) * 4;
                int protocol = packet.get(9) & 0xFF;

                // Only handle TCP (protocol 6)
                if (protocol != 6) continue;

                // Extract destination IP
                byte[] destIpBytes = new byte[4];
                packet.position(16);
                packet.get(destIpBytes);
                String destIp = InetAddress.getByAddress(destIpBytes).getHostAddress();

                // Extract destination port from TCP header
                int destPort = ((packet.get(ihl + 2) & 0xFF) << 8) | (packet.get(ihl + 3) & 0xFF);

                // Handle DNS specially (port 53 → forward to 8.8.8.8 directly)
                // For all other TCP, proxy it
                threadPool.submit(() -> handleTcpConnection(destIp, destPort, packet.array(), length, out));

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running.get()) Log.e(TAG, "Packet loop error", e);
            }
        }
    }

    private void handleTcpConnection(String destIp, int destPort, byte[] originalPacket,
                                      int packetLength, FileOutputStream tunOut) {
        try {
            Socket proxySocket = new Socket();
            protect(proxySocket); // CRITICAL: prevent VPN loop

            proxySocket.connect(
                new java.net.InetSocketAddress(activeProfile.getHost(), activeProfile.getPort()),
                10000
            );

            // Perform proxy handshake
            if (ProxyProfile.TYPE_SOCKS5.equals(activeProfile.getType())) {
                Socks5Handler.connect(proxySocket, destIp, destPort,
                    activeProfile.getUsername(), activeProfile.getPassword());
            } else {
                HttpConnectHandler.connect(proxySocket, destIp, destPort,
                    activeProfile.getUsername(), activeProfile.getPassword());
            }

            // Relay traffic bidirectionally
            InputStream proxyIn = proxySocket.getInputStream();
            OutputStream proxyOut = proxySocket.getOutputStream();

            // Forward original payload to proxy
            if (packetLength > 0) {
                proxyOut.write(originalPacket, 0, packetLength);
                proxyOut.flush();
                bytesSent += packetLength;
            }

            // Relay proxy responses back to TUN
            byte[] buf = new byte[4096];
            int read;
            while ((read = proxyIn.read(buf)) != -1) {
                tunOut.write(buf, 0, read);
                bytesReceived += read;
            }

            proxySocket.close();

        } catch (IOException e) {
            // Connection errors are common (timeouts, refused, etc.) - log quietly
            Log.d(TAG, "TCP relay error to " + destIp + ":" + destPort + " - " + e.getMessage());
        }
    }

    private void stopVpn() {
        running.set(false);
        isRunning = false;
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing VPN interface", e);
        }
        broadcastStatus(false, null);
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "VPN stopped");
    }

    private void broadcastStatus(boolean running, String error) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_RUNNING, running);
        if (error != null) intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ─── Notification helpers ─────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Proxy Tunnel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows proxy tunnel connection status");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProxyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxyTunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, "Disconnect", stopPi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
