package com.kiloproxy.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Handles HTTP CONNECT proxy tunnel setup.
 */
public class HttpConnectHandler {

    /**
     * Sends HTTP CONNECT to establish a tunnel through an HTTP proxy.
     * After this call, the socket is ready to relay data to destHost:destPort.
     */
    public static void connect(Socket socket, String destHost, int destPort,
                                String username, String password) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(destHost).append(":").append(destPort)
               .append(" HTTP/1.1\r\n");
        request.append("Host: ").append(destHost).append(":").append(destPort).append("\r\n");

        if (username != null && !username.isEmpty()) {
            String credentials = username + ":" + password;
            String encoded;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            } else {
                encoded = android.util.Base64.encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP);
            }
            request.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        request.append("Proxy-Connection: Keep-Alive\r\n");
        request.append("\r\n");

        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Add a timeout to avoid infinite blocking
        int oldTimeout = socket.getSoTimeout();
        socket.setSoTimeout(10000); // 10 seconds timeout

        try {
            // Read response headers
            StringBuilder response = new StringBuilder();
            int cur;
            boolean foundHeaderEnd = false;

            // Limit to 8192 bytes to avoid memory exhaustion from garbage data
            while (response.length() < 8192 && (cur = in.read()) != -1) {
                response.append((char) cur);
                if (response.length() >= 4 && response.substring(response.length() - 4).equals("\r\n\r\n")) {
                    foundHeaderEnd = true;
                    break;
                }
            }

            if (!foundHeaderEnd) {
                throw new IOException("HTTP CONNECT failed: Invalid response or header too large");
            }

            String responseStr = response.toString();
            String[] lines = responseStr.split("\r\n", 2);
            String statusLine = lines.length > 0 ? lines[0].trim() : "";

            if (!statusLine.startsWith("HTTP/1.")) {
                throw new IOException("HTTP CONNECT failed: " + statusLine);
            }

            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2 || !"200".equals(statusParts[1])) {
                throw new IOException("HTTP CONNECT failed: " + statusLine);
            }
        } finally {
            socket.setSoTimeout(oldTimeout);
        }
        // Tunnel established
    }
}
