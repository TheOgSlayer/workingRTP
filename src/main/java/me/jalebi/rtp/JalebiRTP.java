package me.jalebi.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class JalebiRTP extends JavaPlugin implements CommandExecutor, Listener {

    private enum Dim {
        OVERWORLD("overworld", World.Environment.NORMAL),
        NETHER("nether", World.Environment.NETHER),
        END("end", World.Environment.THE_END);

        final String key;
        final World.Environment env;

        Dim(String key, World.Environment env) {
            this.key = key;
            this.env = env;
        }
    }

    /** Marks our GUI so we can recognise it safely (no title matching). */
    private static class RtpHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("rtp").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("JalebiRTP enabled.");
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("jalebirtp.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("players-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("jalebirtp.use")) {
            player.sendMessage(msg("no-permission"));
            return true;
        }
        openGui(player);
        return true;
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private void openGui(Player player) {
        ConfigurationSection gui = getConfig().getConfigurationSection("gui");
        int rows = Math.max(1, Math.min(6, getConfig().getInt("gui.rows", 3)));
        Inventory inv = Bukkit.createInventory(new RtpHolder(), rows * 9, color(getConfig().getString("gui.title", "&6&lJalebiRTP")));

        if (getConfig().getBoolean("gui.filler", true)) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            if (fm != null) {
                fm.setDisplayName(" ");
                filler.setItemMeta(fm);
            }
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        for (Dim dim : Dim.values()) {
            int slot = getConfig().getInt("gui." + dim.key + ".slot", 13);
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, buildItem(dim));
        }

        player.openInventory(inv);
    }

    private ItemStack buildItem(Dim dim) {
        String base = "gui." + dim.key + ".";
        Material mat = Material.matchMaterial(getConfig().getString(base + "material", "GRASS_BLOCK"));
        if (mat == null) mat = Material.GRASS_BLOCK;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString(base + "name", dim.key)));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList(base + "lore")) lore.add(color(line));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RtpHolder)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof RtpHolder)) return;

        Player player = (Player) e.getWhoClicked();
        int slot = e.getSlot();

        for (Dim dim : Dim.values()) {
            if (slot == getConfig().getInt("gui." + dim.key + ".slot", -1)) {
                player.closeInventory();
                rtp(player, dim);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RtpHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Keep cooldown on quit so relogging can't bypass it.
    }

    // ------------------------------------------------------------------
    // Teleport logic (NO safe-spot search)
    // ------------------------------------------------------------------

    private void rtp(Player player, Dim dim) {
        if (!player.hasPermission("jalebirtp.bypass")) {
            long cooldownMs = getConfig().getLong("cooldown-seconds", 120) * 1000L;
            Long last = cooldowns.get(player.getUniqueId());
            if (last != null) {
                long remaining = cooldownMs - (System.currentTimeMillis() - last);
                if (remaining > 0) {
                    player.sendMessage(msg("cooldown").replace("{time}", formatTime(remaining)));
                    return;
                }
            }
        }

        World world = resolveWorld(dim);
        if (world == null) {
            // Practically impossible (server always has a world), but never throw.
            world = player.getWorld();
        }

        String base = "dimensions." + dim.key + ".";
        int radius = Math.max(0, getConfig().getInt(base + "radius", 5000));
        int cx = getConfig().getInt(base + "center-x", 0);
        int cz = getConfig().getInt(base + "center-z", 0);
        int fixedY = getConfig().getInt(base + "y", -1);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x = cx + (radius == 0 ? 0 : rnd.nextInt(-radius, radius + 1));
        int z = cz + (radius == 0 ? 0 : rnd.nextInt(-radius, radius + 1));

        int y;
        if (fixedY == -1) {
            y = world.getHighestBlockYAt(x, z) + 1; // loads the chunk
        } else {
            y = fixedY;
        }
        // Clamp into the world's valid height so the teleport can never be rejected.
        y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, y));

        Location target = new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
        world.getChunkAt(target); // make sure it's loaded

        boolean ok = player.teleport(target);
        if (!ok) {
            // Another plugin cancelled it; try once more.
            ok = player.teleport(target);
        }

        if (ok) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(msg("teleported"));
        }
    }

    /** Finds the configured world; falls back to any world of the same dimension type. */
    private World resolveWorld(Dim dim) {
        String name = getConfig().getString("dimensions." + dim.key + ".world", "");
        World w = Bukkit.getWorld(name);
        if (w != null) return w;

        for (World candidate : Bukkit.getWorlds()) {
            if (candidate.getEnvironment() == dim.env) return candidate;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static String formatTime(long ms) {
        long totalSec = (ms + 999) / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }
}
