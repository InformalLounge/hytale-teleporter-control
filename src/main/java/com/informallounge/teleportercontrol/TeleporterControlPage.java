package com.informallounge.teleportercontrol;

import com.hypixel.hytale.builtin.adventure.teleporter.component.Teleporter;
import com.hypixel.hytale.builtin.adventure.teleporter.system.CreateWarpWhenTeleporterPlacedSystem;
import com.hypixel.hytale.builtin.adventure.teleporter.system.TurnOffTeleportersSystem;
import com.hypixel.hytale.builtin.adventure.teleporter.util.CannedWarpNames;
import com.hypixel.hytale.builtin.teleport.TeleportPlugin;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replaces the vanilla teleporter menu. Anyone with access picks a destination from every
 * teleporter they can use (no unlocking, any world). The owner, or an admin, can also rename it,
 * make it private, and share it with specific players.
 */
public class TeleporterControlPage extends InteractiveCustomUIPage<TeleporterControlPage.PageEventData> {

    private static final String PAGE_UI = "Pages/TeleporterControl.ui";
    private static final String SHARE_ROW_UI = "Pages/TeleporterControlShareRow.ui";
    private static final String PUBLIC = "public";
    private static final String PRIVATE = "private";
    /** Height of the tallest tab (Permissions with the shared list showing), used for every tab. */
    private static final int PAGES_HEIGHT = 256;

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    @Nullable
    private String key;
    @Nullable
    private AccessStore.Access access;
    private boolean canManage;
    private boolean canUse;
    private boolean confirmingRelinquish;
    @Nonnull
    private Tab tab = Tab.DESTINATION;

    // Edits to access are held here until Save, like the name and destination.
    private boolean pendingPrivate;
    @Nonnull
    private final Map<String, String> pendingShared = new LinkedHashMap<>();

    public TeleporterControlPage(@Nonnull PlayerRef playerRef, @Nonnull Ref<ChunkStore> blockRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEventData.CODEC);
        this.blockRef = blockRef;
    }

    private void loadAccess() {
        BlockModule.BlockStateInfo blockState = this.blockRef.getStore().getComponent(this.blockRef, BlockModule.BlockStateInfo.getComponentType());
        this.key = blockState == null ? null : TeleporterBlocks.keyOf(this.blockRef.getStore(), blockState);
        TeleporterControlPlugin plugin = TeleporterControlPlugin.get();
        UUID viewer = this.playerRef.getUuid();
        boolean admin = plugin.isAdmin(viewer);
        AccessStore accessStore = plugin.getAccessStore();
        this.access = accessStore.get(this.key);
        if (this.key != null && AccessStore.needsBackfill(this.access)) {
            // Placed before this plugin (or edited by an admin while unowned): the game still remembers
            // who placed it. Ones with no recorded placer (e.g. pasted with builder tools) stay unowned.
            PlacedByInteractionComponent placedBy = this.blockRef.getStore().getComponent(this.blockRef, PlacedByInteractionComponent.getComponentType());
            UUID placer = placedBy == null ? null : placedBy.getWhoPlacedUuid();
            if (placer != null) {
                this.access = accessStore.backfillOwner(this.key, placer, this.nameFor(placer.toString()));
            }
        } else if (this.key != null && this.access.owner == null && this.access.claimable && AccessStore.canUse(this.access, viewer, admin)) {
            this.access = accessStore.claimIfGivenUp(this.key, viewer, this.playerRef.getUsername());
        }
        this.canUse = AccessStore.canUse(this.access, viewer, admin);
        this.canManage = AccessStore.canManage(this.access, viewer, admin);
        this.pendingPrivate = this.access != null && this.access.isPrivate;
        this.pendingShared.clear();
        if (this.access != null) {
            this.pendingShared.putAll(this.access.shared);
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append(PAGE_UI);
        Teleporter teleporter = this.blockRef.getStore().getComponent(this.blockRef, Teleporter.getComponentType());
        this.loadAccess();
        if (teleporter == null || this.key == null) {
            commands.set("#ErrorScreen.Visible", true);
            commands.set("#Settings.Visible", false);
            commands.set("#Buttons.Visible", false);
            return;
        }

        String ownerName = this.access == null || this.access.owner == null
            ? null
            : Objects.requireNonNullElse(this.access.ownerName, "unknown player");
        commands.set("#OwnerLabel.Text", ownerName == null ? "No owner (public)" : "Owner: " + ownerName);

        if (!this.canUse) {
            if (ownerName != null) {
                commands.set("#LockedLabel.Text", "This teleporter is private. It belongs to " + ownerName + ".");
            }
            commands.set("#LockedScreen.Visible", true);
            commands.set("#Settings.Visible", false);
            commands.set("#Buttons.Visible", false);
            return;
        }

        String worldName = store.getExternalData().getWorld().getName();
        commands.set("#WarpDropdown.Entries", this.destinationEntries(teleporter, worldName));
        commands.set("#WarpDropdown.Value", teleporter.getWarp() != null ? teleporter.getWarp() : "");

        commands.set("#NameSection.Visible", this.canManage);
        if (this.canManage) {
            String language = this.playerRef.getLanguage();
            String cannedName;
            Message placeholder = teleporter.hasOwnedWarp() && !teleporter.isCustomName()
                ? Message.translation(teleporter.getOwnedWarp())
                : (cannedName = CannedWarpNames.generateCannedWarpNameKey(this.blockRef, language)) == null
                    ? Message.translation("server.customUI.teleporter.warpName")
                    : Message.translation(cannedName);
            commands.set("#NewWarp.PlaceholderText", placeholder);
            commands.set("#NewWarp.Value", teleporter.isCustomName() && teleporter.getOwnedWarp() != null ? teleporter.getOwnedWarp() : "");

            List<DropdownEntryInfo> accessEntries = new ArrayList<>();
            accessEntries.add(new DropdownEntryInfo(LocalizableString.fromString("Public: anyone can use it"), PUBLIC));
            accessEntries.add(new DropdownEntryInfo(LocalizableString.fromString("Private: you and people you share with"), PRIVATE));
            commands.set("#AccessDropdown.Entries", accessEntries);
            commands.set("#AccessDropdown.Value", this.pendingPrivate ? PRIVATE : PUBLIC);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AccessDropdown",
                new EventData().append("Action", "Access").append("@Access", "#AccessDropdown.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#AddButton",
                new EventData().append("Action", "Add").append("@Player", "#PlayerDropdown.Value"), false);
            this.buildSharing(commands, events);
        }

        commands.set("#GiveUpSection.Visible", this.canGiveUp());
        if (this.canGiveUp()) {
            commands.set("#RelinquishButton.Text", this.relinquishLabel());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RelinquishButton",
                new EventData().append("Action", "Relinquish"), false);
        }

        // Players who can only use it have nothing to switch between, so they get no tabs.
        commands.set("#TabBar.Visible", this.canManage);
        if (this.canManage) {
            Anchor pagesAnchor = new Anchor();
            pagesAnchor.setHeight(Value.of(PAGES_HEIGHT));
            commands.setObject("#Pages.Anchor", pagesAnchor);
            for (Tab each : Tab.values()) {
                events.addEventBinding(CustomUIEventBindingType.Activating, "#" + each.id + "Tab",
                    new EventData().append("Action", "Tab").append("Target", each.id), false);
            }
        }
        this.showTab(commands);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
            new EventData().append("Action", "Save").append("@Warp", "#WarpDropdown.Value").append("@NewWarp", "#NewWarp.Value"));
    }

    private void buildSharing(@Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events) {
        commands.set("#SharingSection.Visible", this.pendingPrivate);
        commands.clear("#SharedList");
        if (this.pendingShared.isEmpty()) {
            commands.appendInline("#SharedList", "Label { Text: \"Not shared with anyone yet.\"; Style: (FontSize: 14, TextColor: #878e9c); }");
        }
        int i = 0;
        for (Map.Entry<String, String> entry : this.pendingShared.entrySet()) {
            String selector = "#SharedList[" + i + "]";
            commands.append("#SharedList", SHARE_ROW_UI);
            commands.set(selector + " #Name.Text", entry.getValue());
            events.addEventBinding(CustomUIEventBindingType.Activating, selector + " #RemoveButton",
                new EventData().append("Action", "Remove").append("Target", entry.getKey()), false);
            i++;
        }

        // Everyone we've seen on the server, minus the owner and people already shared with.
        Map<String, String> candidates = new LinkedHashMap<>(TeleporterControlPlugin.get().getAccessStore().knownPlayers());
        for (PlayerRef online : Universe.get().getPlayers()) {
            candidates.put(online.getUuid().toString(), online.getUsername());
        }
        if (this.access != null && this.access.owner != null) {
            candidates.remove(this.access.owner);
        }
        candidates.keySet().removeAll(this.pendingShared.keySet());
        List<Map.Entry<String, String>> sorted = new ArrayList<>(candidates.entrySet());
        sorted.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
        List<DropdownEntryInfo> playerEntries = new ArrayList<>();
        for (Map.Entry<String, String> candidate : sorted) {
            playerEntries.add(new DropdownEntryInfo(LocalizableString.fromString(candidate.getValue()), candidate.getKey()));
        }
        commands.set("#PlayerDropdown.Entries", playerEntries);
        commands.set("#PlayerDropdown.Value", "");
    }

    /** Every teleporter this viewer may use, in any world. Admins also see /warp destinations. */
    @Nonnull
    private List<DropdownEntryInfo> destinationEntries(@Nonnull Teleporter teleporter, @Nonnull String currentWorld) {
        TeleporterControlPlugin plugin = TeleporterControlPlugin.get();
        UUID viewer = this.playerRef.getUuid();
        boolean admin = plugin.isAdmin(viewer);
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(new DropdownEntryInfo(LocalizableString.fromMessageId("server.customUI.teleporter.noWarp"), ""));
        for (Warp warp : this.allowedDestinations(teleporter, viewer, admin)) {
            String label = warp.getWorld().equals(currentWorld) ? warp.getId() : warp.getId() + " (" + warp.getWorld() + ")";
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(label), warp.getId().toLowerCase(Locale.ROOT)));
        }
        return entries;
    }

    @Nonnull
    private List<Warp> allowedDestinations(@Nonnull Teleporter teleporter, @Nonnull UUID viewer, boolean admin) {
        AccessStore accessStore = TeleporterControlPlugin.get().getAccessStore();
        String ownWarp = teleporter.getOwnedWarp();
        String currentDestination = teleporter.getWarp();
        List<Warp> warps = new ArrayList<>(TeleportPlugin.get().getWarps().values());
        warps.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
        List<Warp> allowed = new ArrayList<>();
        for (Warp warp : warps) {
            if (warp.getId().equalsIgnoreCase(ownWarp)) {
                continue;
            }
            boolean isTeleporter = Teleporter.CREATOR_IDENTIFIER.equals(warp.getCreator());
            boolean visible = isTeleporter
                ? AccessStore.canUse(accessStore.get(AccessStore.key(warp)), viewer, admin)
                : admin;
            // Keep the current destination listed even if the viewer can't see it, so saving doesn't clear it.
            if (visible || warp.getId().equalsIgnoreCase(currentDestination)) {
                allowed.add(warp);
            }
        }
        return allowed;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageEventData data) {
        if (data.action == null) {
            return;
        }
        switch (data.action) {
            case "Access" -> {
                if (!this.canManage) return;
                this.pendingPrivate = PRIVATE.equals(data.access);
                UICommandBuilder commands = new UICommandBuilder();
                commands.set("#SharingSection.Visible", this.pendingPrivate);
                this.sendUpdate(commands);
            }
            case "Add" -> {
                if (!this.canManage || data.player == null || data.player.isEmpty()) return;
                String name = this.nameFor(data.player);
                if (name == null) return;
                this.pendingShared.put(data.player, name);
                this.refreshSharing();
            }
            case "Remove" -> {
                if (!this.canManage || data.target == null) return;
                this.pendingShared.remove(data.target);
                this.refreshSharing();
            }
            case "Tab" -> {
                Tab target = Tab.byId(data.target);
                if (target == null || !this.canManage) return;
                this.tab = target;
                this.confirmingRelinquish = false;
                UICommandBuilder commands = new UICommandBuilder();
                commands.set("#RelinquishButton.Text", this.relinquishLabel());
                this.showTab(commands);
                this.sendUpdate(commands);
            }
            case "Relinquish" -> this.relinquish();
            case "Save" -> this.save(ref, store, data);
            default -> {
            }
        }
    }

    /** Only an owned teleporter has an owner to give up. */
    private boolean canGiveUp() {
        return this.canManage && this.access != null && this.access.owner != null;
    }

    /**
     * Shows the open tab's page and highlights its button. Edits on the other tabs are kept, and
     * Save saves them all; nothing on the Ownership tab needs saving, so Save is disabled there
     * (not hidden, so the window keeps its size).
     */
    private void showTab(@Nonnull UICommandBuilder commands) {
        for (Tab each : Tab.values()) {
            boolean open = each == this.tab;
            commands.set("#" + each.id + "Page.Visible", open);
            commands.set("#" + each.id + "TabActive.Visible", open);
            commands.set("#" + each.id + "Tab.Visible", !open);
        }
        commands.set("#SaveButton.Disabled", this.tab == Tab.OWNERSHIP);
    }

    private boolean isOwnedByViewer() {
        return this.access != null && this.playerRef.getUuid().toString().equals(this.access.owner);
    }

    @Nonnull
    private String relinquishLabel() {
        if (this.confirmingRelinquish) {
            return "Click again to confirm";
        }
        return this.isOwnedByViewer() ? "Give up ownership" : "Remove owner";
    }

    /** First click asks for confirmation; the second leaves it public and unowned, for the next player to claim. */
    private void relinquish() {
        if (!this.canManage || this.key == null || this.access == null || this.access.owner == null) return;
        if (!this.confirmingRelinquish) {
            this.confirmingRelinquish = true;
            UICommandBuilder commands = new UICommandBuilder();
            commands.set("#RelinquishButton.Text", this.relinquishLabel());
            this.sendUpdate(commands);
            return;
        }
        boolean ownTeleporter = this.isOwnedByViewer();
        // Only give it up if the owner hasn't changed while the menu was open.
        if (TeleporterControlPlugin.get().getAccessStore().giveUp(this.key, this.access.owner)) {
            this.playerRef.sendMessage(Message.raw(ownTeleporter
                ? "You gave up ownership of this teleporter. The next player to open it will own it."
                : "Removed this teleporter's owner. The next player to open it will own it."));
        }
        this.close();
    }

    private void refreshSharing() {
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        this.buildSharing(commands, events);
        this.sendUpdate(commands, events, false);
    }

    @Nullable
    private String nameFor(@Nonnull String uuid) {
        try {
            PlayerRef online = Universe.get().getPlayer(UUID.fromString(uuid));
            if (online != null) {
                return online.getUsername();
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return TeleporterControlPlugin.get().getAccessStore().knownPlayers().get(uuid);
    }

    private void save(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageEventData data) {
        BlockModule.BlockStateInfo blockState = this.blockRef.getStore().getComponent(this.blockRef, BlockModule.BlockStateInfo.getComponentType());
        Teleporter teleporter = this.blockRef.getStore().getComponent(this.blockRef, Teleporter.getComponentType());
        if (blockState == null || teleporter == null || !blockState.getSectionRef().isValid()) {
            this.close();
            return;
        }
        // Re-check in case access changed while the menu was open.
        this.loadAccessKeepingEdits();
        if (!this.canUse || this.key == null) {
            this.close();
            return;
        }

        UUID viewer = this.playerRef.getUuid();
        boolean admin = TeleporterControlPlugin.get().isAdmin(viewer);
        String destination = data.warp == null || data.warp.isEmpty() ? null : data.warp.toLowerCase(Locale.ROOT);
        if (destination != null && this.allowedDestinations(teleporter, viewer, admin).stream().noneMatch(w -> w.getId().equalsIgnoreCase(destination))) {
            this.showError("You don't have access to that destination.");
            return;
        }

        String oldOwnedWarp = teleporter.getOwnedWarp();
        String newName = oldOwnedWarp;
        boolean customName = teleporter.isCustomName();
        if (this.canManage && data.warpName != null && !data.warpName.isEmpty()) {
            newName = data.warpName;
            customName = true;
        } else if (oldOwnedWarp == null) {
            newName = CannedWarpNames.generateCannedWarpName(this.blockRef, this.playerRef.getLanguage());
            customName = false;
        }
        if (newName == null) {
            this.showError("Give this teleporter a name.");
            return;
        }
        if (!newName.equalsIgnoreCase(oldOwnedWarp) && TeleportPlugin.get().getWarps().containsKey(newName.toLowerCase(Locale.ROOT))) {
            this.showError("A teleporter with that name already exists.");
            return;
        }

        this.close();

        if (!newName.equals(oldOwnedWarp)) {
            if (oldOwnedWarp != null && !oldOwnedWarp.isEmpty()) {
                TeleportPlugin.get().removeWarp(oldOwnedWarp);
            }
            CreateWarpWhenTeleporterPlacedSystem.createWarp(blockState.getSectionRef().getStore(), blockState.getSectionRef(), blockState, newName);
            teleporter.setOwnedWarp(newName);
            teleporter.setIsCustomName(customName);
        }

        String destinationBefore = teleporter.getWarp();
        teleporter.setWorldUuid(null);
        teleporter.setTransform(null);
        teleporter.setWarp(destination);
        blockState.markNeedsSaving();

        if (this.canManage) {
            AccessStore.Access updated = this.access != null ? this.access : new AccessStore.Access();
            updated.isPrivate = this.pendingPrivate;
            updated.shared = new LinkedHashMap<>(this.pendingShared);
            TeleporterControlPlugin.get().getAccessStore().put(this.key, updated);
        }

        if (!newName.equals(oldOwnedWarp) || !Objects.equals(destinationBefore, destination)) {
            World world = store.getExternalData().getWorld();
            TurnOffTeleportersSystem.updatePortalBlocksInWorld(world);
        }
    }

    private void loadAccessKeepingEdits() {
        boolean editedPrivate = this.pendingPrivate;
        Map<String, String> editedShared = new LinkedHashMap<>(this.pendingShared);
        this.loadAccess();
        this.pendingPrivate = editedPrivate;
        this.pendingShared.clear();
        this.pendingShared.putAll(editedShared);
    }

    private void showError(@Nonnull String text) {
        UICommandBuilder commands = new UICommandBuilder();
        commands.set("#ErrorLabel.Text", text);
        commands.set("#ErrorLabel.Visible", true);
        // Save's errors are all about the name or destination, so show the tab they're on.
        this.tab = Tab.DESTINATION;
        this.showTab(commands);
        this.sendUpdate(commands);
    }

    private enum Tab {
        DESTINATION("Destination"),
        PERMISSIONS("Permissions"),
        OWNERSHIP("Ownership");

        /** Prefix of the tab's element ids in the .ui file, e.g. #DestinationTab and #DestinationPage. */
        final String id;

        Tab(String id) {
            this.id = id;
        }

        @Nullable
        static Tab byId(@Nullable String id) {
            for (Tab tab : values()) {
                if (tab.id.equals(id)) {
                    return tab;
                }
            }
            return null;
        }
    }

    public static class PageEventData {
        @Nonnull
        public static final BuilderCodec<PageEventData> CODEC = BuilderCodec.builder(PageEventData.class, PageEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Target", Codec.STRING), (d, v) -> d.target = v, d -> d.target).add()
            .append(new KeyedCodec<>("@Player", Codec.STRING), (d, v) -> d.player = v, d -> d.player).add()
            .append(new KeyedCodec<>("@Access", Codec.STRING), (d, v) -> d.access = v, d -> d.access).add()
            .append(new KeyedCodec<>("@Warp", Codec.STRING), (d, v) -> d.warp = v, d -> d.warp).add()
            .append(new KeyedCodec<>("@NewWarp", Codec.STRING), (d, v) -> d.warpName = v, d -> d.warpName).add()
            .build();

        @Nullable
        public String action;
        @Nullable
        public String target;
        @Nullable
        public String player;
        @Nullable
        public String access;
        @Nullable
        public String warp;
        @Nullable
        public String warpName;
    }
}
