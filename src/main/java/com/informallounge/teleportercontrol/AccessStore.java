package com.informallounge.teleportercontrol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Who owns each teleporter and who may use it, keyed by the teleporter block's position.
 *
 * Keyed by position rather than warp name because renaming a teleporter replaces its warp.
 * Teleporters placed by a player before this plugin get that player as owner the first time their
 * menu opens. Ones with no recorded placer (for example, pasted in with builder tools) stay public
 * with no owner, and only admins manage them.
 */
public final class AccessStore {

    public static final class Access {
        @Nullable
        public String owner;
        @Nullable
        public String ownerName;
        public boolean isPrivate;
        /** Given up by its owner; the next player to open its menu becomes the owner. */
        public boolean claimable;
        /** UUID -> last known username. */
        public Map<String, String> shared = new LinkedHashMap<>();

        @Nonnull
        Access copy() {
            Access copy = new Access();
            copy.owner = this.owner;
            copy.ownerName = this.ownerName;
            copy.isPrivate = this.isPrivate;
            copy.claimable = this.claimable;
            copy.shared = new LinkedHashMap<>(this.shared);
            return copy;
        }
    }

    private static final class Data {
        Map<String, Access> teleporters = new LinkedHashMap<>();
        /** UUID -> last known username, so players can share with friends who are offline. */
        Map<String, String> players = new LinkedHashMap<>();
    }

    /** How long to wait after a change before writing, so a burst of changes becomes one write. */
    private static final long SAVE_DELAY_MS = 2000L;

    private final Path file;
    private final Path backup;
    private final HytaleLogger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TeleporterControl-Saver");
        thread.setDaemon(true);
        return thread;
    });
    /** Held while writing to disk, so the scheduled save and {@link #close} never write at once. */
    private final Object writeLock = new Object();
    private Data data = new Data();
    private boolean saveScheduled;

    public AccessStore(@Nonnull Path file, @Nonnull HytaleLogger logger) {
        this.file = file;
        this.backup = file.resolveSibling(file.getFileName() + ".bak");
        this.logger = logger;
    }

    @Nonnull
    public static String key(@Nonnull String worldName, int x, int y, int z) {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    /** Teleporter warps sit at block + (0.5, 0.65, 0.5), so flooring recovers the block. */
    @Nullable
    public static String key(@Nonnull Warp warp) {
        if (warp.getTransform() == null) {
            return null;
        }
        Vector3d pos = warp.getTransform().getPosition();
        return key(warp.getWorld(), (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }

    /**
     * Loads the file, falling back to the backup. An unreadable file is renamed aside rather than
     * left in place, so the next save can't overwrite the only copy of the records.
     */
    public synchronized void load() {
        Data loaded = this.tryLoad(this.file);
        if (loaded == null) {
            loaded = this.tryLoad(this.backup);
            if (loaded != null) {
                this.logger.at(Level.WARNING).log("Restored teleporter access records from %s", this.backup);
            }
        }
        if (loaded != null) {
            this.data = loaded;
        }
    }

    @Nullable
    private Data tryLoad(@Nonnull Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data loaded = this.gson.fromJson(reader, Data.class);
            if (loaded == null) {
                throw new IOException("file is empty");
            }
            if (loaded.teleporters == null) loaded.teleporters = new LinkedHashMap<>();
            if (loaded.players == null) loaded.players = new LinkedHashMap<>();
            return loaded;
        } catch (IOException | RuntimeException e) {
            Path aside = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(path, aside);
                this.logger.at(Level.SEVERE).withCause(e).log("Could not read %s; moved it to %s", path, aside);
            } catch (IOException moveFailure) {
                this.logger.at(Level.SEVERE).withCause(e).log("Could not read %s, and could not move it aside", path);
            }
            return null;
        }
    }

    /** Called with the monitor held. Writes happen later on the saver thread, off the world threads. */
    private void markDirty() {
        if (this.saveScheduled) {
            return;
        }
        this.saveScheduled = true;
        try {
            this.saver.schedule(this::flush, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Already shutting down; close() writes everything.
            this.saveScheduled = false;
        }
    }

    /** Writes everything to disk now if anything has changed. */
    private void flush() {
        synchronized (this.writeLock) {
            String json;
            synchronized (this) {
                if (!this.saveScheduled) {
                    return;
                }
                this.saveScheduled = false;
                json = this.gson.toJson(this.data);
            }
            try {
                this.write(json);
            } catch (IOException e) {
                this.logger.at(Level.SEVERE).withCause(e).log("Failed to save %s; will retry", this.file);
                synchronized (this) {
                    this.markDirty();
                }
            }
        }
    }

    private void write(@Nonnull String json) throws IOException {
        Files.createDirectories(this.file.getParent());
        Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        if (Files.exists(this.file)) {
            Files.copy(this.file, this.backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(this.file.getParent());
    }

    /** Makes the rename itself survive a power loss. Not supported on every OS, so failures are ignored. */
    private static void syncDirectory(@Nonnull Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Writes any pending changes and stops the saver thread. Call on plugin shutdown. */
    public void close() {
        this.saver.shutdown();
        try {
            this.saver.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            // Make sure anything still pending (or dropped by the shutdown) gets written.
            if (!this.saveScheduled) {
                return;
            }
        }
        this.flush();
    }

    /** Returns a copy; change it and pass it to {@link #put}. */
    @Nullable
    public synchronized Access get(@Nullable String key) {
        if (key == null) {
            return null;
        }
        Access access = this.data.teleporters.get(key);
        return access == null ? null : access.copy();
    }

    public synchronized void put(@Nonnull String key, @Nonnull Access access) {
        this.data.teleporters.put(key, access.copy());
        this.markDirty();
    }

    /** True if the teleporter has no owner and wasn't given up, so it should go to whoever placed it. */
    static boolean needsBackfill(@Nullable Access access) {
        return access == null || (access.owner == null && !access.claimable);
    }

    /**
     * Gives a teleporter placed before this plugin to the player who placed it, keeping any settings
     * an admin already saved on it. Returns the record, which is left alone if it already has an owner
     * or was given up.
     */
    @Nonnull
    public synchronized Access backfillOwner(@Nonnull String key, @Nonnull UUID placer, @Nullable String name) {
        Access access = this.data.teleporters.get(key);
        if (needsBackfill(access)) {
            if (access == null) {
                access = new Access();
                this.data.teleporters.put(key, access);
            }
            access.owner = placer.toString();
            access.ownerName = name;
            access.shared.remove(access.owner);
            this.markDirty();
        }
        return access.copy();
    }

    /** Makes the player the owner of a teleporter its owner gave up. Returns the record, claimed or not. */
    @Nullable
    public synchronized Access claimIfGivenUp(@Nonnull String key, @Nonnull UUID player, @Nonnull String name) {
        Access access = this.data.teleporters.get(key);
        if (access == null || access.owner != null || !access.claimable) {
            return access == null ? null : access.copy();
        }
        access.owner = player.toString();
        access.ownerName = name;
        access.claimable = false;
        access.shared.remove(access.owner);
        this.markDirty();
        return access.copy();
    }

    /**
     * Resets the teleporter to public with no owner, for the next player to open its menu to claim,
     * if it is still owned by {@code owner}.
     */
    public synchronized boolean giveUp(@Nonnull String key, @Nonnull String owner) {
        Access access = this.data.teleporters.get(key);
        if (access == null || !owner.equals(access.owner)) {
            return false;
        }
        Access givenUp = new Access();
        givenUp.claimable = true;
        this.data.teleporters.put(key, givenUp);
        this.markDirty();
        return true;
    }

    public synchronized void remove(@Nonnull String key) {
        if (this.data.teleporters.remove(key) != null) {
            this.markDirty();
        }
    }

    public synchronized void rememberPlayer(@Nonnull UUID uuid, @Nonnull String name) {
        if (!name.equals(this.data.players.put(uuid.toString(), name))) {
            this.markDirty();
        }
    }

    @Nonnull
    public synchronized Map<String, String> knownPlayers() {
        return new LinkedHashMap<>(this.data.players);
    }

    public static boolean canUse(@Nullable Access access, @Nonnull UUID player, boolean isAdmin) {
        if (access == null || !access.isPrivate || isAdmin) {
            return true;
        }
        String id = player.toString();
        return id.equals(access.owner) || access.shared.containsKey(id);
    }

    /** Unowned teleporters can only be managed by admins. */
    public static boolean canManage(@Nullable Access access, @Nonnull UUID player, boolean isAdmin) {
        return isAdmin || (access != null && player.toString().equals(access.owner));
    }
}
