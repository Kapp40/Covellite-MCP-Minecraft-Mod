package covellite.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of a mining expedition: four main galleries plus their side branches.
 *
 * <p>Pure Java, no Minecraft, so the shape can be tested. The task in the mod
 * only walks this plan and digs; it does not invent coordinates.</p>
 *
 * <pre>
 *   center, four directions, each main gallery {@code length} blocks long.
 *   Every {@code step} blocks, a side branch of {@code lateral} blocks is dug
 *   to both sides.
 * </pre>
 */
public final class ExpeditionPlan {

    /** A cell the companion must open, at feet level; the task also clears the one above. */
    public record Cell(int x, int y, int z) {
    }

    /** Horizontal heading. */
    public record Dir(int dx, int dz, String name) {
    }

    /** One straight run of cells. Main galleries come first, then their branches. */
    public record Segment(String id, boolean main, int dir, int index, List<Cell> cells) {
    }

    public static final int DEFAULT_LENGTH = 60;
    public static final int DEFAULT_STEP = 5;
    public static final int DEFAULT_LATERAL = 5;
    public static final int DEFAULT_DEPTH = 20;
    public static final int DEFAULT_TRAVEL = 250;

    private ExpeditionPlan() {
    }

    /** The four headings, starting from the one the companion is facing. */
    public static List<Dir> dirs(int yawQuarterTurns) {
        List<Dir> all = List.of(
                new Dir(0, -1, "norte"),
                new Dir(1, 0, "este"),
                new Dir(0, 1, "sur"),
                new Dir(-1, 0, "oeste"));
        int start = Math.floorMod(yawQuarterTurns, 4);
        List<Dir> ordered = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            ordered.add(all.get((start + i) % 4));
        }
        return ordered;
    }

    /** Maps a yaw in degrees to a quarter turn (0 = north). */
    public static int quarterTurns(float yaw) {
        float normalized = ((yaw % 360.0F) + 360.0F) % 360.0F;
        int turns = Math.round(normalized / 90.0F);
        // Minecraft yaw 0 looks south (+Z); the list starts at north.
        return Math.floorMod(turns + 2, 4);
    }

    /**
     * Builds the full plan: for each direction in the given facing order, one main
     * gallery of {@code length} cells and, every {@code step} cells, two side
     * branches of {@code lateral} cells.
     */
    public static List<Segment> build(int x, int y, int z, int length, int step, int lateral,
                                      int yawQuarterTurns) {
        int safeLength = Math.max(4, Math.min(128, length));
        int safeStep = Math.max(2, Math.min(32, step));
        int safeLateral = Math.max(1, Math.min(32, lateral));
        List<Segment> segments = new ArrayList<>();
        List<Dir> directions = dirs(yawQuarterTurns);
        for (int d = 0; d < directions.size(); d++) {
            Dir dir = directions.get(d);
            List<Cell> main = new ArrayList<>(safeLength);
            for (int i = 1; i <= safeLength; i++) {
                main.add(new Cell(x + dir.dx() * i, y, z + dir.dz() * i));
            }
            segments.add(new Segment("main:" + d, true, d, 0, List.copyOf(main)));

            int px = -dir.dz();
            int pz = dir.dx();
            for (int at = safeStep; at <= safeLength; at += safeStep) {
                Cell hub = new Cell(x + dir.dx() * at, y, z + dir.dz() * at);
                for (int side = -1; side <= 1; side += 2) {
                    List<Cell> branch = new ArrayList<>(safeLateral);
                    for (int i = 1; i <= safeLateral; i++) {
                        branch.add(new Cell(hub.x() + px * side * i, y, hub.z() + pz * side * i));
                    }
                    segments.add(new Segment("lat:" + d + ":" + at + ":" + (side < 0 ? "a" : "b"),
                            false, d, at, List.copyOf(branch)));
                }
            }
        }
        return segments;
    }

    /** Total blocks the plan asks to open, counting both levels of every cell. */
    public static int blockCount(List<Segment> segments) {
        int cells = 0;
        for (Segment segment : segments) {
            cells += segment.cells().size();
        }
        return cells * 2;
    }

    /** Name of the ore an item drops from, or empty when it is not an ore. */
    public static String oreFamily(String itemName) {
        if (itemName == null) {
            return "";
        }
        String name = itemName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("diamond")) return "diamante";
        if (name.contains("emerald")) return "esmeralda";
        if (name.contains("lapis")) return "lapislazuli";
        if (name.contains("redstone")) return "redstone";
        if (name.contains("copper")) return "cobre";
        if (name.contains("iron") || name.contains("raw_iron")) return "hierro";
        if (name.contains("gold") || name.contains("raw_gold")) return "oro";
        if (name.contains("coal")) return "carbon";
        if (name.contains("quartz")) return "cuarzo";
        if (name.contains("debris") || name.contains("netherite")) return "netherita";
        return "";
    }
}
