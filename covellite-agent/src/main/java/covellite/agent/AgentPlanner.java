package covellite.agent;

import covellite.core.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a natural-language goal into an ordered list of concrete steps.
 *
 * <p>Runs as a separate, tool-less model call so planning never competes with
 * the action loop for the turn budget. The result is stored in memory as a
 * {@code GOAL} entry, which is what makes objectives survive a restart.</p>
 */
public final class AgentPlanner {

    private final AgentSettings settings;
    private final AgentProvider provider;
    private final AgentMemory memory;

    public AgentPlanner(AgentSettings settings, AgentProvider provider, AgentMemory memory) {
        this.settings = settings;
        this.provider = provider;
        this.memory = memory;
    }

    public List<String> decompose(String goal) throws ProviderException {
        String trimmed = goal == null ? "" : goal.trim();
        if (trimmed.isEmpty()) {
            throw new ProviderException("empty goal");
        }
        String system = "Sos el planificador de Cove, un jugador artificial de Minecraft. "
                + "Devolve SOLO JSON valido con la forma "
                + "{\"steps\":[\"paso concreto 1\",\"paso concreto 2\"]}. "
                + "Cada paso debe ser ejecutable con las herramientas del juego, en orden, "
                + "sin explicaciones y sin markdown. Maximo 8 pasos.";
        AgentProvider.Request request = new AgentProvider.Request(
                this.settings.model(), system,
                List.of(AgentProvider.Message.user("Objetivo: " + trimmed)),
                List.of(), Math.min(this.settings.maxTokens(), 600), 0.2);
        AgentProvider.Response response = this.provider.complete(request);

        List<String> steps = parseSteps(response.text());
        StringBuilder stored = new StringBuilder(trimmed);
        for (int i = 0; i < steps.size(); i++) {
            stored.append("\n  ").append(i + 1).append(". ").append(steps.get(i));
        }
        this.memory.remember(AgentMemory.Kind.GOAL, stored.toString(), List.of("active"));
        return steps;
    }

    /** Accepts the requested JSON shape and tolerates a plain numbered list. */
    static List<String> parseSteps(String text) {
        List<String> steps = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return steps;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                Map<String, Object> json = Json.parseObject(text.substring(start, end + 1));
                if (json.get("steps") instanceof List<?> raw) {
                    for (Object item : raw) {
                        String step = String.valueOf(item).trim();
                        if (!step.isEmpty()) {
                            steps.add(step);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall through to the line parser.
            }
        }
        if (steps.isEmpty()) {
            for (String line : text.split("\\r?\\n")) {
                String cleaned = line.trim().replaceFirst("^[-*\\d.\\s]+", "");
                if (!cleaned.isEmpty() && !cleaned.startsWith("{") && !cleaned.startsWith("}")) {
                    steps.add(cleaned);
                }
            }
        }
        return steps.size() > 8 ? new ArrayList<>(steps.subList(0, 8)) : steps;
    }
}
