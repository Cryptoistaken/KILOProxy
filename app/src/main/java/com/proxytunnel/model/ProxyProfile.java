package com.proxytunnel.model;

import java.util.UUID;

public class ProxyProfile {
    public static final String TYPE_HTTP = "HTTP";
    public static final String TYPE_SOCKS5 = "SOCKS5";

    private String id;
    private String name;
    private String host;
    private int port;
    private String type; // HTTP or SOCKS5
    private String username;
    private String password;
    private boolean requiresAuth;

    public ProxyProfile() {
        this.id = UUID.randomUUID().toString();
    }

    public ProxyProfile(String name, String host, int port, String type) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.host = host;
        this.port = port;
        this.type = type;
        this.requiresAuth = false;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isRequiresAuth() { return requiresAuth; }
    public void setRequiresAuth(boolean requiresAuth) { this.requiresAuth = requiresAuth; }

    public String getDisplayAddress() {
        return host + ":" + port;
    }
}
