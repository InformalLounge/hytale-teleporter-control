package com.informallounge.teleportercontrol;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class TeleporterBlocks {

    private TeleporterBlocks() {
    }

    /** Access-store key for a teleporter block entity, or null if its chunk section is gone. */
    @Nullable
    static String keyOf(@Nonnull ComponentAccessor<ChunkStore> accessor, @Nonnull BlockModule.BlockStateInfo blockState) {
        Ref<ChunkStore> sectionRef = blockState.getSectionRef();
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        ChunkSection section = accessor.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) {
            return null;
        }
        int index = blockState.getIndex();
        int x = (section.getX() << 5) + ChunkUtil.xFromIndex(index);
        int y = (section.getY() << 5) + ChunkUtil.yFromIndex(index);
        int z = (section.getZ() << 5) + ChunkUtil.zFromIndex(index);
        return AccessStore.key(accessor.getExternalData().getWorld().getName(), x, y, z);
    }
}
