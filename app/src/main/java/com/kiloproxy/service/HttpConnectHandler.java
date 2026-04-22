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

        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        // Read response headers
        StringBuilder response = new StringBuilder();
        int prev = -1, cur;
        while ((cur = in.read()) != -1) {
            response.append((char) cur);
            // End of headers = \r\n\r\n
            if (prev == '\n' && cur == '\r') {
                // could be end soon
            }
            if (response.length() >= 4) {
                String tail = response.substring(response.length() - 4);
                if (tail.equals("\r\n\r\n")) break;
            }
            prev = cur;
        }

        String responseStr = response.toString();
        if (!responseStr.startsWith("HTTP/1.") || !responseStr.contains(" 200")) {
            // Extract status line for better error message
            String statusLine = responseStr.split("\r\n")[0];
            throw new IOException("HTTP CONNECT failed: " + statusLine);
        }
        // Tunnel established
    }
}
