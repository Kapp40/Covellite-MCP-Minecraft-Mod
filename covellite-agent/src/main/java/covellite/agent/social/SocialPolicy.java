package covellite.agent.social;

/**
 * Decides whether Cove is allowed to spend a model call on thinking.
 *
 * <p>This is deliberately dumb, deterministic Java: the interesting judgement
 * (what to say or do) belongs to the model. The policy only protects the player
 * from spam and protects the wallet from a model call every tick.</p>
 */
public final class SocialPolicy {

    /** Why the mind woke up, or why it stayed quiet. */
    public record Verdict(boolean think, String trigger, String reason) {
        public static Verdict no(String reason) {
            return new Verdict(false, "", reason);
        }

        public static Verdict yes(String trigger) {
            return new Verdict(true, trigger, trigger);
        }
    }

    private final SocialSettings settings;

    public SocialPolicy(SocialSettings settings) {
        this.settings = settings == null ? SocialSettings.defaults() : settings;
    }

    /**
     * @param lastThinkAt when the mind last deliberated, so periodic thinking
     *                    does not become a per-tick loop
     */
    public Verdict evaluate(SocialSignals signals, SocialState state, Identity identity,
                            double chattiness, long lastThinkAt) {
        if (!this.settings.enabled()) {
            return Verdict.no("deshabilitado");
        }
        long now = signals.nowMs();
        if (signals.lonely()) {
            return Verdict.no("sin jugadores");
        }
        if (signals.busy()) {
            return Verdict.no("ocupado");
        }

        SocialState.PendingQuestion pending = state.pending();
        if (pending != null) {
            long age = now - pending.askedAt();
            if (age < this.settings.questionTtlMs()) {
                // Waiting for the answer is itself a reason to stay quiet: Cove
                // must not bury its own question under new chatter.
                return Verdict.no("esperando_respuesta");
            }
            if (pending.reminders() == 0) {
                return Verdict.yes("pregunta_sin_responder");
            }
            if (age < 2L * this.settings.questionTtlMs()) {
                return Verdict.no("esperando_respuesta");
            }
            return Verdict.yes("pregunta_vencida");
        }

        long gap = this.settings.gapFor(chattiness);
        long sinceSpoke = now - Math.max(state.lastSpokeAt(), lastThinkAt);
        if (sinceSpoke < gap) {
            return Verdict.no("acaba_de_hablar");
        }
        if (state.initiationsInWindow(now) >= this.settings.maxInitiations()) {
            return Verdict.no("demasiadas_iniciativas");
        }
        if (now - lastThinkAt < this.settings.thinkIntervalMs()) {
            return Verdict.no("pensado_hace_poco");
        }

        // Concrete reasons first: the model gets a motive instead of "say
        // something", which is what produces filler.
        if (signals.taskFinished()) {
            return Verdict.yes("tarea_terminada");
        }
        if (signals.hurt()) {
            return Verdict.yes("herida");
        }
        if (signals.hungry()) {
            return Verdict.yes("hambre");
        }
        if (signals.farFromSpawn()) {
            return Verdict.yes("lejos_de_casa");
        }
        if (signals.separated()) {
            return Verdict.yes("separado_del_jugador");
        }
        if (signals.night()) {
            return Verdict.yes("noche");
        }
        if (signals.msSinceHumanChat() > 90_000L) {
            return Verdict.yes("silencio_prolongado");
        }
        if (identity.trait("iniciativa") > 0.6D) {
            return Verdict.yes("iniciativa");
        }
        return Verdict.no("nada_relevante");
    }
}
