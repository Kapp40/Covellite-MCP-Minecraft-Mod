package covellite.agent.reflex;

import java.util.List;

/**
 * The ten layer-0 reflexes: the ones that keep the companion alive before any
 * model call happens.
 *
 * <p>Priorities run 100 → 91, all above every task, so a survival reflex always
 * preempts a walk, a chore or a chat order. Actions are ids; the mod maps them
 * to real inputs, and any of them may interrupt whatever is running.</p>
 */
public final class SurvivalReflexes {

    private SurvivalReflexes() {
    }

    public static List<Reflex> layer0() {
        return List.of(
                new Reflex("lava_flee", 100, 20, "back_off",
                        inputs -> inputs.lavaAdjacent() || inputs.lavaAhead(),
                        "Lava pegada o adelante: retroceder"),
                new Reflex("lava_below", 99, 40, "stop_mining",
                        ReflexInputs::lavaBelowTarget,
                        "El bloque apuntado tiene lava debajo: no picar"),
                new Reflex("lava_above", 98, 40, "stop_mining",
                        ReflexInputs::lavaAboveTarget,
                        "Lava sobre el bloque apuntado: no cavar hacia arriba"),
                new Reflex("ledge_brake", 97, 20, "brake",
                        inputs -> !inputs.solidAhead() && inputs.waterBelowBlocks() > 3,
                        "Borde sin suelo: frenar el sprint"),
                new Reflex("mlg_water", 96, 100, "water_drop",
                        inputs -> inputs.fallDistance() >= 3.0D
                                && inputs.hasWaterBucket()
                                && inputs.waterBelowBlocks() <= 2,
                        "Caida larga con agua cerca: cubo para amortiguar"),
                new Reflex("drown", 95, 40, "swim_up",
                        inputs -> inputs.airTicks() < 80,
                        "Aire bajo: subir a la superficie"),
                new Reflex("levitation", 94, 20, "hold_still",
                        ReflexInputs::levitating,
                        "Levitacion: quedarse quieto"),
                new Reflex("freezing", 93, 30, "back_off",
                        ReflexInputs::freezing,
                        "Congelamiento: salir del polvo de nieve"),
                new Reflex("cobweb", 92, 40, "break_web",
                        ReflexInputs::inCobweb,
                        "Atrapado en tela de arana: romperla"),
                new Reflex("tnt_fuse", 91, 30, "flee_tnt",
                        inputs -> inputs.primedTntDistance() <= 8,
                        "TNT encendida cerca: correr perpendicular"));
    }
}
