package com.warakorn.modblocker;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ModBlocker extends JavaPlugin implements Listener, PluginMessageListener {

    private Set<String> blockedMods;
    private Set<String> allowedModLoaders;
    private boolean kickOnModDetection;
    private String kickMessage;
    private Set<UUID> checkedPlayers;
    private Set<UUID> moddedClients;
    private Set<String> detectedMods;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        checkedPlayers = new HashSet<>();
        moddedClients = new HashSet<>();
        detectedMods = new HashSet<>();

        getServer().getPluginManager().registerEvents(this, this);
        setupPluginMessageChannels();

        getLogger().info("ModBlocker enabled!");
        getLogger().info("Allowed Mod Loaders: " + String.join(", ", allowedModLoaders));
        getLogger().info("Blocked Mods: " + String.join(", ", blockedMods));
        getLogger().info("Using aggressive mod detection methods");
    }

    private void loadConfigValues() {
        reloadConfig();

        blockedMods = new HashSet<>(getConfig().getStringList("blocked-mods"));
        allowedModLoaders = new HashSet<>(getConfig().getStringList("allowed-mod-loaders"));

        kickOnModDetection = getConfig().getBoolean("kick-on-mod-detection", true);
        kickMessage = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-message", "&cBlocked mods are not allowed on this server!"));
    }

    private void setupPluginMessageChannels() {
        // Register channels for aggressive mod detection
        String[] channels = {
                "fml:handshake", "fml:hs", "fml:login", "fml:play",
                "fabric:handshake", "fabric:login", "fabric:play",
                "forge:handshake", "forge:login", "forge:play",
                "minecraft:brand", "MC|Brand",
                "journeymap:sync", "journeymap:update", "journeymap:waypoints",
                "xaero:minimap", "xaero:worldmap", "xaerominimap:main", "xaeroworldmap:main",
                "voxelmap:main", "voxelmap:update",
                "litematica:sync", "litematica:update",
                "schematica:sync", "schematica:update",
                "wurst:main", "aristois:main", "impact:main",
                "baritone:settings", "baritone:commands",
                "5zig:set", "5zig:update",
                "labymod:main", "labymod:settings",
                "badlion:mods", "badlion:client"
        };

        for (String channel : channels) {
            try {
                getServer().getMessenger().registerIncomingPluginChannel(this, channel, this);
                getLogger().info("Registered detection channel: " + channel);
            } catch (Exception e) {
                // Channel may not register
            }
        }

        // Register outgoing channels for sending requests
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "minecraft:brand");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "fml:handshake");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "fabric:handshake");
        } catch (Exception e) {
            getLogger().warning("Failed to register outgoing channels");
        }
    }

    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        String channel = event.getChannel();

        if (shouldSkipCheck(player))
            return;

        getLogger().info("Player " + player.getName() + " registered channel: " + channel);
        checkModChannel(player, channel);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (shouldSkipCheck(player))
            return;

        getLogger().info("Player " + player.getName() + " sent message on channel: " + channel);
        checkModChannel(player, channel);

        // Check message content in detail
        if (message != null && message.length > 0) {
            checkMessageContent(player, channel, message);
        }
    }

    private void checkMessageContent(Player player, String channel, byte[] message) {
        try {
            String messageStr = new String(message).toLowerCase();
            getLogger().info("Message content: " + messageStr.substring(0, Math.min(messageStr.length(), 100)));

            // Check if message contains blocked words
            for (String blockedMod : blockedMods) {
                if (messageStr.contains(blockedMod.toLowerCase())) {
                    getLogger().warning("Detected blocked mod in message: " + blockedMod + " on channel: " + channel);
                    detectedMods.add(blockedMod);
                    kickPlayer(player, "Mod data: " + blockedMod);
                    return;
                }
            }
        } catch (Exception e) {
            // Cannot read message
        }
    }

    private boolean shouldSkipCheck(Player player) {
        return player == null ||
                player.hasPermission("modblocker.bypass") ||
                checkedPlayers.contains(player.getUniqueId());
    }

    private void checkModChannel(Player player, String channel) {
        String lowerChannel = channel.toLowerCase();

        // Check if it's an allowed mod loader
        for (String allowedLoader : allowedModLoaders) {
            if (lowerChannel.contains(allowedLoader.toLowerCase())) {
                getLogger().info("Player " + player.getName() + " uses " + allowedLoader + " (ALLOWED)");
                moddedClients.add(player.getUniqueId());
                return;
            }
        }

        // Check for blocked mods
        for (String blockedMod : blockedMods) {
            if (lowerChannel.contains(blockedMod.toLowerCase())) {
                getLogger().warning("Detected blocked mod: " + channel + " from player: " + player.getName());
                detectedMods.add(blockedMod);
                kickPlayer(player, "Blocked Mod: " + blockedMod);
                return;
            }
        }

        // Log for debugging
        getLogger().info("Player " + player.getName() + " channel: " + channel);
    }

    private void kickPlayer(Player player, String reason) {
        if (!kickOnModDetection) {
            getLogger().warning("Detected blocked mod but not kicking: " + reason + " from " + player.getName());
            return;
        }

        final String finalMessage = ChatColor.translateAlternateColorCodes('&',
                kickMessage.replace("{mod}", reason) + "\n&7(Reason: " + reason + ")");

        // Remove player from checkedPlayers before kicking to allow re-detection
        checkedPlayers.remove(player.getUniqueId());

        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.kickPlayer(finalMessage);
                getLogger().warning("Successfully kicked " + player.getName() + " for using: " + reason);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Remove player from checkedPlayers every time they join
        checkedPlayers.remove(player.getUniqueId());
        moddedClients.remove(player.getUniqueId());

        getLogger().info("Player " + player.getName() + " joined - starting mod detection...");

        // Start aggressive detection
        new BukkitRunnable() {
            @Override
            public void run() {
                if (checkedPlayers.contains(player.getUniqueId())) {
                    getLogger().info("Player " + player.getName() + " already checked, skipping...");
                    return;
                }

                getLogger().info("Starting aggressive mod check for " + player.getName());

                // Method 1: Aggressive client brand detection
                aggressiveClientDetection(player);

                // Method 2: Force mod check requests
                forceModCheck(player);

                // Method 3: Check currently registered channels
                checkExistingChannels(player);

                // Add delayed check for slow-loading mods
                delayedModCheck(player);

                // Mark as being checked
                checkedPlayers.add(player.getUniqueId());
            }
        }.runTaskLater(this, 20L); // Wait 1 second
    }

    // Add delayed check for slow-loading mods
    private void delayedModCheck(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || checkedPlayers.contains(player.getUniqueId())) {
                    return;
                }

                getLogger().info("Delayed mod check for " + player.getName());

                // Check channels again
                checkExistingChannels(player);

                // Send check requests again
                forceModCheck(player);

                // Check for new mods
                detectNewMods(player);
            }
        }.runTaskLater(this, 100L); // Wait 5 more seconds
    }

    private void detectNewMods(Player player) {
        try {
            Set<String> currentChannels = player.getListeningPluginChannels();
            getLogger()
                    .info("Delayed check - " + player.getName() + " now has " + currentChannels.size() + " channels");

            for (String channel : currentChannels) {
                checkModChannel(player, channel);
            }

            // If no blocked mods found, log it
            if (currentChannels.size() > 2 && !detectedMods.contains(player.getUniqueId())) {
                getLogger()
                        .info(player.getName() + " has mods but none blocked: " + String.join(", ", currentChannels));
            }

        } catch (Exception e) {
            getLogger().warning("Delayed mod check failed for " + player.getName());
        }
    }

    private void aggressiveClientDetection(Player player) {
        try {
            getLogger().info("Aggressive client detection for " + player.getName());

            // Method 1: Check client brand
            String clientBrand = getClientBrand(player);
            if (clientBrand != null && !clientBrand.equals("vanilla")) {
                getLogger().info("Player " + player.getName() + " client brand: " + clientBrand);
                moddedClients.add(player.getUniqueId());

                // Check brand against blocked list
                for (String blockedMod : blockedMods) {
                    if (clientBrand.toLowerCase().contains(blockedMod.toLowerCase())) {
                        getLogger().warning("Detected blocked mod in client brand: " + clientBrand);
                        detectedMods.add(blockedMod);
                        kickPlayer(player, "Client Brand: " + blockedMod);
                        return;
                    }
                }
            }

            // Method 2: Deep reflection detection
            detectModsViaReflection(player);

        } catch (Exception e) {
            getLogger().warning("Aggressive detection failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    private String getClientBrand(Player player) {
        try {
            // Use Bukkit API first
            if (player.getListeningPluginChannels().size() > 1) {
                return "modded (multiple channels)";
            }

            // Use reflection for client brand
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);

            // Try to find network manager
            Object playerConnection = getFieldValue(craftPlayer, "c", "b", "playerConnection");
            if (playerConnection != null) {
                Object networkManager = getFieldValue(playerConnection, "a", "networkManager");
                if (networkManager != null) {
                    return "modded (network manager found)";
                }
            }

            return "vanilla";

        } catch (Exception e) {
            return "unknown";
        }
    }

    private void detectModsViaReflection(Player player) {
        try {
            // Check for already registered mod channels
            Set<String> channels = player.getListeningPluginChannels();
            for (String channel : channels) {
                getLogger().info("Player " + player.getName() + " listening to channel: " + channel);
                checkModChannel(player, channel);
            }

            // Check number of channels
            if (channels.size() > 2) { // More than normal minecraft channels
                getLogger()
                        .warning("Player " + player.getName() + " has " + channels.size() + " channels (SUSPICIOUS)");
                moddedClients.add(player.getUniqueId());
            }

        } catch (Exception e) {
            getLogger().warning("Reflection detection failed for " + player.getName());
        }
    }

    private Object getFieldValue(Object object, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = object.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (Exception e) {
                // Try next field
            }
        }
        return null;
    }

    private void forceModCheck(Player player) {
        try {
            getLogger().info("Force mod check for " + player.getName());

            // Send brand request
            try {
                player.sendPluginMessage(this, "minecraft:brand", "ModBlocker".getBytes());
            } catch (Exception e) {
            }

            // Send requests to various mod channels
            String[] aggressiveChannels = {
                    "fml:handshake", "fml:hs", "fml:login",
                    "fabric:handshake", "fabric:login",
                    "forge:handshake", "forge:login",
                    "journeymap:sync", "xaero:minimap", "voxelmap:main"
            };

            for (String channel : aggressiveChannels) {
                try {
                    player.sendPluginMessage(this, channel, "MODBLOCKER_AGGRESSIVE_CHECK".getBytes());
                } catch (Exception e) {
                    // Channel not supported
                }
            }

            getLogger().info("Force mod check completed for " + player.getName());

        } catch (Exception e) {
            getLogger().warning("Force mod check failed for " + player.getName());
        }
    }

    private void checkExistingChannels(Player player) {
        try {
            // Check channels the player has already registered
            Set<String> currentChannels = player.getListeningPluginChannels();
            getLogger().info(player.getName() + " currently listening to " + currentChannels.size() + " channels");

            for (String channel : currentChannels) {
                checkModChannel(player, channel);
            }

        } catch (Exception e) {
            getLogger().warning("Failed to check existing channels for " + player.getName());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("modblocker")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("modblocker.reload")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission.");
                    return true;
                }

                loadConfigValues();
                checkedPlayers.clear();
                moddedClients.clear();
                detectedMods.clear();

                getServer().getMessenger().unregisterIncomingPluginChannel(this);
                getServer().getMessenger().unregisterOutgoingPluginChannel(this);
                setupPluginMessageChannels();

                sender.sendMessage(ChatColor.GREEN + "[ModBlocker] Config and channels reloaded!");
                return true;

            } else if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage(ChatColor.GREEN + "[ModBlocker] Status:");
                sender.sendMessage(ChatColor.YELLOW + "Detection: Aggressive Mode");
                sender.sendMessage(ChatColor.YELLOW + "Allowed Loaders: " + String.join(", ", allowedModLoaders));
                sender.sendMessage(ChatColor.YELLOW + "Blocked Mods: " + blockedMods.size() + " mods");
                sender.sendMessage(ChatColor.YELLOW + "Checked Players: " + checkedPlayers.size());
                sender.sendMessage(ChatColor.YELLOW + "Modded Clients: " + moddedClients.size());
                sender.sendMessage(ChatColor.YELLOW + "Detected Mods: " + String.join(", ", detectedMods));
                return true;

            } else if (args.length > 0 && args[0].equalsIgnoreCase("check")) {
                if (args.length > 1) {
                    Player target = getServer().getPlayer(args[1]);
                    if (target != null) {
                        Set<String> channels = target.getListeningPluginChannels();
                        sender.sendMessage(ChatColor.GREEN + "Player " + target.getName() + " status:");
                        sender.sendMessage(
                                ChatColor.YELLOW + "Checked: " + checkedPlayers.contains(target.getUniqueId()));
                        sender.sendMessage(
                                ChatColor.YELLOW + "Modded: " + moddedClients.contains(target.getUniqueId()));
                        sender.sendMessage(ChatColor.YELLOW + "Channels: " + channels.size());
                        for (String channel : channels) {
                            sender.sendMessage(ChatColor.GRAY + "  - " + channel);
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Player not found.");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /modblocker check <player>");
                }
                return true;

            } else if (args.length > 0 && args[0].equalsIgnoreCase("scan")) {
                sender.sendMessage(ChatColor.YELLOW + "Scanning all online players...");
                for (Player online : getServer().getOnlinePlayers()) {
                    if (!checkedPlayers.contains(online.getUniqueId())) {
                        aggressiveClientDetection(online);
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "Scan completed!");
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDisable() {
        getLogger().info("ModBlocker disabled!");
        checkedPlayers.clear();
        moddedClients.clear();
        detectedMods.clear();

        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }
}