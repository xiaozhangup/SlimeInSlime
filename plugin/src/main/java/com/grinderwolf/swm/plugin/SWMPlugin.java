package com.grinderwolf.swm.plugin;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.google.common.collect.ImmutableList;
import com.grinderwolf.swm.plugin.config.DataSource;
import com.grinderwolf.swm.plugin.config.DataSourceConfig;
import com.grinderwolf.swm.plugin.listeners.WorldUnlocker;
import com.grinderwolf.swm.plugin.loaders.LoaderUtils;
import com.grinderwolf.swm.plugin.log.Logging;
import com.infernalsuite.aswm.api.SlimeNMSBridge;
import com.infernalsuite.aswm.api.SlimePlugin;
import com.infernalsuite.aswm.api.events.LoadSlimeWorldEvent;
import com.infernalsuite.aswm.api.exceptions.CorruptedWorldException;
import com.infernalsuite.aswm.api.exceptions.InvalidWorldException;
import com.infernalsuite.aswm.api.exceptions.NewerFormatException;
import com.infernalsuite.aswm.api.exceptions.UnknownWorldException;
import com.infernalsuite.aswm.api.exceptions.WorldAlreadyExistsException;
import com.infernalsuite.aswm.api.exceptions.WorldLoadedException;
import com.infernalsuite.aswm.api.exceptions.WorldLockedException;
import com.infernalsuite.aswm.api.exceptions.WorldTooBigException;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;
import com.infernalsuite.aswm.serialization.anvil.AnvilWorldReader;
import com.infernalsuite.aswm.serialization.slime.SlimeSerializer;
import com.infernalsuite.aswm.serialization.slime.reader.SlimeWorldReaderRegistry;
import com.infernalsuite.aswm.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.aswm.api.world.SlimeWorld;
import com.infernalsuite.aswm.api.world.SlimeWorldInstance;
import com.infernalsuite.aswm.api.world.properties.SlimePropertyMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class SWMPlugin implements SlimePlugin, Listener {

    private static final SlimeNMSBridge BRIDGE_INSTANCE = SlimeNMSBridge.instance();

    private final Map<String, SlimeWorld> loadedWorlds = new ConcurrentHashMap<>();

    private static boolean isPaperMC = false;
    private static Plugin plugin;

    private static boolean checkIsPaper() {
        try {
            return Class.forName("com.destroystokyo.paper.PaperConfig") != null;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }


    public void loading(Plugin plugin, DataSourceConfig dataSourceConfig) throws SQLException {
        isPaperMC = checkIsPaper();
        SWMPlugin.plugin = plugin;

        LoaderUtils.registerLoaders(dataSourceConfig);
        // Default world override
        try {
            Properties props = new Properties();

            props.load(new FileInputStream("server.properties"));
            String defaultWorldName = props.getProperty("level-name");

            SlimeWorld defaultWorld = loadedWorlds.get(defaultWorldName);
            SlimeWorld netherWorld = plugin.getServer().getAllowNether() ? loadedWorlds.get(defaultWorldName + "_nether") : null;
            SlimeWorld endWorld = plugin.getServer().getAllowEnd() ? loadedWorlds.get(defaultWorldName + "_the_end") : null;

            BRIDGE_INSTANCE.setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            Logging.error("Failed to retrieve default world name:");
            ex.printStackTrace();
        }
    }

    public void enable() {
        loadedWorlds.values().stream()
                .filter(slimeWorld -> Objects.isNull(Bukkit.getWorld(slimeWorld.getName())))
                .forEach(this::loadWorld);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(new WorldUnlocker(), plugin);
        //loadedWorlds.clear // - Commented out because not sure why this would be cleared. Needs checking
    }

    public void disable() {
    }


    @Override
    public SlimeWorld loadWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException,
            CorruptedWorldException, NewerFormatException, WorldLockedException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        if (!readOnly) {
            loader.acquireLock(worldName);
        }

        long start = System.currentTimeMillis();

        Logging.info("Loading world " + worldName + ".");
        byte[] serializedWorld = loader.loadWorld(worldName);

        SlimeWorld slimeWorld = SlimeWorldReaderRegistry.readWorld(loader, worldName, serializedWorld, propertyMap);
        Logging.info("Applying datafixers for " + worldName + ".");
        SlimeNMSBridge.instance().applyDataFixers(slimeWorld);

        Logging.info("World " + worldName + " loaded in " + (System.currentTimeMillis() - start) + "ms.");

        registerWorld(slimeWorld);
        return slimeWorld;
    }

    @Override
    public SlimeWorld getWorld(String worldName) {
        return loadedWorlds.get(worldName);
    }

    public List<SlimeWorld> getLoadedWorlds() {
        return ImmutableList.copyOf(loadedWorlds.values());
    }

    @Override
    public SlimeWorld createEmptyWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws WorldAlreadyExistsException, IOException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        Logging.info("Creating empty world " + worldName + ".");
        long start = System.currentTimeMillis();
        SlimeWorld blackhole = new SkeletonSlimeWorld(worldName, readOnly ? null : loader, Map.of(), new CompoundTag("", new CompoundMap()), propertyMap, BRIDGE_INSTANCE.getCurrentVersion());

        loader.saveWorld(worldName, SlimeSerializer.serialize(blackhole));

        Logging.info("World " + worldName + " created in " + (System.currentTimeMillis() - start) + "ms.");

        registerWorld(blackhole);
        return blackhole;
    }

    /**
     * Utility method to register a <b>loaded</b> {@link SlimeWorld} with the internal map (for {@link #getWorld} calls)
     *
     * @param world the world to register
     */
    private void registerWorld(SlimeWorld world) {
        SWMPlugin.getInstance().loadedWorlds.put(world.getName(), world);
    }


    /**
     * Ensure worlds are removed from the loadedWorlds map when {@link Bukkit#unloadWorld} is called.
     */
    @EventHandler
    public void onBukkitWorldUnload(WorldUnloadEvent worldUnloadEvent) {
        loadedWorlds.remove(worldUnloadEvent.getWorld().getName());
    }

    @Override
    public SlimeWorld loadWorld(SlimeWorld slimeWorld) {
        Objects.requireNonNull(slimeWorld, "SlimeWorld cannot be null");

        SlimeWorldInstance instance = BRIDGE_INSTANCE.loadInstance(slimeWorld);

        SlimeWorld mirror = instance.getSlimeWorldMirror();
        Bukkit.getPluginManager().callEvent(new LoadSlimeWorldEvent(mirror));
        registerWorld(mirror);

        if (!slimeWorld.isReadOnly() && slimeWorld.getLoader() != null) {
            try {
                slimeWorld.getLoader().acquireLock(slimeWorld.getName());
            } catch (UnknownWorldException | WorldLockedException | IOException e) {
                e.printStackTrace();
            }
        }

        return mirror;
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader) throws IOException,
            WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        byte[] serializedWorld = currentLoader.loadWorld(worldName);
        newLoader.saveWorld(worldName, serializedWorld);
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeLoader getLoader(String dataSource) {
        return LoaderUtils.getLoader();
    }

    @Override
    public void registerLoader(String dataSource, SlimeLoader loader) {
        Objects.requireNonNull(dataSource, "Data source cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");
    }

    @Override
    public void importWorld(File worldDir, String worldName, SlimeLoader loader) throws WorldAlreadyExistsException, InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException {
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldDir.getName());

        if (bukkitWorld != null && BRIDGE_INSTANCE.getInstance(bukkitWorld) == null) {
            throw new WorldLoadedException(worldDir.getName());
        }

        SlimeWorld world = AnvilWorldReader.readFromDirectory(worldDir);

        byte[] serializedWorld;

        try {
            serializedWorld = SlimeSerializer.serialize(world);
        } catch (IndexOutOfBoundsException ex) {
            throw new WorldTooBigException(worldDir.getName());
        }

        loader.saveWorld(worldName, serializedWorld);
    }


    public static boolean isPaperMC() {
        return isPaperMC;
    }

    public static SWMPlugin getInstance() {
        return INSTANCE;
    }

    private final static SWMPlugin INSTANCE = new SWMPlugin();

    public Plugin getPlugin() {
        return plugin;
    }

    private void runAsync(Runnable runnable) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable);
    }
}
