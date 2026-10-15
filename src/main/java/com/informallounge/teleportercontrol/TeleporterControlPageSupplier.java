package com.informallounge.teleportercontrol;

import com.hypixel.hytale.builtin.adventure.teleporter.component.Teleporter;
import com.hypixel.hytale.builtin.adventure.teleporter.page.TeleporterSettingsPageSupplier;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens {@link TeleporterControlPage} for the teleporter block being used. Mirrors the vanilla supplier, and
 * extends it so the vanilla item's page settings ("Create", "Mode") still decode. They aren't used.
 */
public class TeleporterControlPageSupplier extends TeleporterSettingsPageSupplier {

    @Nonnull
    public static final BuilderCodec<TeleporterControlPageSupplier> CODEC =
        BuilderCodec.builder(TeleporterControlPageSupplier.class, TeleporterControlPageSupplier::new, TeleporterSettingsPageSupplier.CODEC).build();

    @Override
    @Nullable
    public CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull PlayerRef playerRef, @Nonnull InteractionContext context) {
        BlockPosition targetBlock = context.getTargetBlock();
        if (targetBlock == null) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkComponentStore = chunkStore.getStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(targetBlock.x, targetBlock.y, targetBlock.z);
        BlockComponentSection blockComponentSection = sectionRef == null || !sectionRef.isValid()
            ? null
            : chunkComponentStore.getComponent(sectionRef, BlockComponentSection.getComponentType());
        if (blockComponentSection == null) {
            return null;
        }
        int blockIndex = ChunkUtil.indexBlock(targetBlock.x, targetBlock.y, targetBlock.z);
        Ref<ChunkStore> blockRef = blockComponentSection.getBlockReference(blockIndex);
        if (blockRef == null || !blockRef.isValid()) {
            Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
            holder.putComponent(BlockModule.BlockStateInfo.getComponentType(), new BlockModule.BlockStateInfo(blockIndex, sectionRef));
            holder.ensureComponent(Teleporter.getComponentType());
            blockRef = chunkComponentStore.addEntity(holder, AddReason.SPAWN);
        }
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        return new TeleporterControlPage(playerRef, blockRef);
    }
}
