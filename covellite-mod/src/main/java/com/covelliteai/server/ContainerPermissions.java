package com.covelliteai.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.covelliteai.CovelliteAI;

import covellite.core.json.Json;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Persistent permission rules for containers (chests, barrels, hoppers...).
 *
 * <p>Each rule is tied to one block in one dimension and lists the item ids
 * Cove may take and put. An empty list means "nothing" for that direction;
 * {@code *} means "anything". Only the high-level tools
 * ({@code restock}, {@code deposit_items}, {@code find_container}) enforce
 * these rules; raw {@code container_take}/{@code container_put} stay manual,
 * so the model can still act on an explicit order.</p>
 */
public final class ContainerPermissions {

    public record Rule(int x, int y, int z, String dim, String name,
                       Set<String> take, Set<String> put, boolean all) {

        public boolean canTake(String itemId) {
            return this.all || matches(this.take, itemId);
        }

        public boolean canPut(String itemId) {
            return this.all || matches(this.put, itemId);
        }
    }

    private final Path file;
    private final String worldName;
    private final Map<String, Rule> rules = new LinkedHashMap<>();

    private ContainerPermissions(Path file, String worldName) {
        this.file = file;
        this.worldName = worldName;
        load();
    }

    public static ContainerPermissions forWorld(MinecraftServer server) {
        String level = server.getWorldData().getLevelName();
        String safe = level == null ? "" : level.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "world";
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("mods").resolve("covemcp").resolve("permissions");
        return new ContainerPermissions(dir.resolve(safe + ".json"),
                level == null ? "world" : level);
    }

    private static String key(String dim, int x, int y, int z) {
        return dim + ":" + x + "," + y + "," + z;
    }

    public synchronized boolean authorize(int x, int y, int z, String dim, String name,
                                          List<String> take, List<String> put, boolean all) {
        String key = key(dim, x, y, z);
        boolean existed = this.rules.containsKey(key);
        this.rules.put(key, new Rule(x, y, z, dim,
                name == null || name.isBlank() ? null : name.trim(),
                normalize(take), normalize(put), all));
        save();
        return existed;
    }

    public synchronized boolean revoke(String dim, int x, int y, int z) {
        boolean removed = this.rules.remove(key(dim, x, y, z)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Rule find(String dim, int x, int y, int z) {
        return this.rules.get(key(dim, x, y, z));
    }

    public synchronized List<Rule> all() {
        return new ArrayList<>(this.rules.values());
    }

    public String worldName() {
        return this.worldName;
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.toLowerCase(Locale.ROOT).trim());
            }
        }
        return out;
    }

    private static boolean matches(Set<String> list, String itemId) {
        if (itemId == null || itemId.isBlank() || list.isEmpty()) {
            return false;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (String raw : list) {
            String query = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
            if ("*".equals(query) || id.equals(raw) || shortId.equals(query)
                    || id.contains(query) || shortId.contains(query)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(this.file, StandardCharsets.UTF_8));
            if (!(root.get("containers") instanceof Map<?, ?> stored)) {
                return;
            }
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> c)) {
                    continue;
                }
                int x = (int) number(c.get("x"));
                int y = (int) number(c.get("y"));
                int z = (int) number(c.get("z"));
                String dim = c.get("dim") == null ? "overworld" : String.valueOf(c.get("dim"));
                String name = c.get("name") == null ? null : String.valueOf(c.get("name"));
                boolean all = Boolean.TRUE.equals(c.get("all"));
                this.rules.put(key(dim, x, y, z), new Rule(x, y, z, dim, name,
                        strings(c.get("take")), strings(c.get("put")), all));
            }
        } catch (IOException | RuntimeException e) {
            CovelliteAI.LOGGER.warn("Could not read container permissions {}: {}", this.file, e.getMessage());
        }
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0D;
    }

    private void save() {
        Map<String, Object> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Rule> entry : this.rules.entrySet()) {
            Rule rule = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("x", rule.x());
            item.put("y", rule.y());
            item.put("z", rule.z());
            item.put("dim", rule.dim());
            if (rule.name() != null) {
                item.put("name", rule.name());
            }
            item.put("all", rule.all());
            item.put("take", new ArrayList<>(rule.take()));
            item.put("put", new ArrayList<>(rule.put()));
            stored.put(entry.getKey(), item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("world", this.worldName);
        root.put("containers", stored);
        try {
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(temp, Json.pretty(root), StandardCharsets.UTF_8);
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CovelliteAI.LOGGER.warn("Could not save container permissions {}: {}", this.file, e.getMessage());
        }
    }
}
