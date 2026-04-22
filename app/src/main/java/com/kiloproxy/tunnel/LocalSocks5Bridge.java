package com.kiloproxy.tunnel;

import android.util.Log;

import com.kiloproxy.model.ProxyProfile;
import com.kiloproxy.service.HttpConnectHandler;
import com.kiloproxy.service.Socks5Handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LocalSocks5Bridge
 *
 * When the user configures an HTTP proxy (HTTP CONNECT), hev-socks5-tunnel
 * can't talk to it directly — it only speaks SOCKS5.
 *
 * This class starts a tiny local SOCKS5 server on 127.0.0.1:localPort.
 * hev-socks5-tunnel connects to this local port as if it were a SOCKS5 server.
 * The bridge then translates each SOCKS5 CONNECT request into an HTTP CONNECT
 * request and forwards it to the real upstream HTTP proxy.
 *
 * For SOCKS5 upstream profiles, this bridge is NOT used — hev-socks5-tunnel
 * connects directly to the upstream SOCKS5 server.
 */
public class LocalSocks5Bridge {

    private static final String TAG = "KILOProxy/Bridge";
    public  static final int    LOCAL_PORT = 10801; // loopback port for hev to connect to

    private final ProxyProfile  upstream;
    private ServerSocket        serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService     pool;

    public LocalSocks5Bridge(ProxyProfile upstream) {
        this.upstream = upstream;
    }

    /** Start listening. Returns the actual bound port. */
    public int start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
        running.set(true);
        pool = Executors.newCachedThreadPool();
        pool.submit(this::acceptLoop);
        Log.i(TAG, "Bridge listening on 127.0.0.1:" + serverSocket.getLocalPort());
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
        Log.i(TAG, "Bridge stopped");
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                pool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) Log.d(TAG, "Accept error: " + e.getMessage());
            }
        }
    }

    // ── Handle one SOCKS5 client ──────────────────────────────────────────────

    private void handleClient(Socket client) {
        try (Socket c = client) {
            InputStream  cIn  = c.getInputStream();
            OutputStream cOut = c.getOutputStream();

            // ── SOCKS5 greeting ──────────────────────────────────────────────
            int ver = cIn.read();
            if (ver != 5) return;
            int nMethods = cIn.read();
            byte[] methods = readFully(cIn, nMethods);
            // We always respond with NO AUTH (0x00)
            cOut.write(new byte[]{0x05, 0x00});
            cOut.flush();

            // ── SOCKS5 request ───────────────────────────────────────────────
            byte[] req = readFully(cIn, 4);  // VER CMD RSV ATYP
            if (req[1] != 0x01) { // only CONNECT
                cOut.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0}); // CMD_NOT_SUPPORTED
                return;
            }

            String destHost;
            int    atyp = req[3] & 0xFF;
            if (atyp == 0x01) {       // IPv4
                byte[] ip = readFully(cIn, 4);
                destHost = (ip[0]&0xFF)+"."+( ip[1]&0xFF)+"."+(ip[2]&0xFF)+"."+(ip[3]&0xFF);
            } else if (atyp == 0x03) { // domain
                int len = cIn.read() & 0xFF;
                destHost = new String(readFully(cIn, len), StandardCharsets.UTF_8);
            } else if (atyp == 0x04) { // IPv6 – just read and stringify
                byte[] ip6 = readFully(cIn, 16);
                destHost = ipv6String(ip6);
            } else {
                return;
            }
            byte[] portBytes = readFully(cIn, 2);
            int destPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // ── Connect to real upstream proxy ───────────────────────────────
            Socket upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(new InetSocketAddress(
                this.upstream.getHost(), this.upstream.getPort()), 10000);

            try {
                HttpConnectHandler.connect(upstream, destHost, destPort,
                    this.upstream.getUsername(), this.upstream.getPassword());
            } catch (IOException e) {
                upstream.close();
                cOut.write(new byte[]{0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0}); // CONNECTION_REFUSED
                return;
            }

            // ── Reply success to hev-socks5-tunnel ───────────────────────────
            cOut.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0});
            cOut.flush();

            // ── Bidirectional relay ──────────────────────────────────────────
            relay(cIn, cOut, upstream);

        } catch (IOException e) {
            Log.d(TAG, "Client error: " + e.getMessage());
        }
    }

    private void relay(InputStream cIn, OutputStream cOut, Socket upstream) throws IOException {
        InputStream  uIn  = upstream.getInputStream();
        OutputStream uOut = upstream.getOutputStream();

        AtomicBoolean done = new AtomicBoolean(false);

        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = uIn.read(buf)) != -1) {
                    cOut.write(buf, 0, n);
                    cOut.flush();
                }
            } catch (IOException ignored) {
            } finally {
                done.set(true);
                try { upstream.close(); } catch (IOException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = cIn.read(buf)) != -1) {
                uOut.write(buf, 0, n);
                uOut.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { upstream.close(); } catch (IOException ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int r = in.read(buf, read, count - read);
            if (r < 0) throw new IOException("Bridge: stream closed");
            read += r;
        }
        return buf;
    }

    private static String ipv6String(byte[] ip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%x", ((ip[i] & 0xFF) << 8) | (ip[i+1] & 0xFF)));
        }
        return sb.toString();
    }
}
