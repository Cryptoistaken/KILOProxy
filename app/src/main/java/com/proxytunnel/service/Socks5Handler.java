package com.proxytunnel.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Handles SOCKS5 proxy handshake and connection setup.
 */
public class Socks5Handler {

    private static final byte SOCKS_VERSION = 0x05;
    private static final byte AUTH_NO_AUTH = 0x00;
    private static final byte AUTH_USER_PASS = 0x02;
    private static final byte AUTH_NO_ACCEPTABLE = (byte) 0xFF;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ADDR_IPV4 = 0x01;
    private static final byte ADDR_DOMAIN = 0x03;
    private static final byte ADDR_IPV6 = 0x04;

    /**
     * Perform SOCKS5 handshake on an already-connected socket to the proxy server.
     * After this call, the socket is ready to relay data to destHost:destPort.
     */
    public static void connect(Socket socket, String destHost, int destPort,
                                String username, String password) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        boolean useAuth = username != null && !username.isEmpty();

        // Step 1: Send greeting
        if (useAuth) {
            out.write(new byte[]{SOCKS_VERSION, 2, AUTH_NO_AUTH, AUTH_USER_PASS});
        } else {
            out.write(new byte[]{SOCKS_VERSION, 1, AUTH_NO_AUTH});
        }
        out.flush();

        // Step 2: Read server method selection
        byte[] methodResponse = readBytes(in, 2);
        if (methodResponse[0] != SOCKS_VERSION) {
            throw new IOException("SOCKS5: Invalid server version");
        }
        byte method = methodResponse[1];
        if (method == AUTH_NO_ACCEPTABLE) {
            throw new IOException("SOCKS5: No acceptable auth method");
        }

        // Step 3: Authenticate if required
        if (method == AUTH_USER_PASS) {
            if (!useAuth) throw new IOException("SOCKS5: Server requires authentication");
            byte[] user = username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = password.getBytes(StandardCharsets.UTF_8);
            byte[] authReq = new byte[3 + user.length + pass.length];
            authReq[0] = 0x01; // auth version
            authReq[1] = (byte) user.length;
            System.arraycopy(user, 0, authReq, 2, user.length);
            authReq[2 + user.length] = (byte) pass.length;
            System.arraycopy(pass, 0, authReq, 3 + user.length, pass.length);
            out.write(authReq);
            out.flush();

            byte[] authResp = readBytes(in, 2);
            if (authResp[1] != 0x00) {
                throw new IOException("SOCKS5: Authentication failed");
            }
        }

        // Step 4: Send CONNECT request
        byte[] hostBytes = destHost.getBytes(StandardCharsets.UTF_8);
        byte[] connectReq = new byte[7 + hostBytes.length];
        connectReq[0] = SOCKS_VERSION;
        connectReq[1] = CMD_CONNECT;
        connectReq[2] = 0x00; // reserved
        connectReq[3] = ADDR_DOMAIN;
        connectReq[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.length);
        connectReq[5 + hostBytes.length] = (byte) ((destPort >> 8) & 0xFF);
        connectReq[6 + hostBytes.length] = (byte) (destPort & 0xFF);
        out.write(connectReq);
        out.flush();

        // Step 5: Read response
        byte[] resp = readBytes(in, 4);
        if (resp[0] != SOCKS_VERSION) throw new IOException("SOCKS5: Bad response version");
        if (resp[1] != 0x00) throw new IOException("SOCKS5: Connect failed, code=" + resp[1]);

        // Skip bound address
        byte addrType = resp[3];
        if (addrType == ADDR_IPV4) {
            readBytes(in, 4 + 2); // 4 bytes IP + 2 bytes port
        } else if (addrType == ADDR_IPV6) {
            readBytes(in, 16 + 2);
        } else if (addrType == ADDR_DOMAIN) {
            int len = in.read() & 0xFF;
            readBytes(in, len + 2);
        }
        // Socket is now connected through the proxy
    }

    private static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int r = in.read(buf, read, count - read);
            if (r < 0) throw new IOException("SOCKS5: Connection closed");
            read += r;
        }
        return buf;
    }
}
