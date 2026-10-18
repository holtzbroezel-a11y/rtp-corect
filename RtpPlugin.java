package dev.rtpplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class RtpPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    /** Die drei Ziele der GUI. */
    enum Dim {
        OVERWORLD("overworld", "Overworld", World.Environment.NORMAL, Material.GRASS_BLOCK, NamedTextColor.GREEN, 11),
        NETHER("nether", "Nether", World.Environment.NETHER, Material.NETHERRACK, NamedTextColor.RED, 13),
        END("end", "End", World.Environment.THE_END, Material.END_STONE, NamedTextColor.YELLOW, 15);

        final String key;
        final String label;
        final World.Environment env;
        final Material icon;
        final NamedTextColor color;
        final int slot;

        Dim(String key, String label, World.Environment env, Material icon, NamedTextColor color, int slot) {
            this.key = key;
            this.label = label;
            this.env = env;
            this.icon = icon;
            this.color = color;
            this.slot = slot;
        }

        static Dim parse(String s) {
            String x = s.toLowerCase(Locale.ROOT);
            return switch (x) {
                case "overworld", "world", "over", "normal", "ow" -> OVERWORLD;
                case "nether", "hell" -> NETHER;
                case "end", "the_end", "theend" -> END;
                default -> null;
            };
        }
    }

    private static final class GuiHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    int radius;
    int maxAttempts;
    int endMaxAttempts;
    long cooldownMs;
    final Map<UUID, Long> lastUse = new HashMap<>();
    final Set<UUID> busy = new HashSet<>();

    static final Set<Material> UNSAFE_GROUND = new HashSet<>(Arrays.asList(
            Material.LAVA, Material.WATER, Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE,
            Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.WITHER_ROSE, Material.BEDROCK));

    @Override
    public void onEnable() {
        saveDefaultConfig();
        radius = Math.max(100, getConfig().getInt("radius", 500000));
        maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 40));
        endMaxAttempts = Math.max(1, getConfig().getInt("end-max-attempts", 120));
        cooldownMs = Math.max(0, getConfig().getInt("cooldown-seconds", 10)) * 1000L;
        if (getCommand("rtp") != null) {
            getCommand("rtp").setExecutor(this);
            getCommand("rtp").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RtpPlus bereit: Bereich " + (radius * 2) + " x " + (radius * 2));
    }

    // ------------------------------------------------------------------ Hilfen

    static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    private World worldFor(Dim d) {
        String name = getConfig().getString("worlds." + d.key, "");
        if (name != null && !name.isBlank()) {
            World w = Bukkit.getWorld(name);
            if (w != null) return w;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == d.env) return w;
        }
        return null;
    }

    // ------------------------------------------------------------------ GUI

    private ItemStack icon(Dim d) {
        ItemStack it = new ItemStack(d.icon);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(d.label, d.color).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
                Component.text("Klicke, um zufällig in", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("diese Dimension zu teleportieren.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(m);
        return it;
    }

    void openGui(Player p) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(), 27, Component.text("Random Teleport"));
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.empty());
        pane.setItemMeta(pm);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        for (Dim d : Dim.values()) inv.setItem(d.slot, icon(d));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        for (Dim d : Dim.values()) {
            if (e.getSlot() == d.slot) {
                p.closeInventory();
                start(p, d);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof GuiHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        busy.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "Nur für Spieler.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            openGui(p);
            return true;
        }
        Dim d = Dim.parse(args[0]);
        if (d == null) {
            msg(p, "Nutzung: /rtp [overworld|nether|end]", NamedTextColor.RED);
            return true;
        }
        start(p, d);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("overworld", "nether", "end")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Teleport

    void start(Player p, Dim d) {
        if (!p.hasPermission("rtpplus.world." + d.key)) {
            msg(p, "Du darfst dich nicht in diese Dimension teleportieren.", NamedTextColor.RED);
            return;
        }
        World world = worldFor(d);
        if (world == null) {
            msg(p, "Die Welt für " + d.label + " wurde nicht gefunden.", NamedTextColor.RED);
            return;
        }
        if (busy.contains(p.getUniqueId())) {
            msg(p, "Es wird bereits ein Ort gesucht, bitte warten.", NamedTextColor.YELLOW);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && !p.hasPermission("rtpplus.bypass") && now - last < cooldownMs) {
            long left = (cooldownMs - (now - last) + 999) / 1000;
            msg(p, "Bitte warte noch " + left + " Sekunden.", NamedTextColor.RED);
            return;
        }
        busy.add(p.getUniqueId());
        msg(p, "Suche einen zufälligen Ort in " + d.label + " ...", NamedTextColor.GRAY);
        attempt(p, d, world, 0, d == Dim.END ? endMaxAttempts : maxAttempts);
    }

    void fail(Player p, String text) {
        busy.remove(p.getUniqueId());
        if (p.isOnline()) msg(p, text, NamedTextColor.RED);
    }

    void attempt(Player p, Dim d, World world, int tries, int max) {
        if (!p.isOnline()) {
            busy.remove(p.getUniqueId());
            return;
        }
        if (tries >= max) {
            fail(p, "Kein sicherer Ort gefunden, versuche es erneut.");
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = r.nextInt(-radius, radius + 1);
        int z = r.nextInt(-radius, radius + 1);
        Location probe = new Location(world, x + 0.5, 64, z + 0.5);
        if (!world.getWorldBorder().isInside(probe)) {
            attempt(p, d, world, tries + 1, max);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, err) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (err != null || chunk == null) {
                        attempt(p, d, world, tries + 1, max);
                        return;
                    }
                    int y = findSafeY(world, x, z);
                    if (y == Integer.MIN_VALUE) {
                        attempt(p, d, world, tries + 1, max);
                        return;
                    }
                    Location target = new Location(world, x + 0.5, y, z + 0.5,
                            p.getLocation().getYaw(), p.getLocation().getPitch());
                    if (!p.isOnline()) {
                        busy.remove(p.getUniqueId());
                        return;
                    }
                    final int fy = y;
                    p.teleportAsync(target).whenComplete((ok, err2) ->
                            Bukkit.getScheduler().runTask(this, () -> {
                                busy.remove(p.getUniqueId());
                                if (err2 == null && Boolean.TRUE.equals(ok)) {
                                    lastUse.put(p.getUniqueId(), System.currentTimeMillis());
                                    msg(p, "Teleportiert nach " + d.label + ": " + x + ", " + fy + ", " + z,
                                            NamedTextColor.GREEN);
                                } else if (p.isOnline()) {
                                    msg(p, "Teleport fehlgeschlagen, versuche es erneut.", NamedTextColor.RED);
                                }
                            }));
                }));
    }

    int findSafeY(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            // Unter der Netherdecke von oben nach unten suchen
            for (int y = 120; y > world.getMinHeight() + 2; y--) {
                if (isSafe(world.getBlockAt(x, y - 1, z), world.getBlockAt(x, y, z), world.getBlockAt(x, y + 1, z))) {
                    return y;
                }
            }
            return Integer.MIN_VALUE;
        }
        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return Integer.MIN_VALUE;
        Block ground = world.getBlockAt(x, y, z);
        if (isSafe(ground, world.getBlockAt(x, y + 1, z), world.getBlockAt(x, y + 2, z))) return y + 1;
        return Integer.MIN_VALUE;
    }

    static boolean isSafe(Block ground, Block feet, Block head) {
        if (!ground.getType().isSolid() || UNSAFE_GROUND.contains(ground.getType())) return false;
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (feet.isLiquid() || head.isLiquid()) return false;
        return !UNSAFE_GROUND.contains(feet.getType()) && !UNSAFE_GROUND.contains(head.getType());
    }
}
