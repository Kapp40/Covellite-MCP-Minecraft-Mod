package com.covelliteai.server;

import covellite.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Per-world exploration map: which chunks the companion has visited and which
 * places it found interesting.
 *
 * <p>Two things are stored, both bounded: a set of visited chunks (packed
 * coordinates) used to answer "where is unexplored", and named points of
 * interest (caves, forests, water, villages, player-marked places). The file
 * lives next to {@code WorldMemory} under {@code mods/covemcp/maps/}, so two
 * saves never share a map.</p>
 *
 * <p>All methods are synchronized and only parse/write small JSON documents;
 * nothing here touches the world except {@link #scan}, which only reads.</p>
 */
public final class ExplorationMap {

    /** A named place worth remembering. */
    public record Poi(String name, String kind, int x, int y, int z, String dim, long at) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", this.name);
            map.put("kind", this.kind);
            map.put("x", this.x);
            map.put("y", this.y);
            map.put("z", this.z);
            map.put("dim", this.dim);
            map.put("at", this.at);
            return map;
        }

        public static Poi fromMap(Map<String, Object> map) {
            return new Poi(
                    string(map.get("name"), "place"),
                    string(map.get("kind"), "poi"),
                    intOf(map.get("x")),
                    intOf(map.get("y")),
                    intOf(map.get("z")),
                    string(map.get("dim"), "minecraft:overworld"),
                    map.get("at") instanceof Number n ? n.longValue() : 0L);
        }

        private static String string(Object value, String fallback) {
            return value == null ? fallback : String.valueOf(value);
        }

        private static int intOf(Object value) {
            return value instanceof Number n ? n.intValue() : 0;
        }
    }

    private static final int MAX_CHUNKS = 40_000;
    private static final int MAX_POIS = 400;
    private static final int SAVE_EVERY_CHUNKS = 25;

    private static final Map<String, ExplorationMap> CACHE = new HashMap<>();

    private final Path file;
    private final Set<Long> chunks = new HashSet<>();
    private final Map<String, Poi> pois = new LinkedHashMap<>();
    private int unsavedChunks;

    private ExplorationMap(Path file) {
        this.file = file;
        load();
    }

    /** Opens (or reuses) the map of the running world. */
    public static synchronized ExplorationMap forWorld(MinecraftServer server) {
        String level = server.getWorldData().getLevelName();
        String safe = level == null ? "" : level.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "world";
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("mods").resolve("covemcp").resolve("maps");
        Path file = dir.resolve(safe + ".json");
        return CACHE.computeIfAbsent(file.toAbsolutePath().toString(), key -> new ExplorationMap(file));
    }

    public static int[] chunkOf(int blockX, int blockZ) {
        return new int[]{blockX >> 4, blockZ >> 4};
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------
    // Chunks
    // ------------------------------------------------------------------

    /** @return true when this chunk was not explored before */
    public synchronized boolean markChunk(int chunkX, int chunkZ) {
        if (this.chunks.size() >= MAX_CHUNKS) {
            return false;
        }
        boolean added = this.chunks.add(chunkKey(chunkX, chunkZ));
        if (added) {
            this.unsavedChunks++;
            if (this.unsavedChunks >= SAVE_EVERY_CHUNKS) {
                save();
            }
        }
        return added;
    }

    public synchronized boolean isExplored(int chunkX, int chunkZ) {
        return this.chunks.contains(chunkKey(chunkX, chunkZ));
    }

    public synchronized int exploredChunks() {
        return this.chunks.size();
    }

    /**
     * Nearest chunk that has not been visited, searching in rings so the
     * companion always has a concrete next direction.
     */
    public synchronized int[] nearestUnexplored(int originChunkX, int originChunkZ) {
        for (int ring = 1; ring <= 48; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = originChunkX + dx;
                    int cz = originChunkZ + dz;
                    if (!isExplored(cx, cz)) {
                        return new int[]{cx, cz};
                    }
                }
            }
        }
        return new int[]{originChunkX, originChunkZ};
    }

    // ------------------------------------------------------------------
    // Points of interest
    // ------------------------------------------------------------------

    public synchronized Poi markPoi(String name, String kind, int x, int y, int z, String dim) {
        String label = name == null || name.isBlank()
                ? (kind == null ? "lugar" : kind) + " " + x + " " + y + " " + z
                : name.trim();
        Poi poi = new Poi(label, kind == null || kind.isBlank() ? "poi" : kind.trim(),
                x, y, z, dim == null ? "minecraft:overworld" : dim,
                System.currentTimeMillis() / 1000L);
        this.pois.put(key(label), poi);
        if (this.pois.size() > MAX_POIS) {
            // Drop the oldest entry, keeping the map bounded.
            String oldest = this.pois.keySet().iterator().next();
            this.pois.remove(oldest);
        }
        save();
        return poi;
    }

    public synchronized Poi find(String name) {
        return this.pois.get(key(name));
    }

    public synchronized List<Poi> pois() {
        return List.copyOf(this.pois.values());
    }

    public synchronized boolean forget(String name) {
        boolean removed = this.pois.remove(key(name)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Map<String, Object> status(int chunkX, int chunkZ) {
        List<Object> places = new ArrayList<>();
        for (Poi poi : this.pois.values()) {
            if (places.size() >= 40) {
                break;
            }
            places.add(poi.toMap());
        }
        int[] next = nearestUnexplored(chunkX, chunkZ);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("explored_chunks", this.chunks.size());
        out.put("coverage", coverageLabel());
        out.put("pois", places.size());
        out.put("places", places);
        out.put("next_unexplored_chunk", List.of(next[0], next[1]));
        out.put("next_unexplored_blocks", List.of(next[0] * 16 + 8, next[1] * 16 + 8));
        out.put("file", this.file.toAbsolutePath().toString());
        return out;
    }

    /** Coarse coverage label, handy for the model without counting. */
    private String coverageLabel() {
        int count = this.chunks.size();
        if (count < 64) {
            return "casi_sin_explorar";
        }
        if (count < 512) {
            return "zona_inicial";
        }
        if (count < 4096) {
            return "region_conocida";
        }
        return "ampliamente_explorado";
    }

    // ------------------------------------------------------------------
    // Terrain scan
    // ------------------------------------------------------------------

    /**
     * Samples the blocks around the companion and classifies the surroundings.
     * Reads the world only; the caller decides whether to store a POI.
     */
    public static Map<String, Object> scan(ServerPlayer player, int radius, ExplorationMap map) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int water = 0;
        int logs = 0;
        int leaves = 0;
        int stone = 0;
        int ores = 0;
        int lava = 0;

        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius / 2, -radius),
                origin.offset(radius, radius / 2, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                water++;
            } else if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                lava++;
            } else if (state.is(BlockTags.LOGS)) {
                logs++;
            } else if (state.is(BlockTags.LEAVES)) {
                leaves++;
            } else if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(BlockTags.STONE_ORE_REPLACEABLES)) {
                stone++;
            }
            if (state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                    || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                    || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.REDSTONE_ORES)
                    || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.EMERALD_ORES)) {
                ores++;
            }
        }

        String kind;
        if (lava > 0 && stone > 40) {
            kind = "lava";
        } else if (ores > 12) {
            kind = "mena";
        } else if (stone > 200 && player.getY() < 60) {
            kind = "cueva";
        } else if (water > 60) {
            kind = "agua";
        } else if (leaves + logs > 120) {
            kind = "bosque";
        } else if (stone > 260) {
            kind = "montana";
        } else {
            kind = "campo";
        }

        Poi poi = null;
        if (map != null && !kind.equals("campo")) {
            poi = map.markPoi(null, kind, origin.getX(), origin.getY(), origin.getZ(),
                    level.dimension().location().toString());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("water", water);
        out.put("lava", lava);
        out.put("logs", logs);
        out.put("leaves", leaves);
        out.put("stone", stone);
        out.put("ores", ores);
        if (poi != null) {
            out.put("place", poi.name());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            String text = Files.readString(this.file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            Map<String, Object> root = Json.parseObject(text);
            if (root.get("chunks") instanceof List<?> chunks) {
                for (Object item : chunks) {
                    String raw = String.valueOf(item);
                    int comma = raw.indexOf(',');
                    if (comma <= 0) {
                        continue;
                    }
                    try {
                        int cx = Integer.parseInt(raw.substring(0, comma).trim());
                        int cz = Integer.parseInt(raw.substring(comma + 1).trim());
                        this.chunks.add(chunkKey(cx, cz));
                    } catch (NumberFormatException ignored) {
                        // A truncated entry must not stop the load.
                    }
                }
            }
            if (root.get("pois") instanceof List<?> places) {
                for (Object item : places) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        raw.forEach((k, v) -> typed.put(String.valueOf(k), v));
                        Poi poi = Poi.fromMap(typed);
                        this.pois.put(key(poi.name()), poi);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[explore] cannot read map " + this.file + ": " + e.getMessage());
        }
    }

    public synchronized void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> chunkList = new ArrayList<>(this.chunks.size());
        for (long key : this.chunks) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            chunkList.add(cx + "," + cz);
        }
        root.put("chunks", chunkList);
        List<Object> placeList = new ArrayList<>();
        for (Poi poi : this.pois.values()) {
            placeList.add(poi.toMap());
        }
        root.put("pois", placeList);
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            Files.writeString(this.file, Json.stringify(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            this.unsavedChunks = 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("[explore] cannot save map " + this.file + ": " + e.getMessage());
        }
    }
}
