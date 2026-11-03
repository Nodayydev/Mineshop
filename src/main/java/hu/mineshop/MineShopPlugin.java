package hu.mineshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public final class MineShopPlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<Material, Double> basePrices = new EnumMap<>(Material.class);
    private final Map<Material, SaleData> saleData = new EnumMap<>(Material.class);
    private final Map<UUID, Integer> categoryPages = new HashMap<>();
    private NamespacedKey categoryKey, materialKey, actionKey;
    private File pricesFile, salesFile, resetFile;
    private long lastEconomyReset;
    private EconomyStorage economyStorage;
    private YamlConfiguration prices, sales;

    @Override public void onEnable() {
        saveDefaultConfig();
        addStorageDefaults();
        categoryKey = new NamespacedKey(this, "category");
        materialKey = new NamespacedKey(this, "material");
        actionKey = new NamespacedKey(this, "action");
        if (!setupEconomy()) { getLogger().severe("Vault vagy Vault-kompatibilis economy plugin nem található. A plugin leáll."); Bukkit.getPluginManager().disablePlugin(this); return; }
        createDefaultCategories();
        autoCategorizeAllMaterials();
        createItemCatalog();
        loadPrices();
        try {
            economyStorage = EconomyStorage.open(this, getConfig());
            loadSales();
        } catch (Exception e) {
            getLogger().severe("Nem sikerült megnyitni az economy adattárolót: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        loadCategories();
        setupEconomyReset();
        Objects.requireNonNull(getCommand("shop")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Ezt a parancsot játékos használhatja."); return true; }
            openCategories(player); return true;
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MineShop elindult: " + categories.size() + " kategória, " + basePrices.size() + " termék.");
    }

    private void addStorageDefaults() {
        getConfig().addDefault("economy-reset.enabled", true);
        getConfig().addDefault("economy-reset.interval-months", 3);
        getConfig().addDefault("storage.type", "sqlite");
        getConfig().addDefault("storage.sqlite.file", "data/economy.db");
        getConfig().addDefault("storage.mysql.host", "127.0.0.1");
        getConfig().addDefault("storage.mysql.port", 3306);
        getConfig().addDefault("storage.mysql.database", "mineshop");
        getConfig().addDefault("storage.mysql.username", "root");
        getConfig().addDefault("storage.mysql.password", "");
        getConfig().addDefault("storage.mysql.use-ssl", false);
        getConfig().addDefault("storage.mysql.table-prefix", "mineshop_");
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    @Override public void onDisable() {
        saveSales();
        if (economyStorage != null) {
            try { economyStorage.close(); }
            catch (Exception e) { getLogger().severe("Adatbázis bezárási hiba: " + e.getMessage()); }
        }
    }

    private void setupEconomyReset() {
        if (!getConfig().getBoolean("economy-reset.enabled", true)) {
            getLogger().info("Az economy reset ki van kapcsolva.");
            return;
        }
        resetFile = new File(getDataFolder(), "data/economy-reset.yml");
        try {
            if (economyStorage != null && economyStorage.isSql()) lastEconomyReset = economyStorage.getLastReset();
            else lastEconomyReset = YamlConfiguration.loadConfiguration(resetFile).getLong("last-reset", 0L);
            if (lastEconomyReset <= 0) {
                lastEconomyReset = System.currentTimeMillis();
                saveEconomyResetTimestamp();
                getLogger().info("Economy reset ütemezve: " + resetIntervalMonths() + " hónap múlva.");
            } else {
                runEconomyResetIfDue();
            }
            Bukkit.getScheduler().runTaskTimer(this, this::runEconomyResetIfDue, 20L * 60 * 60, 20L * 60 * 60);
        } catch (Exception e) {
            getLogger().severe("Nem sikerült beállítani az economy resetet: " + e.getMessage());
        }
    }

    private int resetIntervalMonths() {
        return Math.max(1, getConfig().getInt("economy-reset.interval-months", 3));
    }

    private void runEconomyResetIfDue() {
        if (!getConfig().getBoolean("economy-reset.enabled", true) || lastEconomyReset <= 0) return;
        long now = System.currentTimeMillis();
        long nextReset = Instant.ofEpochMilli(lastEconomyReset).atZone(ZoneOffset.UTC)
                .plusMonths(resetIntervalMonths()).toInstant().toEpochMilli();
        if (now < nextReset) return;
        try {
            if (economyStorage != null && economyStorage.isSql()) economyStorage.resetEconomy(now);
            for (SaleData data : saleData.values()) {
                data.pressure = 0;
                data.lastSale = now;
            }
            lastEconomyReset = now;
            if (economyStorage == null || !economyStorage.isSql()) saveSales();
            saveEconomyResetTimestamp();
            getLogger().info("Economy reset kész: az itemárak dinamikus százalékai nullázódtak. Az összesített eladási darabszám megmaradt.");
        } catch (Exception e) {
            getLogger().severe("Economy reset hiba, a következő ellenőrzés újrapróbálja: " + e.getMessage());
        }
    }

    private void saveEconomyResetTimestamp() throws Exception {
        if (economyStorage != null && economyStorage.isSql()) {
            economyStorage.initializeLastReset(lastEconomyReset);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("last-reset", lastEconomyReset);
        saveYaml(yaml, resetFile);
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider(); return economy != null;
    }

    private void createDefaultCategories() {
        File folder = new File(getDataFolder(), "categories");
        if (!folder.exists() && !folder.mkdirs()) getLogger().warning("Nem sikerült létrehozni a categories mappát.");
        Map<String, String[]> defaults = new LinkedHashMap<>();
        defaults.put("epitoelemek", new String[]{"Építőelemek", "BRICKS", "STONE,COBBLESTONE,STONE_BRICKS,BRICKS,GLASS,OAK_PLANKS"});
        defaults.put("szinesblokkok", new String[]{"Színes blokkok", "CYAN_WOOL", "WHITE_WOOL,ORANGE_WOOL,MAGENTA_WOOL,LIGHT_BLUE_WOOL,YELLOW_WOOL,LIME_WOOL,PINK_WOOL,GRAY_WOOL,LIGHT_GRAY_WOOL,CYAN_WOOL,PURPLE_WOOL,BLUE_WOOL,BROWN_WOOL,GREEN_WOOL,RED_WOOL,BLACK_WOOL"});
        defaults.put("termeszetesblokkok", new String[]{"Természetes blokkok", "GRASS_BLOCK", "DIRT,GRASS_BLOCK,SAND,GRAVEL,CLAY,COAL_ORE,IRON_ORE,DIAMOND_ORE,OBSIDIAN,OAK_LOG"});
        defaults.put("hasznalati", new String[]{"Használati blokkok", "CRAFTING_TABLE", "CRAFTING_TABLE,FURNACE,CHEST,BARREL,ANVIL,ENCHANTING_TABLE,ENDER_CHEST"});
        defaults.put("redstone", new String[]{"Redstone blokkok", "REDSTONE", "REDSTONE,REDSTONE_TORCH,REPEATER,COMPARATOR,PISTON,STICKY_PISTON,HOPPER,DISPENSER,OBSERVER"});
        defaults.put("eszkozok", new String[]{"Eszközök", "DIAMOND_PICKAXE", "WOODEN_PICKAXE,STONE_PICKAXE,IRON_PICKAXE,DIAMOND_PICKAXE,NETHERITE_PICKAXE,IRON_AXE,DIAMOND_SHOVEL"});
        defaults.put("harc", new String[]{"Harc", "DIAMOND_SWORD", "WOODEN_SWORD,IRON_SWORD,DIAMOND_SWORD,NETHERITE_SWORD,BOW,CROSSBOW,SHIELD,ARROW,DIAMOND_CHESTPLATE"});
        defaults.put("etelek", new String[]{"Ételek és italok", "GOLDEN_CARROT", "BREAD,COOKED_BEEF,COOKED_PORKCHOP,APPLE,GOLDEN_CARROT,GOLDEN_APPLE,POTION"});
        defaults.put("osszetevok", new String[]{"Összetevők", "DIAMOND", "COAL,IRON_INGOT,GOLD_INGOT,COPPER_INGOT,DIAMOND,EMERALD,NETHERITE_INGOT,REDSTONE,LAPIS_LAZULI,BLAZE_ROD"});
        defaults.put("idezotojasok", new String[]{"Idézőtojások", "PIG_SPAWN_EGG", "PIG_SPAWN_EGG,COW_SPAWN_EGG,SHEEP_SPAWN_EGG,ZOMBIE_SPAWN_EGG,SKELETON_SPAWN_EGG"});
        for (var entry : defaults.entrySet()) {
            File file = new File(folder, entry.getKey() + ".yml"); if (file.exists()) continue;
            YamlConfiguration yml = new YamlConfiguration(); yml.set("name", entry.getValue()[0]); yml.set("icon", entry.getValue()[1]);
            double price = 5.0;
            for (String name : entry.getValue()[2].split(",")) { Material m = Material.matchMaterial(name); if (m != null && m.isItem()) { yml.set("items." + name + ".base-price", price); price = Math.max(1, price * 1.65); } }
            try { yml.save(file); } catch (IOException e) { getLogger().warning("Nem menthető kategória: " + file.getName()); }
        }
    }

    private void autoCategorizeAllMaterials() {
        File trackingFile = new File(getDataFolder(), "catalog/auto-categorized.yml");
        YamlConfiguration tracking = YamlConfiguration.loadConfiguration(trackingFile);
        Set<String> processed = new HashSet<>(tracking.getStringList("processed"));
        Set<String> assigned = new HashSet<>();
        File directory = new File(getDataFolder(), "categories");
        File[] categoryFiles = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (categoryFiles != null) for (File file : categoryFiles) {
            YamlConfiguration category = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = category.getConfigurationSection("items");
            if (section != null) {
                for (String itemName : new ArrayList<>(section.getKeys(false))) {
                    Material material = Material.matchMaterial(itemName);
                    if (material == null || !isAllowedShopItem(material)) category.set("items." + itemName, null);
                    else assigned.add(itemName);
                }
                saveYaml(category, file);
            }
        }

        int added = 0;
        for (Material material : Material.values()) {
            if (!isAllowedShopItem(material) || processed.contains(material.name())) continue;
            String categoryId = assigned.contains(material.name()) ? null : categoryFor(material);
            if (categoryId != null) {
                File file = new File(directory, categoryId + ".yml");
                YamlConfiguration category = YamlConfiguration.loadConfiguration(file);
                if (!category.contains("name")) category.set("name", categoryName(categoryId));
                if (!category.contains("icon")) category.set("icon", categoryIcon(categoryId));
                category.set("items." + material.name() + ".base-price", defaultBasePrice(material, categoryId));
                saveYaml(category, file);
                added++;
            }
            processed.add(material.name());
        }
        tracking.set("description", "A már feldolgozott itemek nyilvántartása. A törölt kategóriaelemeket a plugin nem állítja vissza.");
        tracking.set("processed", processed.stream().sorted().toList());
        saveYaml(tracking, trackingFile);
        if (added > 0) getLogger().info("Automatikusan kategorizált itemek: " + added);
    }

    private boolean isAllowedShopItem(Material material) {
        if (!material.isItem() || material.isAir()) return false;
        String name = material.name();
        if (name.startsWith("LEGACY_") || name.contains("DEBUG") || name.contains("TEST_")) return false;
        return !Set.of("BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
                "COMMAND_BLOCK_MINECART", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "KNOWLEDGE_BOOK",
                "LIGHT", "SPAWNER", "END_PORTAL_FRAME", "END_PORTAL", "END_GATEWAY", "REINFORCED_DEEPSLATE").contains(name);
    }

    private String categoryFor(Material material) {
        String name = material.name();
        if (name.endsWith("_SPAWN_EGG")) return "idezotojasok";
        if (material.isEdible() || Set.of("POTION", "SPLASH_POTION", "LINGERING_POTION", "MILK_BUCKET", "HONEY_BOTTLE", "OMINOUS_BOTTLE", "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP").contains(name)) return "etelek";
        if (isCombatItem(name)) return "harc";
        if (isToolItem(name)) return "eszkozok";
        if (!material.isBlock()) return "osszetevok";
        if (isColoredBlock(name)) return "szinesblokkok";
        if (isNaturalBlock(name)) return "termeszetesblokkok";
        if (isRedstoneBlock(name)) return "redstone";
        if (isUtilityBlock(name)) return "hasznalati";
        return "epitoelemek";
    }

    private boolean isCombatItem(String name) {
        return name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || Set.of("BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "ARROW", "SPECTRAL_ARROW", "TIPPED_ARROW", "TOTEM_OF_UNDYING", "ELYTRA").contains(name);
    }

    private boolean isToolItem(String name) {
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || Set.of("SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "BRUSH", "SPYGLASS", "COMPASS", "CLOCK", "RECOVERY_COMPASS", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK").contains(name);
    }

    private boolean isColoredBlock(String name) {
        for (String color : List.of("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"))
            if (name.startsWith(color + "_")) return true;
        return name.contains("_WOOL") || name.contains("_TERRACOTTA") || name.contains("_CONCRETE")
                || name.contains("_STAINED_GLASS") || name.contains("_SHULKER_BOX") || name.contains("_CANDLE")
                || name.contains("_BANNER") || name.contains("_BED") || name.contains("_CARPET") || name.contains("_GLAZED_TERRACOTTA");
    }

    private boolean isNaturalBlock(String name) {
        return name.endsWith("_ORE") || name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_LEAVES")
                || name.endsWith("_SAPLING") || name.endsWith("_CORAL") || name.endsWith("_CORAL_BLOCK")
                || name.endsWith("_CORAL_FAN") || name.endsWith("_FUNGUS") || name.endsWith("_MUSHROOM")
                || name.endsWith("_ROOTS") || name.endsWith("_VINES") || name.endsWith("_FLOWER")
                || name.endsWith("_TULIP") || name.endsWith("_FERN") || name.endsWith("_SEEDS")
                || Set.of("DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TORCHFLOWER", "PITCHER_PLANT", "DEAD_BUSH", "CACTUS", "SUGAR_CANE", "BAMBOO", "KELP", "DRIED_KELP_BLOCK", "SEAGRASS", "TALL_SEAGRASS", "SEA_PICKLE", "LILY_PAD", "GLOW_LICHEN", "HANGING_ROOTS", "NETHER_SPROUTS", "CRIMSON_ROOTS", "WARPED_ROOTS", "WEEPING_VINES", "TWISTING_VINES", "CAVE_VINES", "CAVE_VINES_PLANT").contains(name)
                || Set.of("STONE", "DEEPSLATE", "TUFF", "ANDESITE", "DIORITE", "GRANITE", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "SAND", "RED_SAND", "GRAVEL", "CLAY", "MUD", "MOSS_BLOCK", "ICE", "PACKED_ICE", "BLUE_ICE", "SNOW", "POWDER_SNOW", "OBSIDIAN", "NETHERRACK", "END_STONE", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL", "RAW_IRON_BLOCK", "RAW_COPPER_BLOCK", "RAW_GOLD_BLOCK").contains(name);
    }

    private boolean isRedstoneBlock(String name) {
        if (name.endsWith("_ORE")) return false;
        return Set.of("REDSTONE", "REDSTONE_BLOCK", "REDSTONE_TORCH", "REPEATER", "COMPARATOR", "PISTON", "STICKY_PISTON", "OBSERVER", "DROPPER", "DISPENSER", "HOPPER", "NOTE_BLOCK", "JUKEBOX", "LEVER", "TRIPWIRE", "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "LIGHTNING_ROD", "TARGET", "REDSTONE_LAMP", "CRAFTER", "TNT").contains(name)
                || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE");
    }

    private boolean isUtilityBlock(String name) {
        return Set.of("CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER", "CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "ENCHANTING_TABLE", "BREWING_STAND", "SMITHING_TABLE", "GRINDSTONE", "LOOM", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "STONECUTTER", "COMPOSTER", "LECTERN", "BELL", "BEACON", "RESPAWN_ANCHOR", "CAMPFIRE", "SOUL_CAMPFIRE", "BEEHIVE", "BEE_NEST", "FLOWER_POT", "DECORATED_POT", "JUKEBOX", "CONDUIT", "END_PORTAL_FRAME", "LODESTONE", "SPAWNER").contains(name);
    }

    private String categoryName(String id) {
        return switch (id) {
            case "epitoelemek" -> "Építőelemek"; case "szinesblokkok" -> "Színes blokkok";
            case "termeszetesblokkok" -> "Természetes blokkok"; case "hasznalati" -> "Használati blokkok";
            case "redstone" -> "Redstone blokkok"; case "eszkozok" -> "Eszközök";
            case "harc" -> "Harc"; case "etelek" -> "Ételek és italok";
            case "osszetevok" -> "Összetevők"; default -> "Idézőtojások";
        };
    }

    private String categoryIcon(String id) {
        return switch (id) {
            case "epitoelemek" -> "BRICKS"; case "szinesblokkok" -> "CYAN_WOOL";
            case "termeszetesblokkok" -> "GRASS_BLOCK"; case "hasznalati" -> "CRAFTING_TABLE";
            case "redstone" -> "REDSTONE"; case "eszkozok" -> "DIAMOND_PICKAXE";
            case "harc" -> "DIAMOND_SWORD"; case "etelek" -> "GOLDEN_CARROT";
            case "osszetevok" -> "DIAMOND"; default -> "PIG_SPAWN_EGG";
        };
    }

    private double defaultBasePrice(Material material, String categoryId) {
        return switch (categoryId) {
            case "harc", "eszkozok" -> 10.0;
            case "idezotojasok" -> 1_000_000.0;
            case "hasznalati" -> 8.0;
            case "etelek" -> 2.0;
            case "redstone" -> 3.0;
            default -> material.isBlock() ? 1.0 : 2.0;
        };
    }

    private void createItemCatalog() {
        File catalogFile = new File(getDataFolder(), "catalog/all-items.yml");
        YamlConfiguration catalog = YamlConfiguration.loadConfiguration(catalogFile);
        Set<String> items = new TreeSet<>(catalog.getStringList("items"));
        for (Material material : Material.values()) if (isAllowedShopItem(material)) items.add(material.name());
        catalog.set("description", "A Paper/Minecraft API összes item-id-ja; az auto-categorized.yml követi, melyik id-t sorolta már be a plugin.");
        catalog.set("items", new ArrayList<>(items));
        saveYaml(catalog, catalogFile);
    }

    private void loadPrices() {
        pricesFile = new File(getDataFolder(), "prices.yml"); prices = YamlConfiguration.loadConfiguration(pricesFile);
        if (prices.getConfigurationSection("items") == null) {
            for (File f : Objects.requireNonNullElse(new File(getDataFolder(), "categories").listFiles(), new File[0])) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f); ConfigurationSection sec = y.getConfigurationSection("items");
                if (sec != null) for (String key : sec.getKeys(false)) prices.set("items." + key, sec.getDouble(key + ".base-price", 1));
            }
            saveYaml(prices, pricesFile);
        }
        ConfigurationSection sec = prices.getConfigurationSection("items");
        if (sec != null) for (String key : new ArrayList<>(sec.getKeys(false))) {
            Material material = Material.matchMaterial(key);
            if (material == null || !isAllowedShopItem(material)) { prices.set("items." + key, null); continue; }
            double value = prices.getDouble("items." + key);
            if (material.name().endsWith("_SPAWN_EGG") && value < 1_000_000.0) value = 1_000_000.0;
            if (value >= 0) { basePrices.put(material, value); prices.set("items." + key, value); }
        }
        saveYaml(prices, pricesFile);
    }

    private void loadSales() {
        salesFile = new File(getDataFolder(), "data/sales.yml");
        if (economyStorage != null && economyStorage.isSql()) {
            try {
                Map<String, EconomyStorage.SaleRecord> saved = economyStorage.load();
                for (var entry : saved.entrySet()) {
                    Material material = Material.matchMaterial(entry.getKey());
                    if (material == null) continue;
                    var row = entry.getValue();
                    saleData.put(material, new SaleData(row.pressure(), row.lastSale(), row.totalSold()));
                }
                if (!saved.isEmpty()) return;
            } catch (Exception e) {
                throw new IllegalStateException("Nem sikerült betölteni az economy adatokat az adatbázisból.", e);
            }
        }
        YamlConfiguration oldSales = YamlConfiguration.loadConfiguration(salesFile);
        ConfigurationSection sec = oldSales.getConfigurationSection("items");
        if (sec != null) for (String key : sec.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material != null) saleData.put(material, new SaleData(oldSales.getDouble("items." + key + ".pressure"),
                    oldSales.getLong("items." + key + ".last-sale", System.currentTimeMillis()), oldSales.getLong("items." + key + ".total-sold", 0)));
        }
        // Import existing YAML data the first time the selected SQL database is empty.
        if (economyStorage != null && economyStorage.isSql() && !saleData.isEmpty()) saveSales();
    }

    private Map<String, EconomyStorage.SaleRecord> salesSnapshot() {
        Map<String, EconomyStorage.SaleRecord> snapshot = new LinkedHashMap<>();
        for (var entry : saleData.entrySet()) {
            SaleData data = entry.getValue();
            snapshot.put(entry.getKey().name(), new EconomyStorage.SaleRecord(data.pressure, data.lastSale, data.totalSold));
        }
        return snapshot;
    }

    private void saveSalesAsync() {
        if (economyStorage == null || !economyStorage.isSql()) { saveSales(); return; }
        Map<String, EconomyStorage.SaleRecord> snapshot = salesSnapshot();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { economyStorage.save(snapshot); }
            catch (Exception e) { getLogger().severe("Nem sikerült menteni az economy adatbázisba: " + e.getMessage()); }
        });
    }

    private void saveSales() {
        if (economyStorage != null && economyStorage.isSql()) {
            try { economyStorage.save(salesSnapshot()); }
            catch (Exception e) { getLogger().severe("Nem sikerült menteni az economy adatbázisba: " + e.getMessage()); }
            return;
        }
        if (salesFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : saleData.entrySet()) {
            String path = "items." + entry.getKey().name();
            yaml.set(path + ".pressure", entry.getValue().pressure);
            yaml.set(path + ".last-sale", entry.getValue().lastSale);
            yaml.set(path + ".total-sold", entry.getValue().totalSold);
        }
        saveYaml(yaml, salesFile);
    }

    private void loadCategories() {
        File dir = new File(getDataFolder(), "categories"); File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml")); if (files == null) return;
        Arrays.sort(files, Comparator.comparingInt(file -> {
            String id = file.getName().substring(0, file.getName().length() - 4);
            int index = CATEGORY_ORDER.indexOf(id);
            return index < 0 ? CATEGORY_ORDER.size() : index;
        }));
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f); String id = f.getName().substring(0, f.getName().length()-4); Material icon = Material.matchMaterial(y.getString("icon", "CHEST"));
            Category c = new Category(id, y.getString("name", id), icon == null ? Material.CHEST : icon, new ArrayList<>());
            ConfigurationSection sec = y.getConfigurationSection("items"); if (sec != null) for (String name : new ArrayList<>(sec.getKeys(false))) {
                Material material = Material.matchMaterial(name);
                if (material == null || !isAllowedShopItem(material)) { y.set("items." + name, null); continue; }
                double base = y.getDouble("items." + name + ".base-price", defaultBasePrice(material, id));
                if (material.name().endsWith("_SPAWN_EGG")) base = Math.max(base, 1_000_000.0);
                basePrices.putIfAbsent(material, base);
                if (basePrices.get(material) > 0) c.items.add(material);
                if (material.name().endsWith("_SPAWN_EGG")) y.set("items." + name + ".base-price", base);
            }
            c.items.sort(Comparator.comparingInt((Material material) -> naturalSortGroup(id, material)).thenComparing(Material::name));
            saveYaml(y, f);
            categories.put(id, c);
        }
        for (Material m : basePrices.keySet()) if (prices.get("items." + m.name()) == null) prices.set("items." + m.name(), basePrices.get(m));
        saveYaml(prices, pricesFile);
    }

    private int naturalSortGroup(String category, Material material) {
        if (!category.equals("termeszetesblokkok")) return 0;
        String name = material.name();
        if (Set.of("DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "MUD", "CLAY", "SAND", "RED_SAND", "GRAVEL", "FARMLAND", "MOSS_BLOCK").contains(name)) return 0;
        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.equals("BAMBOO_BLOCK") || name.equals("STRIPPED_BAMBOO_BLOCK")) return 1;
        if (name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || name.equals("MANGROVE_ROOTS") || name.equals("HANGING_ROOTS")) return 2;
        if (name.endsWith("_ORE") || name.startsWith("RAW_") || name.endsWith("_INGOT")) return 4;
        if (Set.of("STONE", "DEEPSLATE", "TUFF", "ANDESITE", "DIORITE", "GRANITE", "CALCITE", "DRIPSTONE_BLOCK", "BASALT", "BLACKSTONE", "NETHERRACK", "END_STONE").contains(name)) return 3;
        if (material.isBlock()) return 5;
        return 6;
    }

    private void openCategories(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, color(getConfig().getString("menus.categories-title", "&8Shop kategóriák")));
        fillBackground(inv);
        fillBottomBar(inv);
        List<Category> visibleCategories = new ArrayList<>(categories.values());
        int rows = (visibleCategories.size() + 4) / 5;
        int firstRow = 1 + Math.max(0, (3 - rows) / 2);
        for (int index = 0; index < visibleCategories.size() && index < 20; index++) {
            int row = index / 5;
            int first = row * 5;
            int count = Math.min(5, visibleCategories.size() - first);
            int column = index - first;
            int left = 1 + (7 - count) / 2;
            Category c = visibleCategories.get(index);
            ItemStack item = named(c.icon, "&e" + c.name, List.of("&7Kattints a kategória megnyitásához", "&8" + c.items.size() + " termék"));
            metaData(item, categoryKey, c.id);
            inv.setItem((firstRow + row) * 9 + left + column, item);
        }
        inv.setItem(31, sellAllButton(p));
        inv.setItem(35, exitButton());
        p.openInventory(inv);
    }
    private static final int ITEMS_PER_PAGE = 36;
    private static final List<String> CATEGORY_ORDER = List.of("epitoelemek", "szinesblokkok", "termeszetesblokkok", "hasznalati", "redstone", "eszkozok", "harc", "etelek", "osszetevok", "idezotojasok");

    private void openCategory(Player p, Category c) { openCategory(p, c, 0); }

    private void openCategory(Player p, Category c, int requestedPage) {
        int pages = Math.max(1, (c.items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        categoryPages.put(p.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(null, 45, color("&8" + c.name));
        fillBackground(inv);
        fillBottomBar(inv);
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(c.items.size(), start + ITEMS_PER_PAGE);
        int pageCount = end - start;
        for (int local = 0; local < pageCount; local++) {
            Material m = c.items.get(start + local);
            ItemStack item = new ItemStack(m);
            ItemMeta meta = item.getItemMeta();
            meta.setLore(itemLore(m).stream().map(this::color).toList());
            item.setItemMeta(meta);
            metaData(item, materialKey, m.name());
            inv.setItem(local, item);
        }
        inv.setItem(36, actionButton(Material.ARROW, "&eVissza", "back"));
        if (page > 0) inv.setItem(38, actionButton(Material.ARROW, "&eElőző oldal", "page-prev"));
        inv.setItem(40, sellAllButton(p));
        if (page + 1 < pages) inv.setItem(42, actionButton(Material.ARROW, "&eKövetkező oldal", "page-next"));
        inv.setItem(44, exitButton());
        p.openInventory(inv);
    }

    private ItemStack actionButton(Material icon, String name, String action) {
        ItemStack button = named(icon, name, List.of());
        metaData(button, actionKey, action);
        return button;
    }
    private void fillBackground(Inventory inv) {
        ItemStack pane = named(Material.WHITE_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inv.getSize(); slot++) inv.setItem(slot, pane.clone());
    }
    private void fillBottomBar(Inventory inv) {
        ItemStack pane = named(Material.CYAN_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = inv.getSize() - 9; slot < inv.getSize(); slot++) inv.setItem(slot, pane.clone());
    }
    private int contentSlot(int index, int total, int firstInventoryRow) {
        int row = index / 7;
        int firstIndex = row * 7;
        int countInRow = Math.min(7, total - firstIndex);
        int column = index - firstIndex;
        int left = 1 + (7 - countInRow) / 2;
        return (firstInventoryRow + row) * 9 + left + column;
    }
    private ItemStack exitButton() {
        return actionButton(Material.RED_STAINED_GLASS_PANE, "&c✖ Kilépés", "close");
    }
    private ItemStack sellAllButton(Player p) {
        ItemStack button = named(Material.HOPPER, "&bMinden eladása", List.of(
                "&7Eladja az inventorydban lévő összes", "&7beállított terméket.", "", "&eBecsült összeg: " + money(estimateSellAll(p)), "&aKattints az eladáshoz"));
        metaData(button, actionKey, "sell-all");
        return button;
    }
    private double estimateSellAll(Player p) {
        double total = 0;
        for (ItemStack item : p.getInventory().getStorageContents())
            if (item != null && isAllowedShopItem(item.getType()) && basePrices.containsKey(item.getType())) total += currentPrice(item.getType()) * item.getAmount();
        return total;
    }
    private List<String> itemLore(Material m) {
        double base=basePrices.getOrDefault(m,0.0), current=currentPrice(m); SaleData s=getSale(m); double sold=effectivePressure(m);
        String demand = sold >= 5000 ? "&cNagyon magas kínálat" : sold >= 1000 ? "&6Magas kínálat" : sold >= 100 ? "&eKözepes kínálat" : "&aAlacsony kínálat";
        return List.of("&7Aktuális érték: &e"+money(current), "&7Alapérték: &f"+money(base), "&7Értékváltozás: "+change(current,base), "&7Eladott mennyiség: &f"+s.totalSold+" db", "&7Kereslet: "+demand, "", "&aBal klikk: vásárlás (1)", "&aShift + bal klikk: vásárlás (stack)", "&cJobb klikk: eladás (1)", "&cShift + jobb klikk: összes eladása");
    }
    private void metaData(ItemStack item, NamespacedKey key, String value) { ItemMeta meta=item.getItemMeta(); meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,value); item.setItemMeta(meta); }
    private ItemStack named(Material m,String name,List<String> lore) { ItemStack i=new ItemStack(m); ItemMeta meta=i.getItemMeta(); meta.setDisplayName(color(name)); meta.setLore(lore.stream().map(this::color).toList()); i.setItemMeta(meta); return i; }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle(); if (!title.equals(color(getConfig().getString("menus.categories-title", "&8Shop kategóriák"))) && !categories.values().stream().anyMatch(c -> title.equals(color("&8"+c.name)))) return;
        e.setCancelled(true); if (e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory()) return;
        ItemStack clicked=e.getCurrentItem(); if (clicked==null || !clicked.hasItemMeta()) return; ItemMeta meta=clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if ("close".equals(action)) { p.closeInventory(); return; }
        if ("back".equals(action)) { openCategories(p); return; }
        if ("page-prev".equals(action) || "page-next".equals(action)) {
            Category current = categories.values().stream().filter(c -> title.equals(color("&8" + c.name))).findFirst().orElse(null);
            if (current != null) {
                int page = categoryPages.getOrDefault(p.getUniqueId(), 0) + ("page-next".equals(action) ? 1 : -1);
                openCategory(p, current, page);
            }
            return;
        }
        if ("sell-all".equals(action)) {
            String previousTitle = title;
            sellAll(p);
            Bukkit.getScheduler().runTask(this, () -> {
                if (previousTitle.equals(color(getConfig().getString("menus.categories-title", "&8Shop kategóriák")))) openCategories(p);
                else categories.values().stream().filter(c -> previousTitle.equals(color("&8" + c.name))).findFirst().ifPresent(c -> openCategory(p, c, categoryPages.getOrDefault(p.getUniqueId(), 0)));
            });
            return;
        }
        String cat=meta.getPersistentDataContainer().get(categoryKey,PersistentDataType.STRING); if(cat!=null){ Category c=categories.get(cat); if(c!=null)openCategory(p,c); return; }
        String material=meta.getPersistentDataContainer().get(materialKey,PersistentDataType.STRING); if(material==null)return;
        Material m=Material.matchMaterial(material); if(m==null)return;
        boolean shift=e.isShiftClick(); int count=shift?m.getMaxStackSize():1;
        if(e.isLeftClick()) buy(p,m,count); else if(e.isRightClick()) sell(p,m,shift?Integer.MAX_VALUE:1);
        Bukkit.getScheduler().runTask(this,()->{Category c=categories.values().stream().filter(x->x.items.contains(m)).findFirst().orElse(null); if(c!=null && p.getOpenInventory().getTopInventory().getSize()==45) openCategory(p,c,categoryPages.getOrDefault(p.getUniqueId(), 0));});
    }
    @EventHandler public void onPlayerInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) Bukkit.getScheduler().runTask(this, () -> applyInventoryPriceLore(p));
    }
    @EventHandler public void onPlayerJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(this, () -> applyInventoryPriceLore(e.getPlayer()));
    }
    @EventHandler public void onPlayerInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(this, () -> applyInventoryPriceLore(p));
    }
    private void applyInventoryPriceLore(Player p) {
        for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            ItemStack item = p.getInventory().getItem(slot);
            if (item == null || !basePrices.containsKey(item.getType())) continue;
            ItemMeta meta = item.getItemMeta(); if (meta == null) continue;
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            for (int i = 0; i < lore.size();) {
                if (lore.get(i).startsWith(ChatColor.DARK_GRAY + "[MineShop]")) {
                    int start = i > 0 && lore.get(i - 1).isEmpty() ? i - 1 : i;
                    int end = Math.min(lore.size(), i + 4); lore.subList(start, end).clear();
                } else i++;
            }
            double base = basePrices.get(item.getType()), current = currentPrice(item.getType());
            SaleData data = getSale(item.getType());
            lore.add(ChatColor.DARK_GRAY + "[MineShop]");
            lore.add(color("&7Aktuális érték: &e" + money(current)));
            lore.add(color("&7Alapérték: &f" + money(base)));
            lore.add(color("&7Változás: " + change(current, base) + " &8| &7Eladva: &f" + data.totalSold));
            meta.setLore(lore); item.setItemMeta(meta);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e) { if(categories.values().stream().anyMatch(c->e.getView().getTitle().equals(color("&8"+c.name))) || e.getView().getTitle().equals(color(getConfig().getString("menus.categories-title", "&8Shop kategóriák")))) e.setCancelled(true); }
    private void buy(Player p,Material m,int requested) {
        double unit=currentPrice(m); if(unit<=0){p.sendMessage(color("&cEz a tárgy jelenleg nem vásárolható."));return;}
        int count=Math.min(requested,(int)Math.floor(economy.getBalance(p)/unit)); count=Math.min(count,inventoryCapacity(p,m)); if(count<=0){p.sendMessage(color("&cNincs elég pénzed vagy helyed."));return;}
        double total=unit*count; if(!economy.withdrawPlayer(p,total).transactionSuccess()){p.sendMessage(color("&cA fizetés nem sikerült."));return;}
        Map<Integer,ItemStack> overflow=p.getInventory().addItem(new ItemStack(m,count)); overflow.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i)); p.sendMessage(color("&aVásároltál &f"+count+"x "+pretty(m)+" &aitemet &e"+money(total)+"&a összegért."));
    }
    private void sell(Player p,Material m,int requested) {
        double unit=currentPrice(m); if(unit<=0){p.sendMessage(color("&cEz a tárgy jelenleg nem adható el."));return;}
        int owned=0; for(ItemStack i:p.getInventory().getStorageContents())if(i!=null&&i.getType()==m)owned+=i.getAmount(); int count=Math.min(owned,requested); if(count<=0){p.sendMessage(color("&cNincs nálad eladható "+pretty(m)+"."));return;}
        int left=count; for(int slot=0;slot< p.getInventory().getStorageContents().length && left>0;slot++){ItemStack i=p.getInventory().getItem(slot);if(i!=null&&i.getType()==m){int take=Math.min(left,i.getAmount());i.setAmount(i.getAmount()-take);left-=take;if(i.getAmount()<=0)p.getInventory().setItem(slot,null);}}
        double total=unit*count; economy.depositPlayer(p,total); addSales(m,count); p.sendMessage(color("&aEladtál &f"+count+"x "+pretty(m)+" &aitemet &e"+money(total)+"&a összegért.")); saveSalesAsync();
    }
    private void sellAll(Player p) {
        Map<Material, Integer> amounts = new EnumMap<>(Material.class);
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item == null || !isAllowedShopItem(item.getType()) || !basePrices.containsKey(item.getType()) || currentPrice(item.getType()) <= 0) continue;
            amounts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        if (amounts.isEmpty()) { p.sendMessage(color("&cNincs nálad eladható termék.")); return; }
        Map<Material, Integer> soldAmounts = new EnumMap<>(amounts);
        int soldCount = soldAmounts.values().stream().mapToInt(Integer::intValue).sum();
        double total = soldAmounts.entrySet().stream().mapToDouble(e -> currentPrice(e.getKey()) * e.getValue()).sum();
        var transaction = economy.depositPlayer(p, total);
        if (!transaction.transactionSuccess()) { p.sendMessage(color("&cA fizetés nem sikerült.")); return; }
        for (int slot = 0; slot < p.getInventory().getStorageContents().length; slot++) {
            ItemStack item = p.getInventory().getItem(slot);
            if (item == null || !amounts.containsKey(item.getType())) continue;
            Material material = item.getType();
            if (item.getAmount() <= amounts.get(material)) {
                amounts.put(material, amounts.get(material) - item.getAmount());
                p.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - amounts.get(material));
                amounts.put(material, 0);
            }
        }
        soldAmounts.forEach(this::addSales);
        p.sendMessage(color("&aEladtál &f" + soldCount + " db &abeállított terméket &e" + money(total) + "&a összegért."));
        saveSalesAsync();
    }
    private int inventoryCapacity(Player p,Material m){int free=0;for(ItemStack i:p.getInventory().getStorageContents()){if(i==null)free+=m.getMaxStackSize();else if(i.getType()==m)free+=m.getMaxStackSize()-i.getAmount();}return free;}
    private void addSales(Material m,int count){SaleData s=getSale(m);s.pressure+=count;s.totalSold+=count;s.lastSale=System.currentTimeMillis();}
    private SaleData getSale(Material m){return saleData.computeIfAbsent(m,k->new SaleData(0,System.currentTimeMillis(),0));}
    private double effectivePressure(Material m){SaleData s=getSale(m);double halfLife=Math.max(1,getConfig().getDouble("economy.recovery-half-life-hours",168));double elapsed=(System.currentTimeMillis()-s.lastSale)/3600000.0;return s.pressure*Math.pow(0.5,elapsed/halfLife);}
    private double currentPrice(Material m){double base=basePrices.getOrDefault(m,0.0); if(base<=0)return 0; double scale=Math.max(1,getConfig().getDouble("economy.sales-scale",500)); double factor=1.0/(1.0+effectivePressure(m)/scale); factor=Math.max(getConfig().getDouble("economy.minimum-multiplier",0.2),Math.min(getConfig().getDouble("economy.maximum-multiplier",1.5),factor)); double price = base * factor;
        if (m.name().endsWith("_SPAWN_EGG")) price = Math.max(1_000_000.0, price);
        return price;}
    private String change(double current,double base){double pct=base<=0?0:(current/base-1)*100;return color((pct>=0?"&a+":"&c")+String.format(Locale.US,"%.1f%%",pct));}
    private String money(double v){return String.format(Locale.US,"%.2f",v)+getConfig().getString("economy.currency-symbol","$");}
    private String pretty(Material m){return m.name().toLowerCase(Locale.ROOT).replace('_',' ');}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private int nextSlot(int s){int n=s+1;return n%9==8?n+2:n;}
    private void saveYaml(YamlConfiguration y,File f){try{File parent=f.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();y.save(f);}catch(IOException e){getLogger().warning("Nem sikerült elmenteni: "+f.getPath());}}
    private record Category(String id,String name,Material icon,List<Material> items){}
    private static final class SaleData { double pressure; long lastSale; long totalSold; SaleData(double pressure,long lastSale,long totalSold){this.pressure=pressure;this.lastSale=lastSale;this.totalSold=totalSold;} }
}
