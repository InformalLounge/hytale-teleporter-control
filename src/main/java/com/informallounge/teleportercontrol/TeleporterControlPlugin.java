package com.informallounge.teleportercontrol;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Unlimited, ownable teleporters.
 *
 * The bundled asset pack removes the placement cap and points the Teleporter block at
 * {@link ControlledTeleporterInteraction} and {@link TeleporterControlPage} instead of the vanilla ones.
 */
public class TeleporterControlPlugin extends JavaPlugin {

    /** Can use and manage every teleporter. Operators normally have it through "*". */
    public static final String ADMIN_PERMISSION = "teleportercontrol.admin";

    private static TeleporterControlPlugin instance;
    private AccessStore accessStore;

    public TeleporterControlPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static TeleporterControlPlugin get() {
        return instance;
    }

    @Nonnull
    public AccessStore getAccessStore() {
        return this.accessStore;
    }

    public boolean isAdmin(@Nonnull UUID player) {
        return PermissionsModule.get().hasPermission(player, ADMIN_PERMISSION);
    }

    @Override
    protected void setup() {
        this.accessStore = new AccessStore(this.getDataDirectory().resolve("access.json"), this.getLogger());
        this.accessStore.load();

        this.getChunkStoreRegistry().registerSystem(new OwnershipSystems.Placed(this.accessStore));
        this.getChunkStoreRegistry().registerSystem(new OwnershipSystems.Removed(this.accessStore));

        this.getCodecRegistry(Interaction.CODEC).register("ControlledTeleporter", ControlledTeleporterInteraction.class, ControlledTeleporterInteraction.CODEC);
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register("TeleporterControl", TeleporterControlPageSupplier.class, TeleporterControlPageSupplier.CODEC);

        this.getEventRegistry().registerGlobal(PlayerConnectEvent.class, event -> {
            PlayerRef player = event.getPlayerRef();
            this.accessStore.rememberPlayer(player.getUuid(), player.getUsername());
        });
    }

    @Override
    protected void shutdown() {
        if (this.accessStore != null) {
            this.accessStore.close();
        }
    }
}
