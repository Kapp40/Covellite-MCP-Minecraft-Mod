package com.covelliteai.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.covelliteai.CovelliteAI;

import covellite.core.json.Json;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Persistent places the companion remembers ("home", "iron mine"...), one JSON
 * file per world under {@code <game dir>/mods/covemcp/memory/}. Only data
 * lives there; no jars.
 *
 * <p>Names are matched ignoring case, accents and extra spaces, so
 * {@code "Casa"} and {@code "casa "} are the same place.</p>
 */
public final class WorldMemory {

    public record Place(String name, double x, double y, double z, String dim, String note, long savedAt) {}

    private final Path file;
    private final String worldName;
    private final Map<String, Place> places = new LinkedHashMap<>();

    private WorldMemory(Path file, String worldName) {
        this.file = file;
        this.worldName = worldName;
        load();
    }

    /** Opens (or creates lazily) the memory file of the world the server is running. */
    public static WorldMemory forWorld(MinecraftServer server) {
        String level = server.getWorldData().getLevelName();
        String safe = level == null ? "" : level.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "world";
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("mods").resolve("covemcp").resolve("memory");
        return new WorldMemory(dir.resolve(safe + ".json"), level == null ? "world" : level);
    }

    static String key(String name) {
        String text = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    public synchronized boolean put(String name, double x, double y, double z, String dim, String note) {
        String key = key(name);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        boolean existed = this.places.containsKey(key);
        this.places.put(key, new Place(name.trim(), x, y, z, dim,
                note == null || note.isBlank() ? null : note.trim(), System.currentTimeMillis() / 1000L));
        save();
        return existed;
    }

    /** Exact match first, then a unique "contains" match. */
    public synchronized Place find(String name) {
        String key = key(name);
        Place exact = this.places.get(key);
        if (exact != null || key.isEmpty()) {
            return exact;
        }
        Place found = null;
        for (Map.Entry<String, Place> entry : this.places.entrySet()) {
            if (entry.getKey().contains(key) || key.contains(entry.getKey())) {
                if (found != null) {
                    return null; // ambiguous
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    public synchronized boolean remove(String name) {
        Place place = find(name);
        if (place == null) {
            return false;
        }
        this.places.remove(key(place.name()));
        save();
        return true;
    }

    public synchronized List<Place> all() {
        return new ArrayList<>(this.places.values());
    }

    public String worldName() {
        return this.worldName;
    }

    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(this.file, StandardCharsets.UTF_8));
            if (!(root.get("places") instanceof Map<?, ?> stored)) {
                return;
            }
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> p)) {
                    continue;
                }
                String name = p.get("name") == null ? String.valueOf(entry.getKey()) : String.valueOf(p.get("name"));
                this.places.put(key(name), new Place(name,
                        number(p.get("x")), number(p.get("y")), number(p.get("z")),
                        p.get("dim") == null ? "overworld" : String.valueOf(p.get("dim")),
                        p.get("note") == null ? null : String.valueOf(p.get("note")),
                        (long) number(p.get("saved_at"))));
            }
        } catch (IOException | RuntimeException e) {
            CovelliteAI.LOGGER.warn("Could not read world memory {}: {}", this.file, e.getMessage());
        }
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0D;
    }

    private void save() {
        Map<String, Object> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Place> entry : this.places.entrySet()) {
            Place place = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", place.name());
            item.put("x", Math.round(place.x() * 10.0D) / 10.0D);
            item.put("y", Math.round(place.y() * 10.0D) / 10.0D);
            item.put("z", Math.round(place.z() * 10.0D) / 10.0D);
            item.put("dim", place.dim());
            if (place.note() != null) {
                item.put("note", place.note());
            }
            item.put("saved_at", place.savedAt());
            stored.put(entry.getKey(), item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("world", this.worldName);
        root.put("places", stored);
        try {
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(temp, Json.pretty(root), StandardCharsets.UTF_8);
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CovelliteAI.LOGGER.warn("Could not save world memory {}: {}", this.file, e.getMessage());
        }
    }
}
