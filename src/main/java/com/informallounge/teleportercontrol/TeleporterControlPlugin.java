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
 * The bundled asset pack removes the placement cap. The vanilla "Teleporter" interaction and page types are
 * re-registered to {@link ControlledTeleporterInteraction} and {@link TeleporterControlPageSupplier}, so the
 * vanilla Teleporter item uses them without the mod shipping a copy of it. Registering an existing type id
 * replaces it, and this plugin's setup runs after Hytale:Teleporter's because it depends on it.
 */
public class TeleporterControlPlugin extends JavaPlugin {

    /** Can use and manage every teleporter. Operators normally have it through "*". */
    public static final String ADMIN_PERMISSION = "teleportercontrol.admin";

    /** The interaction type and page id the vanilla Teleporter item uses. */
    private static final String VANILLA_TYPE_ID = "Teleporter";

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

        this.getCodecRegistry(Interaction.CODEC).register(VANILLA_TYPE_ID, ControlledTeleporterInteraction.class, ControlledTeleporterInteraction.CODEC);
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(VANILLA_TYPE_ID, TeleporterControlPageSupplier.class, TeleporterControlPageSupplier.CODEC);

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
