package com.covelliteai.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Prototype-only commands used to exercise the companion from the game chat.
 * They are intentionally not exposed through MCP yet.
 */
public final class CompanionCommands {

    private CompanionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CompanionManager manager, AgentBridge agent, BridgeApi api) {
        register(dispatcher, manager);
        if (agent != null) {
            registerAgent(dispatcher, agent, api);
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CompanionManager manager) {
        dispatcher.register(Commands.literal("covellite")
                .then(Commands.literal("spawn")
                        .executes(ctx -> {
                            boolean spawned = manager.spawn(ctx.getSource().getServer());
                            return feedback(ctx.getSource(), "spawn=" + spawned);
                        }))
                .then(Commands.literal("despawn")
                        .executes(ctx -> {
                            manager.despawn(ctx.getSource().getServer());
                            return feedback(ctx.getSource(), "despawned");
                        }))
                .then(Commands.literal("tphere")
                        .executes(ctx -> {
                            try {
                                boolean moved = manager.teleportTo(ctx.getSource().getPlayerOrException());
                                return feedback(ctx.getSource(), "tphere=" + moved);
                            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                                return failure(ctx.getSource(), "player_only_command");
                            }
                        }))
                .then(Commands.literal("state")
                        .executes(ctx -> feedback(ctx.getSource(), manager.state().toString())))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            manager.controller().stop();
                            return feedback(ctx.getSource(), "stopped");
                        }))
                .then(Commands.literal("jump")
                        .executes(ctx -> {
                            if (manager.player() == null) {
                                return failure(ctx.getSource(), "companion_not_present");
                            }
                            manager.controller().jump();
                            return feedback(ctx.getSource(), "jump");
                        }))
                .then(Commands.literal("look")
                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                                        .executes(ctx -> {
                                            if (manager.player() == null) {
                                                return failure(ctx.getSource(), "companion_not_present");
                                            }
                                            manager.controller().look(
                                                    (float) DoubleArgumentType.getDouble(ctx, "yaw"),
                                                    (float) DoubleArgumentType.getDouble(ctx, "pitch"));
                                            return feedback(ctx.getSource(), "look");
                                        }))))
                .then(Commands.literal("move")
                        .then(Commands.argument("forward", DoubleArgumentType.doubleArg(-1, 1))
                                .then(Commands.argument("strafe", DoubleArgumentType.doubleArg(-1, 1))
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1200))
                                                .executes(ctx -> move(ctx.getSource(), manager, ctx, false))
                                                .then(Commands.argument("sprint", BoolArgumentType.bool())
                                                        .executes(ctx -> move(ctx.getSource(), manager, ctx, true)))))))
                .then(Commands.literal("sneak")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    if (manager.player() == null) {
                                        return failure(ctx.getSource(), "companion_not_present");
                                    }
                                    manager.controller().setSneak(BoolArgumentType.getBool(ctx, "enabled"));
                                    return feedback(ctx.getSource(), "sneak=" + BoolArgumentType.getBool(ctx, "enabled"));
                                }))));
    }

    /**
     * {@code /cove ai ...}: manage the in-process AI runtime from the game chat.
     * This is the first in-game surface of the agent; it works without editing
     * any file.
     */
    private static void registerAgent(CommandDispatcher<CommandSourceStack> dispatcher, AgentBridge agent,
                                      BridgeApi api) {
        dispatcher.register(Commands.literal("cove")
                .then(Commands.literal("ai")
                        .then(Commands.literal("status")
                                .executes(ctx -> feedback(ctx.getSource(), agent.status().toString())))
                        .then(Commands.literal("ask")
                                .then(Commands.argument("texto", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String text = StringArgumentType.getString(ctx, "texto");
                                            if (agent.ask(text)) {
                                                return feedback(ctx.getSource(), "enviado al agente");
                                            }
                                            return failure(ctx.getSource(),
                                                    "agente deshabilitado o proveedor no configurado");
                                        })))
                        .then(Commands.literal("plan")
                                .then(Commands.argument("objetivo", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String goal = StringArgumentType.getString(ctx, "objetivo");
                                            if (agent.plan(goal)) {
                                                return feedback(ctx.getSource(), "planificando: " + goal);
                                            }
                                            return failure(ctx.getSource(),
                                                    "agente deshabilitado o proveedor no configurado");
                                        })))
                        .then(Commands.literal("remember")
                                .then(Commands.argument("texto", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String text = StringArgumentType.getString(ctx, "texto");
                                            return agent.remember(text)
                                                    ? feedback(ctx.getSource(), "recordado")
                                                    : failure(ctx.getSource(), "agente deshabilitado");
                                        })))
                        .then(Commands.literal("memory")
                                .then(Commands.argument("tipo", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String kind = StringArgumentType.getString(ctx, "tipo");
                                            java.util.List<String> lines = agent.recentMemory(kind, 5);
                                            return feedback(ctx.getSource(), lines.isEmpty()
                                                    ? "sin recuerdos de tipo " + kind
                                                    : String.join(" | ", lines));
                                        })))
                        .then(Commands.literal("panel")
                                .executes(ctx -> {
                                    java.util.List<String> lines = new java.util.ArrayList<>(agent.panel());
                                    if (api != null) {
                                        lines.addAll(api.panelLines());
                                    }
                                    for (String line : lines) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[CoveMCP] " + line), false);
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("personality")
                                .then(Commands.argument("nombre", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "nombre");
                                            if (agent.updateSetting("agentPersonality", name)) {
                                                return feedback(ctx.getSource(), "personalidad=" + name);
                                            }
                                            return failure(ctx.getSource(),
                                                    "personalidad desconocida: usa companion, curioso, cauteloso, bromista o callado");
                                        })))
                        .then(Commands.literal("language")
                                .then(Commands.argument("codigo", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String code = StringArgumentType.getString(ctx, "codigo");
                                            if (agent.updateSetting("agentLanguage", code)) {
                                                return feedback(ctx.getSource(), "idioma=" + code);
                                            }
                                            return failure(ctx.getSource(), "idioma invalido");
                                        })))
                        .then(Commands.literal("goals")
                                .executes(ctx -> {
                                    java.util.List<String> goals = agent.activeGoals();
                                    return feedback(ctx.getSource(), goals.isEmpty()
                                            ? "sin objetivos activos"
                                            : String.join(" | ", goals));
                                }))
                        .then(Commands.literal("on")
                                .executes(ctx -> feedback(ctx.getSource(), "agent=" + agent.start(
                                        ctx.getSource().getServer(),
                                        ctx.getSource().getServer().getWorldData().getLevelName(),
                                        true))))
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    agent.stop();
                                    return feedback(ctx.getSource(), "agente detenido");
                                }))));
    }

    private static int move(CommandSourceStack source, CompanionManager manager,
                            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                            boolean sprintProvided) {
        if (manager.player() == null) {
            return failure(source, "companion_not_present");
        }
        double forward = DoubleArgumentType.getDouble(ctx, "forward");
        double strafe = DoubleArgumentType.getDouble(ctx, "strafe");
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        boolean sprint = sprintProvided && BoolArgumentType.getBool(ctx, "sprint");
        manager.controller().move(forward, strafe, sprint, ticks);
        return feedback(source, "move forward=" + forward + " strafe=" + strafe
                + " ticks=" + ticks + " sprint=" + sprint);
    }

    private static int feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[CoveMCP] " + message), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[CoveMCP] " + message));
        return 0;
    }
}
