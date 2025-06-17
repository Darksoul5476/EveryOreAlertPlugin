package com.dark;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EveryOreAlert extends JavaPlugin implements Listener {

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS
    );

    // For total ores mined tracking
    private File oreTotalsFile;
    private YamlConfiguration oreTotalsConfig;
    // UUID -> (Material name -> total mined)
    private final Map<UUID, Map<Material, Integer>> oreTotals = new HashMap<>();

    // Group management
    private File groupsFile;
    private YamlConfiguration groupsConfig;
    private Map<String, Set<String>> groups = new HashMap<>();

    // For streak tracking: UUID -> current streak ore type and count
    private final Map<UUID, Material> lastOreType = new HashMap<>();
    private final Map<UUID, Integer> oreStreak = new HashMap<>();

    // --- Custom alert block management ---
    private File alertBlocksFile;
    private YamlConfiguration alertBlocksConfig;
    private final Set<Material> alertBlocks = new HashSet<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        loadGroups();
        loadOreTotals();
        loadAlertBlocks();
        getLogger().info("EveryOreAlert enabled!");
    }

    @Override
    public void onDisable() {
        saveGroups();
        saveOreTotals();
        saveAlertBlocks();
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // --- Ores logic (unchanged) ---
        if (ORES.contains(block.getType())) {
            UUID uuid = player.getUniqueId();
            Material oreType = block.getType();

            // Handle streak logic
            Material lastType = lastOreType.get(uuid);
            int streak;
            if (lastType != null && lastType.equals(oreType)) {
                streak = oreStreak.getOrDefault(uuid, 0) + 1;
            } else {
                streak = 1;
            }
            lastOreType.put(uuid, oreType);
            oreStreak.put(uuid, streak);

            // Update and save total
            int total = addOreTotal(uuid, oreType, 1);
            String oreName = oreType.toString().toLowerCase().replace('_', ' ');
            String message = (streak == 1)
                    ? String.format("§b[ALERT] §e%s has found 1 %s! (Total: %d %s mined)", player.getName(), oreName, total, oreName)
                    : String.format("§b[ALERT] §e%s has found %dx %s! (Total: %d %s mined)", player.getName(), streak, oreName, total, oreName);

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp()) {
                    p.sendMessage(message);
                }
            }
        }

        // --- Custom alert block logic (OPs only) ---
        if (alertBlocks.contains(block.getType())) {
            String blockName = block.getType().toString().toLowerCase().replace('_', ' ');
            String alertMsg = String.format("§d[BLOCK ALERT] §e%s broke %s", player.getName(), blockName);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp()) {
                    p.sendMessage(alertMsg);
                }
            }
        }
    }

    // Adds to total and saves, returns new total
    private int addOreTotal(UUID uuid, Material ore, int amount) {
        oreTotals.putIfAbsent(uuid, new HashMap<>());
        Map<Material, Integer> playerTotals = oreTotals.get(uuid);
        int newTotal = playerTotals.getOrDefault(ore, 0) + amount;
        playerTotals.put(ore, newTotal);
        saveOreTotals();
        return newTotal;
    }

    // --- Ore totals persistence methods ---
    private void loadOreTotals() {
        oreTotalsFile = new File(getDataFolder(), "ore_totals.yml");
        if (!oreTotalsFile.exists()) {
            oreTotalsConfig = new YamlConfiguration();
            return;
        }
        oreTotalsConfig = YamlConfiguration.loadConfiguration(oreTotalsFile);
        for (String uuidStr : oreTotalsConfig.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Map<Material, Integer> playerTotals = new HashMap<>();
            for (String matName : oreTotalsConfig.getConfigurationSection(uuidStr).getKeys(false)) {
                try {
                    Material mat = Material.valueOf(matName);
                    int amt = oreTotalsConfig.getInt(uuidStr + "." + matName, 0);
                    playerTotals.put(mat, amt);
                } catch (IllegalArgumentException ignored) {}
            }
            oreTotals.put(uuid, playerTotals);
        }
    }

    private void saveOreTotals() {
        if (oreTotalsConfig == null) oreTotalsConfig = new YamlConfiguration();
        // Clear all keys
        for (String key : new HashSet<>(oreTotalsConfig.getKeys(false))) {
            oreTotalsConfig.set(key, null);
        }
        // Save current totals
        for (Map.Entry<UUID, Map<Material, Integer>> entry : oreTotals.entrySet()) {
            String uuidStr = entry.getKey().toString();
            for (Map.Entry<Material, Integer> oreEntry : entry.getValue().entrySet()) {
                oreTotalsConfig.set(uuidStr + "." + oreEntry.getKey().name(), oreEntry.getValue());
            }
        }
        try {
            oreTotalsConfig.save(oreTotalsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save ore_totals.yml: " + e.getMessage());
        }
    }

    // --- Group persistence methods ---
    private void loadGroups() {
        groupsFile = new File(getDataFolder(), "groups.yml");
        if (!groupsFile.exists()) {
            groupsConfig = new YamlConfiguration();
            return;
        }
        groupsConfig = YamlConfiguration.loadConfiguration(groupsFile);
        for (String key : groupsConfig.getKeys(false)) {
            List<String> members = groupsConfig.getStringList(key);
            groups.put(key, new HashSet<>(members));
        }
    }

    private void saveGroups() {
        if (groupsConfig == null) groupsConfig = new YamlConfiguration();
        // Clear all keys
        for (String key : new HashSet<>(groupsConfig.getKeys(false))) {
            groupsConfig.set(key, null);
        }
        // Save current groups
        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            groupsConfig.set(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        try {
            groupsConfig.save(groupsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save groups.yml: " + e.getMessage());
        }
    }

    // --- Custom alert block persistence methods ---
    private void loadAlertBlocks() {
        alertBlocksFile = new File(getDataFolder(), "alert_blocks.yml");
        alertBlocksConfig = YamlConfiguration.loadConfiguration(alertBlocksFile);
        alertBlocks.clear();
        List<String> blockNames = alertBlocksConfig.getStringList("blocks");
        for (String name : blockNames) {
            try {
                alertBlocks.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveAlertBlocks() {
        if (alertBlocksConfig == null) alertBlocksConfig = new YamlConfiguration();
        List<String> blockNames = new ArrayList<>();
        for (Material mat : alertBlocks) {
            blockNames.add(mat.name());
        }
        alertBlocksConfig.set("blocks", blockNames);
        try {
            alertBlocksConfig.save(alertBlocksFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save alert_blocks.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        // Utility commands: /fly, /disfly, /heal, /feed
        if (name.equals("fly")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            if (!player.isOp() && !player.hasPermission("everyorealert.utility")) {
                player.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }
            player.setAllowFlight(true);
            player.setFlying(true);
            player.sendMessage("§aFlight enabled!");
            return true;
        }
        if (name.equals("disfly")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            if (!player.isOp() && !player.hasPermission("everyorealert.utility")) {
                player.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage("§cFlight disabled!");
            return true;
        }
        if (name.equals("heal")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            if (!player.isOp() && !player.hasPermission("everyorealert.utility")) {
                player.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }
            player.setHealth(player.getMaxHealth());
            player.setFireTicks(0);
            player.sendMessage("§aYou have been healed!");
            return true;
        }
        if (name.equals("feed")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            if (!player.isOp() && !player.hasPermission("everyorealert.utility")) {
                player.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.sendMessage("§aYou have been fed!");
            return true;
        }

        // --- Custom alert block commands ---
        if (name.equals("alert") && args.length >= 1 && args[0].equalsIgnoreCase("block")) {
            if (!sender.hasPermission("everyorealert.alert")) {
                sender.sendMessage("§cYou do not have permission to use this command.");
                return true;
            }
            if (args.length == 1 || (args.length >= 2 && args[1].equalsIgnoreCase("list"))) {
                if (alertBlocks.isEmpty()) {
                    sender.sendMessage("§7No alert blocks set.");
                } else {
                    sender.sendMessage("§bAlert blocks: §f" + String.join(", ",
                            alertBlocks.stream().map(Material::name).toList()));
                }
                return true;
            }
            if (args.length >= 3) {
                String action = args[1].toLowerCase();
                String blockName = args[2].toUpperCase();
                try {
                    Material mat = Material.valueOf(blockName);
                    if (action.equals("add")) {
                        if (alertBlocks.add(mat)) {
                            saveAlertBlocks();
                            sender.sendMessage("§aAdded §e" + blockName + " §ato alert blocks.");
                        } else {
                            sender.sendMessage("§e" + blockName + " §cis already in alert blocks.");
                        }
                        return true;
                    } else if (action.equals("remove")) {
                        if (alertBlocks.remove(mat)) {
                            saveAlertBlocks();
                            sender.sendMessage("§aRemoved §e" + blockName + " §afrom alert blocks.");
                        } else {
                            sender.sendMessage("§e" + blockName + " §cis not in alert blocks.");
                        }
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cUnknown block type: " + blockName);
                    return true;
                }
            }
            sender.sendMessage("§cUsage: /alert block [add|remove|list] <block>");
            return true;
        }

        // Your existing /alert command and subcommands below...
        if (!name.equals("alert")) return false;

        if (!sender.hasPermission("everyorealert.alert")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cUsage: //alert <warn|ban|unban|kick|add group|delete group|msg|group join|group leave> ...");
            return true;
        }

        String sub = args[0].toLowerCase();

        // warn, ban, unban, kick
        if (sub.equals("warn") || sub.equals("ban") || sub.equals("unban") || sub.equals("kick")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: //alert " + sub + " <player> <reason>");
                return true;
            }
            String targetName = args[1];
            String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            Player target = Bukkit.getPlayerExact(targetName);

            String alertMsg = "§c[ALERT] §e" + sender.getName() + " issued " + sub.toUpperCase() + " to " + targetName + ": " + reason;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp()) p.sendMessage(alertMsg);
            }

            switch (sub) {
                case "warn" -> {
                    if (target != null) target.sendMessage("§eYou have been warned: " + reason);
                    sender.sendMessage("§aWarned " + targetName + ".");
                }
                case "ban" -> {
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(targetName, reason, null, sender.getName());
                    if (target != null) target.kickPlayer("§cYou have been banned: " + reason);
                    sender.sendMessage("§aBanned " + targetName + ".");
                }
                case "unban" -> {
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(targetName);
                    sender.sendMessage("§aUnbanned " + targetName + ".");
                }
                case "kick" -> {
                    if (target != null) {
                        target.kickPlayer("§cYou have been kicked: " + reason);
                        sender.sendMessage("§aKicked " + targetName + ".");
                    } else {
                        sender.sendMessage("§cPlayer not found.");
                    }
                }
            }
            return true;
        }

        // add group <group>
        if (sub.equals("add") && args.length >= 3 && args[1].equalsIgnoreCase("group")) {
            String group = args[2].toLowerCase();
            if (groups.containsKey(group)) {
                sender.sendMessage("§cGroup '" + group + "' already exists.");
            } else {
                groups.put(group, new HashSet<>());
                saveGroups();
                sender.sendMessage("§aGroup '" + group + "' added.");
            }
            return true;
        }

        // delete group <group>
        if (sub.equals("delete") && args.length >= 3 && args[1].equalsIgnoreCase("group")) {
            String group = args[2].toLowerCase();
            if (!groups.containsKey(group)) {
                sender.sendMessage("§cGroup '" + group + "' does not exist.");
            } else {
                groups.remove(group);
                saveGroups();
                sender.sendMessage("§aGroup '" + group + "' deleted.");
            }
            return true;
        }

        // msg <group> <msg>
        if (sub.equals("msg") && args.length >= 3) {
            String group = args[1].toLowerCase();
            if (!groups.containsKey(group)) {
                sender.sendMessage("§cGroup '" + group + "' does not exist.");
                return true;
            }
            String msg = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            Set<String> members = groups.get(group);
            int sent = 0;
            for (String name2 : members) {
                Player p = Bukkit.getPlayerExact(name2);
                if (p != null && p.isOnline()) {
                    p.sendMessage("§b[Group: " + group + "] §f" + msg);
                    sent++;
                }
            }
            sender.sendMessage("§aMessage sent to " + sent + " player(s) in group '" + group + "'.");
            return true;
        }

        // group join <group>
        if (sub.equals("group") && args.length >= 3 && args[1].equalsIgnoreCase("join")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can join groups.");
                return true;
            }
            String group = args[2].toLowerCase();
            if (!groups.containsKey(group)) {
                sender.sendMessage("§cGroup '" + group + "' does not exist.");
                return true;
            }
            Set<String> members = groups.get(group);
            if (members.contains(player.getName())) {
                sender.sendMessage("§cYou are already in group '" + group + "'.");
                return true;
            }
            members.add(player.getName());
            saveGroups();
            sender.sendMessage("§aYou have joined group '" + group + "'.");
            return true;
        }

        // group leave <group>
        if (sub.equals("group") && args.length >= 3 && args[1].equalsIgnoreCase("leave")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can leave groups.");
                return true;
            }
            String group = args[2].toLowerCase();
            if (!groups.containsKey(group)) {
                sender.sendMessage("§cGroup '" + group + "' does not exist.");
                return true;
            }
            Set<String> members = groups.get(group);
            if (!members.contains(player.getName())) {
                sender.sendMessage("§cYou are not in group '" + group + "'.");
                return true;
            }
            members.remove(player.getName());
            saveGroups();
            sender.sendMessage("§aYou have left group '" + group + "'.");
            return true;
        }

        return false;
    }
}
