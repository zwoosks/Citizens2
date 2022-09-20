package net.citizensnpcs;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.InventoryHelper;
import net.citizensnpcs.api.SkullMetaProvider;
import net.citizensnpcs.api.ai.speech.SpeechFactory;
import net.citizensnpcs.api.command.CommandManager;
import net.citizensnpcs.api.command.CommandManager.CommandInfo;
import net.citizensnpcs.api.command.Injector;
import net.citizensnpcs.api.event.CitizensDisableEvent;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.CitizensPreReloadEvent;
import net.citizensnpcs.api.event.CitizensReloadEvent;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.SimpleNPCDataStore;
import net.citizensnpcs.api.scripting.EventRegistrar;
import net.citizensnpcs.api.scripting.ObjectProvider;
import net.citizensnpcs.api.scripting.ScriptCompiler;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.NBTStorage;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.Translator;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.commands.AdminCommands;
import net.citizensnpcs.commands.EditorCommands;
import net.citizensnpcs.commands.NPCCommands;
import net.citizensnpcs.commands.TemplateCommands;
import net.citizensnpcs.commands.TraitCommands;
import net.citizensnpcs.commands.WaypointCommands;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.npc.CitizensNPCRegistry;
import net.citizensnpcs.npc.CitizensTraitFactory;
import net.citizensnpcs.npc.NPCSelector;
import net.citizensnpcs.npc.ai.speech.Chat;
import net.citizensnpcs.npc.ai.speech.CitizensSpeechFactory;
import net.citizensnpcs.npc.profile.ProfileFetcher;
import net.citizensnpcs.npc.skin.Skin;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.NMS;
import net.citizensnpcs.util.PlayerUpdateTask;
import net.citizensnpcs.util.StringHelper;
import net.citizensnpcs.util.Util;
import net.milkbowl.vault.economy.Economy;

public class Citizens extends JavaPlugin implements CitizensPlugin {
    private final List<NPCRegistry> anonymousRegistries = Lists.newArrayList();
    private final List<NPCRegistry> citizensBackedRegistries = Lists.newArrayList();
    private final CommandManager commands = new CommandManager();
    private Settings config;
    private boolean enabled;
    private final InventoryHelper inventoryHelper = new InventoryHelper() {
        @Override
        public InventoryView openAnvilInventory(Player player, Inventory inventory, String title) {
            return NMS.openAnvilInventory(player, inventory, title);
        }

        @Override
        public void updateInventoryTitle(Player player, InventoryView view, String newTitle) {
            if (view.getTopInventory().getType() == InventoryType.CRAFTING
                    || view.getTopInventory().getType() == InventoryType.CREATIVE
                    || view.getTopInventory().getType() == InventoryType.PLAYER)
                return;
            NMS.updateInventoryTitle(player, view, newTitle);
        }
    };
    private CitizensNPCRegistry npcRegistry;
    private boolean saveOnDisable = true;
    private NPCDataStore saves;
    private NPCSelector selector;
    private Storage shops;
    private final SkullMetaProvider skullMetaProvider = new SkullMetaProvider() {
        @Override
        public String getTexture(SkullMeta meta) {
            if (NMS.getProfile(meta) == null)
                return null;
            return Iterables.getFirst(NMS.getProfile(meta).getProperties().get("textures"), new Property("", ""))
                    .getValue();
        }

        @Override
        public void setTexture(String string, SkullMeta meta) {
            UUID uuid = meta.getOwningPlayer() == null ? UUID.randomUUID() : meta.getOwningPlayer().getUniqueId();
            NMS.setProfile(meta, new GameProfile(uuid, string));
        }
    };
    private CitizensSpeechFactory speechFactory;
    private final Map<String, NPCRegistry> storedRegistries = Maps.newHashMap();
    private CitizensTraitFactory traitFactory;

    @Override
    public NPCRegistry createAnonymousNPCRegistry(NPCDataStore store) {
        CitizensNPCRegistry anon = new CitizensNPCRegistry(store, "anonymous-" + UUID.randomUUID().toString());
        anonymousRegistries.add(anon);
        return anon;
    }

    @Override
    public NPCRegistry createCitizensBackedNPCRegistry(NPCDataStore store) {
        CitizensNPCRegistry anon = new CitizensNPCRegistry(store, "anonymous-citizens-" + UUID.randomUUID().toString());
        citizensBackedRegistries.add(anon);
        return anon;
    }

    @Override
    public NPCRegistry createNamedNPCRegistry(String name, NPCDataStore store) {
        NPCRegistry created = new CitizensNPCRegistry(store, name);
        storedRegistries.put(name, created);
        return created;
    }

    @Override
    public CommandManager getCommandManager() {
        return null;
    }

    private NPCDataStore createStorage(File folder) {
        Storage saves = null;
        String type = Setting.STORAGE_TYPE.asString();
        if (type.equalsIgnoreCase("nbt")) {
            saves = new NBTStorage(new File(folder + File.separator + Setting.STORAGE_FILE.asString()),
                    "Citizens NPC Storage");
        }
        if (saves == null) {
            saves = new YamlStorage(new File(folder, Setting.STORAGE_FILE.asString()), "Citizens NPC Storage");
        }
        if (!saves.load())
            return null;
        return SimpleNPCDataStore.create(saves);
    }

    private void despawnNPCs(boolean save) {
        for (NPCRegistry registry : Iterables.concat(Arrays.asList(npcRegistry), citizensBackedRegistries)) {
            if (registry == null)
                continue;
            if (save) {
                registry.saveToStore();
            }
            registry.despawnNPCs(DespawnReason.RELOAD);
        }
    }

    private void enableSubPlugins() {
        File root = new File(getDataFolder(), Setting.SUBPLUGIN_FOLDER.asString());
        if (!root.exists() || !root.isDirectory())
            return;
        File[] files = root.listFiles();
        for (File file : files) {
            Plugin plugin;
            try {
                plugin = Bukkit.getPluginManager().loadPlugin(file);
            } catch (Exception e) {
                continue;
            }
            if (plugin == null)
                continue;
            // code beneath modified from CraftServer
            try {
                Messaging.logTr(Messages.LOADING_SUB_PLUGIN, plugin.getDescription().getFullName());
                plugin.onLoad();
            } catch (Throwable ex) {
                Messaging.severeTr(Messages.ERROR_INITALISING_SUB_PLUGIN, ex.getMessage(),
                        plugin.getDescription().getFullName());
                ex.printStackTrace();
            }
        }
        NMS.loadPlugins();
    }

    public CommandInfo getCommandInfo(String rootCommand, String modifier) {
        return commands.getCommand(rootCommand, modifier);
    }

    public Iterable<CommandInfo> getCommands(String base) {
        return commands.getCommands(base);
    }

    @Override
    public net.citizensnpcs.api.npc.NPCSelector getDefaultNPCSelector() {
        return selector;
    }

    @Override
    public InventoryHelper getInventoryHelper() {
        return inventoryHelper;
    }

    @Override
    public NPCRegistry getNamedNPCRegistry(String name) {
        if (name.equals(npcRegistry.getName()))
            return npcRegistry;
        return storedRegistries.get(name);
    }

    @Override
    public Iterable<NPCRegistry> getNPCRegistries() {
        return new Iterable<NPCRegistry>() {
            @Override
            public Iterator<NPCRegistry> iterator() {
                return new Iterator<NPCRegistry>() {
                    Iterator<NPCRegistry> stored;

                    @Override
                    public boolean hasNext() {
                        return stored == null ? true : stored.hasNext();
                    }

                    @Override
                    public NPCRegistry next() {
                        if (stored == null) {
                            stored = Iterables
                                    .concat(storedRegistries.values(), anonymousRegistries, citizensBackedRegistries)
                                    .iterator();
                            return npcRegistry;
                        }
                        return stored.next();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    @Override
    public NPCRegistry getNPCRegistry() {
        return npcRegistry;
    }

    public NPCSelector getNPCSelector() {
        return selector;
    }

    @Override
    public ClassLoader getOwningClassLoader() {
        return getClassLoader();
    }

    @Override
    public File getScriptFolder() {
        return new File(getDataFolder(), "scripts");
    }

    @Override
    public SkullMetaProvider getSkullMetaProvider() {
        return skullMetaProvider;
    }

    @Override
    public SpeechFactory getSpeechFactory() {
        return speechFactory;
    }

    @Override
    public TraitFactory getTraitFactory() {
        return traitFactory;
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String cmdName, String[] args) {
        String modifier = args.length > 0 ? args[0] : "";
        if (!commands.hasCommand(command, modifier) && !modifier.isEmpty()) {
            return suggestClosestModifier(sender, command.getName(), modifier);
        }

        NPC npc = selector == null ? null : selector.getSelected(sender);
        // TODO: change the args supplied to a context style system for
        // flexibility (ie. adding more context in the future without
        // changing everything)

        Object[] methodArgs = { sender, npc };
        return commands.executeSafe(command, args, sender, methodArgs);
    }

    public void onDependentPluginDisable() {
        storeNPCs(false);
        saveOnDisable = false;
    }

    @Override
    public void onDisable() {
        if (!enabled) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new CitizensDisableEvent());
        Editor.leaveAll();
        despawnNPCs(saveOnDisable);
        HandlerList.unregisterAll(this);
        npcRegistry = null;
        enabled = false;
        saveOnDisable = true;
        NMS.shutdown();
        CitizensAPI.shutdown();
    }

    @Override
    public void onEnable() {
        CitizensAPI.setImplementation(this);
        config = new Settings(getDataFolder());
        setupTranslator();
        // Disable if the server is not using the compatible Minecraft version
        String mcVersion = Util.getMinecraftRevision();
        try {
            NMS.loadBridge(mcVersion);
        } catch (Exception e) {
            if (Messaging.isDebugging()) {
                e.printStackTrace();
            }
            Messaging.severeTr(Messages.CITIZENS_INCOMPATIBLE, getDescription().getVersion(), mcVersion);
            enabled = true;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        registerScriptHelpers();

        saves = createStorage(getDataFolder());
        shops = new YamlStorage(new File(getDataFolder(), "shops.yml"));
        if (saves == null || !shops.load()) {
            Messaging.severeTr(Messages.FAILED_LOAD_SAVES);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        npcRegistry = new CitizensNPCRegistry(saves, "citizens");
        traitFactory = new CitizensTraitFactory();
        selector = new NPCSelector(this);
        speechFactory = new CitizensSpeechFactory();
        speechFactory.register(Chat.class, "chat");

        Bukkit.getPluginManager().registerEvents(new EventListen(storedRegistries), this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new CitizensPlaceholders(selector).register();
        }

        setupEconomy();

        registerCommands();
        enableSubPlugins();
        NMS.load(commands);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        commands.registerTabCompletion(this);

        // Setup NPCs after all plugins have been enabled (allows for multiworld
        // support and for NPCs to properly register external settings)
        if (getServer().getScheduler().scheduleSyncDelayedTask(this, new CitizensLoadTask(), 1) == -1) {
            Messaging.severeTr(Messages.LOAD_TASK_NOT_SCHEDULED);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onImplementationChanged() {
        Messaging.severeTr(Messages.CITIZENS_IMPLEMENTATION_DISABLED);
        Bukkit.getPluginManager().disablePlugin(this);
    }

    public void registerCommandClass(Class<?> clazz) {
        try {
            commands.register(clazz);
        } catch (Throwable ex) {
            Messaging.logTr(Messages.CITIZENS_INVALID_COMMAND_CLASS);
            ex.printStackTrace();
        }
    }

    private void registerCommands() {
        commands.setInjector(new Injector(this));
        // Register command classes
        commands.register(AdminCommands.class);
        commands.register(EditorCommands.class);
        commands.register(NPCCommands.class);
        commands.register(TemplateCommands.class);
        commands.register(TraitCommands.class);
        commands.register(WaypointCommands.class);
    }

    private void registerScriptHelpers() {
        ScriptCompiler compiler = CitizensAPI.getScriptCompiler();
        compiler.registerGlobalContextProvider(new EventRegistrar(this));
        compiler.registerGlobalContextProvider(new ObjectProvider("plugin", this));
    }

    public void reload() throws NPCLoadException {
        Editor.leaveAll();
        config.reload();
        despawnNPCs(false);
        ProfileFetcher.reset();
        Skin.clearCache();
        getServer().getPluginManager().callEvent(new CitizensPreReloadEvent());

        saves.reloadFromSource();
        saves.loadInto(npcRegistry);

        shops.load();
        ShopTrait.loadShops(shops.getKey(""));

        getServer().getPluginManager().callEvent(new CitizensReloadEvent());
    }

    @Override
    public void removeNamedNPCRegistry(String name) {
        storedRegistries.remove(name);
    }

    private void scheduleSaveTask(int delay) {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new CitizensSaveTask(), delay, delay);
    }

    @Override
    public void setDefaultNPCDataStore(NPCDataStore store) {
        if (store == null) {
            throw new IllegalArgumentException("must be non-null");
        }
        despawnNPCs(true);
        this.saves = store;
        this.npcRegistry = new CitizensNPCRegistry(saves, "citizens-global-" + UUID.randomUUID().toString());
        saves.loadInto(npcRegistry);
    }

    private void setupEconomy() {
        try {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null && provider.getProvider() != null) {
                Economy economy = provider.getProvider();
                Bukkit.getPluginManager().registerEvents(new PaymentListener(economy), this);
            }
            Messaging.logTr(Messages.LOADED_ECONOMY);
        } catch (NoClassDefFoundError e) {
        }
    }

    private void setupTranslator() {
        Locale locale = Locale.getDefault();
        String setting = Setting.LOCALE.asString();
        if (!setting.isEmpty()) {
            String[] parts = setting.split("[\\._]");
            switch (parts.length) {
                case 1:
                    locale = new Locale(parts[0]);
                    break;
                case 2:
                    locale = new Locale(parts[0], parts[1]);
                    break;
                case 3:
                    locale = new Locale(parts[0], parts[1], parts[2]);
                    break;
                default:
                    break;
            }
        }
        Translator.setInstance(new File(getDataFolder(), "lang"), locale);
    }

    private void startMetrics() {
        try {
            Metrics metrics = new Metrics(this, 2463);

            metrics.addCustomChart(new Metrics.SingleLineChart("total_npcs", new Callable<Integer>() {
                @Override
                public Integer call() {
                    if (npcRegistry == null)
                        return 0;
                    return Iterables.size(npcRegistry);
                }
            }));
            /*
            TODO: not implemented yet
            metrics.addCustomChart(new Metrics.MultiLineChart("traits", new Callable<Map<String, Integer>>() {
                @Override
                public Map<String, Integer> call() throws Exception {
                    return traitFactory.getTraitPlot();
                }
            }));
            */
        } catch (Exception e) {
            Messaging.logTr(Messages.METRICS_ERROR_NOTIFICATION, e.getMessage());
        }
    }

    public void storeNPCs() {
        storeNPCs(false);
    }

    public void storeNPCs(boolean async) {
        if (saves == null)
            return;
        saves.storeAll(npcRegistry);
        ShopTrait.saveShops(shops.getKey(""));
        if (async) {
            saves.saveToDisk();
            new Thread(() -> {
                shops.save();
            }).start();
        } else {
            shops.save();
            saves.saveToDiskImmediate();
        }
    }

    private boolean suggestClosestModifier(CommandSender sender, String command, String modifier) {
        String closest = commands.getClosestCommandModifier(command, modifier);
        if (!closest.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + Messaging.tr(Messages.UNKNOWN_COMMAND));
            sender.sendMessage(StringHelper.wrap(" /") + command + " " + StringHelper.wrap(closest));
            return true;
        }
        return false;
    }

    private class CitizensLoadTask implements Runnable {
        @Override
        public void run() {
            saves.loadInto(npcRegistry);
            ShopTrait.loadShops(shops.getKey(""));

            Messaging.logTr(Messages.NUM_LOADED_NOTIFICATION, Iterables.size(npcRegistry), "?");
            startMetrics();
            scheduleSaveTask(Setting.SAVE_TASK_DELAY.asInt());
            Bukkit.getPluginManager().callEvent(new CitizensEnableEvent());
            new PlayerUpdateTask().runTaskTimer(Citizens.this, 0, 1);
            enabled = true;
        }
    }

    private class CitizensSaveTask implements Runnable {
        @Override
        public void run() {
            storeNPCs(false);
        }
    }
}
