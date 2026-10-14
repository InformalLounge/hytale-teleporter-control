package com.informallounge.teleportercontrol;

import com.hypixel.hytale.builtin.adventure.teleporter.component.Teleporter;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Records who placed each teleporter, and forgets teleporters when they are broken. */
final class OwnershipSystems {

    private OwnershipSystems() {
    }

    static final class Placed extends RefChangeSystem<ChunkStore, PlacedByInteractionComponent> {
        private final AccessStore store;
        private final Query<ChunkStore> query = Query.and(
            PlacedByInteractionComponent.getComponentType(), Teleporter.getComponentType(), BlockModule.BlockStateInfo.getComponentType());

        Placed(@Nonnull AccessStore store) {
            this.store = store;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull PlacedByInteractionComponent placedBy, @Nonnull Store<ChunkStore> chunkStore, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            UUID placer = placedBy.getWhoPlacedUuid();
            if (placer == null) {
                return;
            }
            BlockModule.BlockStateInfo blockState = commandBuffer.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            if (blockState == null) {
                return;
            }
            String key = TeleporterBlocks.keyOf(commandBuffer, blockState);
            if (key == null) {
                return;
            }
            // Only fills in a missing record, so this can't undo a later give-up if it ever runs again.
            this.store.backfillOwner(key, placer, this.nameOf(chunkStore, placer));
        }

        @Nullable
        private String nameOf(@Nonnull Store<ChunkStore> chunkStore, @Nonnull UUID uuid) {
            EntityStore entityStore = chunkStore.getExternalData().getWorld().getEntityStore();
            Ref<EntityStore> playerRef = entityStore.getRefFromUUID(uuid);
            if (playerRef == null || !playerRef.isValid()) {
                return this.store.knownPlayers().get(uuid.toString());
            }
            PlayerRef player = entityStore.getStore().getComponent(playerRef, PlayerRef.getComponentType());
            return player == null ? null : player.getUsername();
        }

        @Override
        public void onComponentSet(@Nonnull Ref<ChunkStore> ref, @Nullable PlacedByInteractionComponent oldComponent, @Nonnull PlacedByInteractionComponent newComponent, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<ChunkStore> ref, @Nonnull PlacedByInteractionComponent component, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        }

        @Override
        @Nonnull
        public ComponentType<ChunkStore, PlacedByInteractionComponent> componentType() {
            return PlacedByInteractionComponent.getComponentType();
        }

        @Override
        @Nonnull
        public Query<ChunkStore> getQuery() {
            return this.query;
        }
    }

    static final class Removed extends RefSystem<ChunkStore> {
        private final AccessStore store;
        private final Query<ChunkStore> query = Query.and(Teleporter.getComponentType(), BlockModule.BlockStateInfo.getComponentType());

        Removed(@Nonnull AccessStore store) {
            this.store = store;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            if (reason != RemoveReason.REMOVE) {
                return;
            }
            BlockModule.BlockStateInfo blockState = commandBuffer.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            if (blockState == null) {
                return;
            }
            String key = TeleporterBlocks.keyOf(commandBuffer, blockState);
            if (key != null) {
                this.store.remove(key);
            }
        }

        @Override
        @Nonnull
        public Query<ChunkStore> getQuery() {
            return this.query;
        }
    }
}
