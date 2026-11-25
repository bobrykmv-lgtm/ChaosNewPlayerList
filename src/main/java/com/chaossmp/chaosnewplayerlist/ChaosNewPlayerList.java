package com.chaossmp.chaosnewplayerlist;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public final class ChaosNewPlayerList extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private File playersFile;
    private FileConfiguration playersConfig;

    private String guiTitle;
    private int guiRows;
    private long openDelayTicks;
    private int closeButtonSlot;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        loadPlayersFile();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("newplayers") != null) {
            getCommand("newplayers").setExecutor(this);
            getCommand("newplayers").setTabCompleter(this);
        }

        getLogger().info("ChaosNewPlayerList включён! Игроков в базе: " + getPlayerCount());
    }

    @Override
    public void onDisable() {
        savePlayersFile();
        getLogger().info("ChaosNewPlayerList выключен.");
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.guiTitle = colorize(getConfig().getString("welcome-gui.title", "&6&lЗдраствуй, новый игрок!"));
        this.guiRows = Math.max(3, Math.min(6, getConfig().getInt("welcome-gui.rows", 4)));
        this.openDelayTicks = Math.max(1L, getConfig().getLong("welcome-gui.open-delay-ticks", 20L));
        this.closeButtonSlot = Math.max(0, Math.min(guiRows * 9 - 1, getConfig().getInt("welcome-gui.close-button.slot", 31)));
    }

    private void loadPlayersFile() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку плагина.");
        }
        this.playersFile = new File(getDataFolder(), "players.yml");
        if (!this.playersFile.exists()) {
            try {
                if (!this.playersFile.createNewFile()) {
                    getLogger().warning("Не удалось создать players.yml");
                }
            } catch (IOException e) {
                getLogger().severe("Ошибка создания players.yml: " + e.getMessage());
            }
        }
        this.playersConfig = YamlConfiguration.loadConfiguration(this.playersFile);
    }

    private void savePlayersFile() {
        if (playersConfig == null || playersFile == null) return;
        try {
            playersConfig.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("Ошибка сохранения players.yml: " + e.getMessage());
        }
    }

    private int getPlayerCount() {
        if (playersConfig == null || !playersConfig.isConfigurationSection("players")) {
            return 0;
        }
        return playersConfig.getConfigurationSection("players").getKeys(false).size();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuidStr = player.getUniqueId().toString();
        String basePath = "players." + uuidStr;

        boolean isFirstJoin = !playersConfig.contains(basePath);
        long now = System.currentTimeMillis();

        playersConfig.set(basePath + ".name", player.getName());
        playersConfig.set(basePath + ".last-seen", now);

        if (isFirstJoin) {
            playersConfig.set(basePath + ".first-join", now);
            int orderNumber = getPlayerCount();
            playersConfig.set(basePath + ".number", orderNumber);
            savePlayersFile();

            // Открываем окно приветствия с небольшой задержкой после прогрузки мира
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    openWelcomeGui(player, orderNumber);
                }
            }, openDelayTicks);
        } else {
            savePlayersFile();
        }
    }

    public void openWelcomeGui(Player player, int playerNumber) {
        int size = guiRows * 9;
        Inventory inv = Bukkit.createInventory(new WelcomeGuiHolder(), size, guiTitle);

        // Заполнение фона стеклом
        Material fillerMat = parseMaterial(getConfig().getString("welcome-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMat, " ", List.of());
        for (int i = 0; i < size; i++) {
            inv.setItem(i, filler);
        }

        // Информационные карточки из конфига
        ConfigurationSection itemsSec = getConfig().getConfigurationSection("welcome-gui.info-items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                ConfigurationSection sec = itemsSec.getConfigurationSection(key);
                if (sec == null) continue;

                int slot = sec.getInt("slot", -1);
                if (slot < 0 || slot >= size) continue;

                Material mat = parseMaterial(sec.getString("material", "PAPER"), Material.PAPER);
                String name = applyPlaceholders(sec.getString("name", "&eИнформация"), player, playerNumber);
                List<String> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(applyPlaceholders(line, player, playerNumber));
                }

                inv.setItem(slot, createItem(mat, name, lore));
            }
        }

        // Кнопка "Закрыть"
        Material closeMat = parseMaterial(getConfig().getString("welcome-gui.close-button.material", "BARRIER"), Material.BARRIER);
        String closeName = colorize(getConfig().getString("welcome-gui.close-button.name", "&c&l✖ Закрыть"));
        List<String> closeLore = new ArrayList<>();
        for (String line : getConfig().getStringList("welcome-gui.close-button.lore")) {
            closeLore.add(colorize(line));
        }
        inv.setItem(closeButtonSlot, createItem(closeMat, closeName, closeLore));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.1f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WelcomeGuiHolder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getRawSlot() == closeButtonSlot) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WelcomeGuiHolder) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("preview")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Эту команду может выполнить только игрок.");
                return true;
            }
            int num = playersConfig.getInt("players." + player.getUniqueId() + ".number", getPlayerCount() + 1);
            openWelcomeGui(player, num);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chaosnewplayerlist.admin") && !sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "Нет прав.");
                return true;
            }
            loadPluginConfig();
            loadPlayersFile();
            sender.sendMessage(ChatColor.GREEN + "[ChaosNewPlayerList] Конфиг перезагружен.");
            return true;
        }

        if (playersConfig == null || !playersConfig.isConfigurationSection("players")) {
            sender.sendMessage(ChatColor.YELLOW + "Список новых игроков пока пуст.");
            return true;
        }

        List<PlayerEntry> entries = new ArrayList<>();
        for (String uuid : playersConfig.getConfigurationSection("players").getKeys(false)) {
            String path = "players." + uuid;
            String name = playersConfig.getString(path + ".name", "Unknown");
            long firstJoin = playersConfig.getLong(path + ".first-join", 0L);
            int number = playersConfig.getInt(path + ".number", 0);
            entries.add(new PlayerEntry(number, name, firstJoin));
        }

        entries.sort(Comparator.comparingLong(PlayerEntry::firstJoin).reversed());

        sender.sendMessage(ChatColor.GOLD + "=== Новые игроки сервера (" + entries.size() + ") ===");
        int limit = Math.min(15, entries.size());
        for (int i = 0; i < limit; i++) {
            PlayerEntry entry = entries.get(i);
            String dateStr = entry.firstJoin() > 0 ? dateFormat.format(new Date(entry.firstJoin())) : "—";
            sender.sendMessage(ChatColor.YELLOW + "#" + entry.number() + " "
                    + ChatColor.WHITE + entry.name()
                    + ChatColor.GRAY + " (" + dateStr + ")");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("chaosnewplayerlist.admin") || sender.isOp()) {
                return List.of("preview", "reload");
            }
            return List.of("preview");
        }
        return List.of();
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material mat = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return mat != null ? mat : fallback;
    }

    private String applyPlaceholders(String text, Player player, int number) {
        return colorize(text)
                .replace("%player%", player.getName())
                .replace("%number%", String.valueOf(number));
    }

    private String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private record PlayerEntry(int number, String name, long firstJoin) {}

    private static final class WelcomeGuiHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
