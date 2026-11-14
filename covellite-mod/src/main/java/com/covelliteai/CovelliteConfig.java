package com.covelliteai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Small, dependency-free configuration for the client mod. Values live in
 * {@code config/covemcp.properties} inside the Minecraft instance.
 */
public final class CovelliteConfig {
    public static final int DEFAULT_PORT = 17452;
    public static final String DEFAULT_PLAYER_NAME = "AIPlayer65";

    public final String playerName;
    public final int port;
    public final int maxActions;
    public final int maxActionHistory;
    public final int stateUpdateTicks;
    public final int maxChatHistory;
    public final boolean autoConnect;
    public final String serverAddress;
    /**
     * Every value read from {@code covemcp.properties}. The AI runtime parses
     * its own {@code agent*} keys from here so new provider options do not
     * require a new field in this class.
     */
    public final Map<String, String> properties;

    private CovelliteConfig(String playerName, int port, int maxActions,
                            int maxActionHistory, int stateUpdateTicks,
                            int maxChatHistory, boolean autoConnect,
                            String serverAddress, Map<String, String> properties) {
        this.playerName = playerName;
        this.port = port;
        this.maxActions = maxActions;
        this.maxActionHistory = maxActionHistory;
        this.stateUpdateTicks = stateUpdateTicks;
        this.maxChatHistory = maxChatHistory;
        this.autoConnect = autoConnect;
        this.serverAddress = serverAddress;
        this.properties = Map.copyOf(properties);
    }

    public static CovelliteConfig load(Path configDir) {
        Path file = configDir.resolve("covemcp.properties");
        Path legacy = configDir.resolve("covelliteai.properties");
        if (!Files.exists(file) && Files.exists(legacy)) {
            // Migrate the previous install without losing port or other settings.
            try {
                Files.createDirectories(configDir);
                Files.copy(legacy, file);
            } catch (IOException e) {
                CovelliteAI.LOGGER.warn("Could not migrate {} to {}: {}",
                        legacy, file, e.getMessage());
            }
        }
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
                    p.store(out, "CoveMCP client configuration");
                }
            } catch (IOException e) {
                CovelliteAI.LOGGER.warn("Could not create {}; using defaults", file, e);
            }
        }

        String name = p.getProperty("playerName", DEFAULT_PLAYER_NAME).trim();
        if (name.isEmpty() || "CovelliteAI".equals(name)) {
            // Branding rename: keep the same identity slot but expose the new name.
            name = DEFAULT_PLAYER_NAME;
            p.setProperty("playerName", name);
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "CoveMCP client configuration");
            } catch (IOException e) {
                CovelliteAI.LOGGER.warn("Could not update {} with the new player name", file);
            }
        }
        int port = clampInt(p.getProperty("port"), DEFAULT_PORT, 1024, 65535);
        int maxActions = clampInt(p.getProperty("maxActions"), 32, 1, 256);
        int maxHistory = clampInt(p.getProperty("maxActionHistory"), 256, 1, 4096);
        int stateTicks = clampInt(p.getProperty("stateUpdateTicks"), 5, 1, 200);
        int maxChat = clampInt(p.getProperty("maxChatHistory"), 256, 1, 4096);
        boolean autoConnect = Boolean.parseBoolean(p.getProperty("autoConnect", "false").trim());
        String serverAddress = p.getProperty("serverAddress", "").trim();
        Map<String, String> all = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            all.put(key, p.getProperty(key));
        }
        return new CovelliteConfig(name, port, maxActions, maxHistory, stateTicks, maxChat,
                autoConnect, serverAddress, all);
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
