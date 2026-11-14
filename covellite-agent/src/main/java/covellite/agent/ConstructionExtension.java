package covellite.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construction and excavation pack.
 *
 * <p>It owns no world code: every block is placed or broken through the existing
 * {@code place_block} / {@code break_block} tools, which already validate the
 * position on the server thread. That is the whole point of the extension
 * system — a new capability built by composing what the mod already exposes,
 * without touching {@code BridgeApi}.</p>
 *
 * <p>Every operation is capped so a bad prompt cannot turn into 10k block
 * changes in one turn.</p>
 */
public final class ConstructionExtension implements AgentExtension {

    /** Hard ceiling of block operations per tool call. */
    private static final int MAX_BLOCKS = 256;

    @Override
    public String id() {
        return "construction";
    }

    @Override
    public String description() {
        return "Paredes, cajas y tuneles usando place_block y break_block";
    }

    @Override
    public void register(AgentExtensionContext context) {
        AgentToolRegistry.ToolExecutor remote = context.remote();

        context.tool(new AgentProvider.ToolSpec("build_wall",
                        "Construye una pared recta de bloques desde una esquina. axis es x o z.",
                        schema(props(
                                "x", integer("X de la esquina."),
                                "y", integer("Y de la base."),
                                "z", integer("Z de la esquina."),
                                "length", integer("Largo en bloques (1-64)."),
                                "height", integer("Alto en bloques (1-8)."),
                                "axis", string("x o z: direccion de la pared."),
                                "block", string("Id del bloque, por ejemplo minecraft:stone.")),
                                List.of("x", "y", "z", "length", "block"))),
                (name, args) -> {
                    int x = intArg(args, "x", 0);
                    int y = intArg(args, "y", 64);
                    int z = intArg(args, "z", 0);
                    int length = clamp(intArg(args, "length", 1), 1, 64);
                    int height = clamp(intArg(args, "height", 1), 1, 8);
                    String axis = axis(args);
                    String block = String.valueOf(args.getOrDefault("block", "minecraft:stone"));

                    List<Object> targets = new ArrayList<>();
                    for (int step = 0; step < length; step++) {
                        for (int up = 0; up < height; up++) {
                            int bx = axis.equals("x") ? x + step : x;
                            int bz = axis.equals("z") ? z + step : z;
                            targets.add(new int[]{bx, y + up, bz});
                        }
                    }
                    return placeAll(remote, targets, block, "wall");
                });

        context.tool(new AgentProvider.ToolSpec("build_box",
                        "Construye el perimetro (cuatro paredes) de una caja hueca.",
                        schema(props(
                                "x", integer("X de la esquina."),
                                "y", integer("Y de la base."),
                                "z", integer("Z de la esquina."),
                                "width", integer("Ancho 1-32."),
                                "depth", integer("Profundidad 1-32."),
                                "height", integer("Alto 1-8."),
                                "block", string("Id del bloque.")),
                                List.of("x", "y", "z", "width", "depth", "block"))),
                (name, args) -> {
                    int x = intArg(args, "x", 0);
                    int y = intArg(args, "y", 64);
                    int z = intArg(args, "z", 0);
                    int width = clamp(intArg(args, "width", 3), 1, 32);
                    int depth = clamp(intArg(args, "depth", 3), 1, 32);
                    int height = clamp(intArg(args, "height", 3), 1, 8);
                    String block = String.valueOf(args.getOrDefault("block", "minecraft:stone"));

                    List<Object> targets = new ArrayList<>();
                    for (int up = 0; up < height; up++) {
                        for (int dx = 0; dx < width; dx++) {
                            targets.add(new int[]{x + dx, y + up, z});
                            targets.add(new int[]{x + dx, y + up, z + depth - 1});
                        }
                        for (int dz = 1; dz < depth - 1; dz++) {
                            targets.add(new int[]{x, y + up, z + dz});
                            targets.add(new int[]{x + width - 1, y + up, z + dz});
                        }
                    }
                    return placeAll(remote, targets, block, "box");
                });

        context.tool(new AgentProvider.ToolSpec("mine_tunnel",
                        "Excava un tunel recto de 2 de alto y 1 de ancho desde una posicion.",
                        schema(props(
                                "x", integer("X de inicio."),
                                "y", integer("Y de los pies."),
                                "z", integer("Z de inicio."),
                                "length", integer("Largo 1-64."),
                                "axis", string("x o z.")),
                                List.of("x", "y", "z", "length"))),
                (name, args) -> {
                    int x = intArg(args, "x", 0);
                    int y = intArg(args, "y", 64);
                    int z = intArg(args, "z", 0);
                    int length = clamp(intArg(args, "length", 1), 1, 64);
                    String axis = axis(args);

                    int broken = 0;
                    int failed = 0;
                    List<String> errors = new ArrayList<>();
                    for (int step = 0; step < length; step++) {
                        int bx = axis.equals("x") ? x + step : x;
                        int bz = axis.equals("z") ? z + step : z;
                        for (int up = 0; up < 2; up++) {
                            Map<String, Object> params = new LinkedHashMap<>();
                            params.put("x", bx);
                            params.put("y", y + up);
                            params.put("z", bz);
                            try {
                                remote.execute("break_block", params);
                                broken++;
                            } catch (Exception e) {
                                failed++;
                                if (errors.size() < 3) {
                                    errors.add(e.getMessage() == null ? "error" : e.getMessage());
                                }
                            }
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("broken", broken);
                    out.put("failed", failed);
                    out.put("errors", errors);
                    return out;
                });
    }

    private static Map<String, Object> placeAll(AgentToolRegistry.ToolExecutor remote,
                                                List<Object> targets, String block, String kind) {
        int placed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        int budget = 0;
        for (Object raw : targets) {
            if (budget++ >= MAX_BLOCKS) {
                break;
            }
            int[] target = (int[]) raw;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("x", target[0]);
            params.put("y", target[1]);
            params.put("z", target[2]);
            params.put("block", block);
            try {
                remote.execute("place_block", params);
                placed++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 3) {
                    errors.add(e.getMessage() == null ? "error" : e.getMessage());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("placed", placed);
        out.put("failed", failed);
        out.put("errors", errors);
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String axis(Map<String, Object> args) {
        String raw = String.valueOf(args.getOrDefault("axis", "x")).toLowerCase(Locale.ROOT);
        return raw.equals("z") ? "z" : "x";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        if (!required.isEmpty()) {
            map.put("required", new ArrayList<Object>(required));
        }
        return map;
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }
}
