package com.covelliteai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small, dependency-free configuration for the client mod. Values live in
 * {@code config/covelliteai.properties} inside the Minecraft instance.
 */
public final class CovelliteConfig {
    public static final int DEFAULT_PORT = 17452;
    public static final String DEFAULT_PLAYER_NAME = "CovelliteAI";

    public final String playerName;
    public final int port;
    public final int maxActions;
    public final int maxActionHistory;
    public final int stateUpdateTicks;
    public final int maxChatHistory;
    public final boolean autoConnect;
    public final String serverAddress;

    private CovelliteConfig(String playerName, int port, int maxActions,
                            int maxActionHistory, int stateUpdateTicks,
                            int maxChatHistory, boolean autoConnect,
                            String serverAddress) {
        this.playerName = playerName;
        this.port = port;
        this.maxActions = maxActions;
        this.maxActionHistory = maxActionHistory;
        this.stateUpdateTicks = stateUpdateTicks;
        this.maxChatHistory = maxChatHistory;
        this.autoConnect = autoConnect;
        this.serverAddress = serverAddress;
    }

    public static CovelliteConfig load(Path configDir) {
        Path file = configDir.resolve("covelliteai.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                CovelliteAI.LOGGER.warn("Could not read {}; using defaults", file, e);
            }
        } else {
            p.setProperty("playerName", DEFAULT_PLAYER_NAME);
            p.setProperty("port", Integer.toString(DEFAULT_PORT));
            p.setProperty("maxActions", "32");
            p.setProperty("maxActionHistory", "256");
            p.setProperty("stateUpdateTicks", "5");
            p.setProperty("maxChatHistory", "256");
            p.setProperty("autoConnect", "false");
            p.setProperty("serverAddress", "");
            try {
                Files.createDirectories(configDir);
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "CovelliteAI client configuration");
                }
            } catch (IOException e) {
                CovelliteAI.LOGGER.warn("Could not create {}; using defaults", file, e);
            }
        }

        String name = p.getProperty("playerName", DEFAULT_PLAYER_NAME).trim();
        int port = clampInt(p.getProperty("port"), DEFAULT_PORT, 1024, 65535);
        int maxActions = clampInt(p.getProperty("maxActions"), 32, 1, 256);
        int maxHistory = clampInt(p.getProperty("maxActionHistory"), 256, 1, 4096);
        int stateTicks = clampInt(p.getProperty("stateUpdateTicks"), 5, 1, 200);
        int maxChat = clampInt(p.getProperty("maxChatHistory"), 256, 1, 4096);
        boolean autoConnect = Boolean.parseBoolean(p.getProperty("autoConnect", "false").trim());
        String serverAddress = p.getProperty("serverAddress", "").trim();
        return new CovelliteConfig(name, port, maxActions, maxHistory, stateTicks, maxChat,
                autoConnect, serverAddress);
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
