package com.informallounge.teleportercontrol;

import com.hypixel.hytale.builtin.adventure.teleporter.component.Teleporter;
import com.hypixel.hytale.builtin.adventure.teleporter.interaction.server.TeleporterInteraction;
import com.hypixel.hytale.builtin.teleport.TeleportPlugin;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** The vanilla step-on teleport, but only for players allowed on both the source and destination. */
public class ControlledTeleporterInteraction extends TeleporterInteraction {

    @Nonnull
    public static final BuilderCodec<ControlledTeleporterInteraction> CODEC =
        BuilderCodec.builder(ControlledTeleporterInteraction.class, ControlledTeleporterInteraction::new, TeleporterInteraction.CODEC).build();

    private static final long DENY_MESSAGE_COOLDOWN_MS = 5000L;
    private static final Map<UUID, Long> lastDenyMessage = new ConcurrentHashMap<>();

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock, @Nonnull CooldownHandler cooldownHandler) {
        TeleporterControlPlugin plugin = TeleporterControlPlugin.get();
        AccessStore store = plugin.getAccessStore();

        AccessStore.Access source = store.get(AccessStore.key(world.getName(), targetBlock.x, targetBlock.y, targetBlock.z));
        AccessStore.Access destination = store.get(destinationKey(world, targetBlock));

        PlayerRef player = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        boolean allowed;
        if (player == null) {
            // Mobs and other entities only ride fully public links.
            allowed = AccessStore.canUse(source, new UUID(0, 0), false) && AccessStore.canUse(destination, new UUID(0, 0), false);
        } else {
            boolean admin = plugin.isAdmin(player.getUuid());
            allowed = AccessStore.canUse(source, player.getUuid(), admin) && AccessStore.canUse(destination, player.getUuid(), admin);
            if (!allowed) {
                tellDenied(player);
            }
        }

        if (!allowed) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        super.interactWithBlock(world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
    }

    @Nullable
    private static String destinationKey(@Nonnull World world, @Nonnull Vector3i targetBlock) {
        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(world, targetBlock.x, targetBlock.y, targetBlock.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Teleporter teleporter = blockRef.getStore().getComponent(blockRef, Teleporter.getComponentType());
        if (teleporter == null || teleporter.getWarp() == null) {
            return null;
        }
        Warp warp = TeleportPlugin.get().getWarps().get(teleporter.getWarp().toLowerCase(Locale.ROOT));
        if (warp == null || !Teleporter.CREATOR_IDENTIFIER.equals(warp.getCreator())) {
            // Admin-made /warp destinations aren't owned by anyone.
            return null;
        }
        return AccessStore.key(warp);
    }

    private static void tellDenied(@Nonnull PlayerRef player) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(player.getUuid());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastDenyMessage.put(player.getUuid(), now);
        player.sendMessage(Message.raw("You don't have access to this teleporter or where it leads."));
    }
}
