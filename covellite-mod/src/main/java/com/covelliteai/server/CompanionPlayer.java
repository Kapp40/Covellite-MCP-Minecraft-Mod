package com.covelliteai.server;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The artificial player.
 *
 * <p>It is a normal {@link ServerPlayer}, registered in the integrated server's
 * {@code PlayerList}, so it has its own entity id, UUID, inventory, health,
 * statistics, advancements and playerdata file. It is not a NeoForge
 * {@code FakePlayer}: it ticks, takes damage, moves and is tracked by the
 * vanilla entity tracker.</p>
 */
public final class CompanionPlayer extends ServerPlayer {

    public CompanionPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Attacks with a fully charged swing.
     *
     * <p>{@code attackStrengthTicker} is protected in {@code LivingEntity}, so
     * the bridge cannot set it from outside. Charging it here makes melee
     * damage identical to a player who waited for the cooldown.</p>
     */
    public void attackWithFullStrength(Entity target) {
        this.attackStrengthTicker = (int) Math.max(this.getCurrentItemAttackStrengthDelay(), 1.0F);
        this.attack(target);
    }
}
