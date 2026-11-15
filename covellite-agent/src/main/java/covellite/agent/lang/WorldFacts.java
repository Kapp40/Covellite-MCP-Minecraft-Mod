package covellite.agent.lang;

import covellite.core.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The small slice of the world Cove needs in order to speak and decide.
 *
 * <p>Deliberately tiny: a handful of numbers and a couple of names. It is built
 * from the same snapshot the mod already sends, so nothing new has to be
 * collected every tick.</p>
 */
public record WorldFacts(
        String playerName,
        String biome,
        boolean night,
        float health,
        int food,
        double spawnDistance,
        int humansNear,
        String activity,
        List<String> knownPlaces,
        List<String> inventory) {

    public WorldFacts {
        if (biome == null) {
            biome = "";
        }
        if (activity == null) {
            activity = "";
        }
        if (knownPlaces == null) {
            knownPlaces = List.of();
        }
        if (inventory == null) {
            inventory = List.of();
        }
        if (playerName == null) {
            playerName = "";
        }
    }

    public static WorldFacts empty() {
        return new WorldFacts("", "", false, 20.0F, 20, 0.0D, 0, "", List.of(), List.of());
    }

    public WorldFacts withSignals(String player, boolean isNight, float hp, int foodLevel,
                                  double distance, int humans, String currentActivity) {
        return new WorldFacts(player, this.biome, isNight, hp, foodLevel, distance, humans,
                currentActivity, this.knownPlaces, this.inventory);
    }

    public boolean hungry() {
        return this.food <= 6;
    }

    public boolean hurt() {
        return this.health < 8.0F;
    }

    public boolean farFromSpawn() {
        return this.spawnDistance > 220.0D;
    }

    public boolean alone() {
        return this.humansNear <= 0;
    }

    /** Best-effort read of the JSON snapshot the mod already produces. */
    public static WorldFacts fromSnapshot(String json, WorldFacts fallback) {
        WorldFacts base = fallback == null ? empty() : fallback;
        if (json == null || json.isBlank()) {
            return base;
        }
        try {
            String object = covellite.agent.social.Interpretation.extractObject(json);
            if (object == null) {
                return base;
            }
            Map<String, Object> map = Json.parseObject(object);
            float health = (float) number(map, base.health(), "health", "hp", "vida");
            int food = (int) number(map, base.food(), "food", "food_level", "comida");
            boolean night = bool(map, base.night(), "night", "is_night", "noche");
            String biome = text(map, base.biome(), "biome", "bioma");
            List<String> places = strings(map, "places", "known_places", "lugares");
            List<String> inventory = strings(map, "inventory", "items", "inventario");
            return new WorldFacts(base.playerName(), biome, night, health, food,
                    base.spawnDistance(), base.humansNear(), base.activity(),
                    places.isEmpty() ? base.knownPlaces() : places,
                    inventory.isEmpty() ? base.inventory() : inventory);
        } catch (RuntimeException e) {
            return base;
        }
    }

    private static double number(Map<String, Object> map, double fallback, String... keys) {
        for (String key : keys) {
            if (map.get(key) instanceof Number value) {
                return value.doubleValue();
            }
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                for (String key : keys) {
                    if (typed.get(key) instanceof Number value) {
                        return value.doubleValue();
                    }
                }
            }
        }
        return fallback;
    }

    private static boolean bool(Map<String, Object> map, boolean fallback, String... keys) {
        for (String key : keys) {
            if (map.get(key) instanceof Boolean value) {
                return value;
            }
        }
        return fallback;
    }

    private static String text(Map<String, Object> map, String fallback, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String string && !string.isBlank()) {
                return string;
            }
        }
        return fallback;
    }

    private static List<String> strings(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(String.valueOf(item));
                }
                return out;
            }
        }
        return List.of();
    }
}
