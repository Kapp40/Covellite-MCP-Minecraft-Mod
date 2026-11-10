package com.covelliteai.server;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
}
