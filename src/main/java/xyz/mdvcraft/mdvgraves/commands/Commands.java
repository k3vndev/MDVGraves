package xyz.mdvcraft.mdvgraves.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Commands implements CommandExecutor, TabCompleter, Listener {
  private final MDVGravesPlugin plugin;

  public Commands(MDVGravesPlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBackGraveAlias(PlayerCommandPreprocessEvent event) {
    if (!plugin.getConfig().getBoolean("utilities.back-grave.intercept-back-grave", true))
      return;
    if (!event.getMessage().trim().matches("(?i)^/back\\s+(grave|graves|bolsa)$"))
      return;
    event.setCancelled(true);
    plugin.executeGraveBack(event.getPlayer());
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (command.getName().equalsIgnoreCase("graveback")) {
      if (args.length >= 1) {
        if (!canControlOtherPlayer(sender)) {
          plugin.send(sender, "messages.no-permission", Map.of());
          return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
          plugin.send(sender, "messages.player-not-found", Map.of("player", args[0]));
          return true;
        }
        boolean success = plugin.executeGraveBack(target, true, true, true, true);
        plugin.send(sender, success ? "messages.back-other-success" : "messages.back-other-failed",
            Map.of("player", target.getName()));
        return true;
      }
      if (!(sender instanceof Player player)) {
        sender.sendMessage(plugin.color("&cUso desde consola: /graveback <jugador>"));
        return true;
      }
      return plugin.executeGraveBack(player);
    }

    if (args.length > 0 && isDeathFruitCommand(args[0])) {
      return plugin.executeDeathFruit(sender, args);
    }

    if (args.length > 0 && isBackCommand(args[0])) {
      if (args.length >= 2) {
        if (!canControlOtherPlayer(sender)) {
          plugin.send(sender, "messages.no-permission", Map.of());
          return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
          plugin.send(sender, "messages.player-not-found", Map.of("player", args[1]));
          return true;
        }
        boolean success = plugin.executeGraveBack(target, true, true, true, true);
        plugin.send(sender, success ? "messages.back-other-success" : "messages.back-other-failed",
            Map.of("player", target.getName()));
        return true;
      }
      if (!(sender instanceof Player player)) {
        sender.sendMessage(plugin.color("&cUso: /mdvgraves back <jugador>"));
        return true;
      }
      return plugin.executeGraveBack(player);
    }

    if (!sender.hasPermission("mdvgraves.admin")) {
      plugin.send(sender, "messages.no-permission", Map.of());
      return true;
    }
    if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
      sender.sendMessage(plugin.color("&6MDVGraves &f1.0.8 &7| Bolsas activas: &e" + plugin.getActiveGraveCount()));
      return true;
    }
    if (args[0].equalsIgnoreCase("reload")) {
      plugin.reloadPlugin();
      plugin.send(sender, "messages.reload", Map.of());
      return true;
    }
    if (args[0].equalsIgnoreCase("cleanup")) {
      int count = plugin.cleanupExpired(true);
      plugin.send(sender, "messages.cleanup", Map.of("count", Integer.toString(count)));
      return true;
    }
    if (isDeleteAllCommand(args[0])) {
      if (args.length < 2 || !(args[1].equalsIgnoreCase("confirm") || args[1].equalsIgnoreCase("confirmar"))) {
        plugin.send(sender, "messages.delete-all-confirm",
            Map.of("count", Integer.toString(plugin.getActiveGraveCount())));
        return true;
      }
      try {
        int count = plugin.deleteAllGraves();
        plugin.send(sender, "messages.delete-all-done", Map.of("count", Integer.toString(count)));
      } catch (Exception ex) {
        plugin.logCommandFailure("No se pudieron eliminar todas las bolsas.", ex);
        plugin.send(sender, "messages.delete-all-error", Map.of());
      }
      return true;
    }
    return false;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (command.getName().equalsIgnoreCase("graveback")) {
      if (args.length == 1 && canControlOtherPlayer(sender))
        return onlinePlayersStartingWith(args[0]);
      return List.of();
    }
    if (args.length == 2 && isBackCommand(args[0]) && canControlOtherPlayer(sender)) {
      return onlinePlayersStartingWith(args[1]);
    }
    if (args.length != 1)
      return List.of();
    List<String> options = new ArrayList<>();
    if (sender.hasPermission("mdvgraves.back"))
      options.add("back");
    if (sender.hasPermission("mdvgraves.admin")) {
      options.add("info");
      options.add("reload");
      options.add("cleanup");
      options.add("deleteall");
    }
    String prefix = args[0].toLowerCase(Locale.ROOT);
    return options.stream().filter(option -> option.startsWith(prefix)).toList();
  }

  private boolean canControlOtherPlayer(CommandSender sender) {
    return sender instanceof org.bukkit.command.ConsoleCommandSender
        || sender.hasPermission("mdvgraves.back.others")
        || sender.hasPermission("mdvgraves.admin");
  }

  private List<String> onlinePlayersStartingWith(String input) {
    String prefix = input.toLowerCase(Locale.ROOT);
    return Bukkit.getOnlinePlayers().stream().map(Player::getName)
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
  }

  private boolean isDeathFruitCommand(String argument) {
    return argument.equalsIgnoreCase("deathfruit")
        || argument.equalsIgnoreCase("fruta")
        || argument.equalsIgnoreCase("frutamuerte");
  }

  private boolean isBackCommand(String argument) {
    return argument.equalsIgnoreCase("back")
        || argument.equalsIgnoreCase("volver")
        || argument.equalsIgnoreCase("regresar");
  }

  private boolean isDeleteAllCommand(String argument) {
    return argument.equalsIgnoreCase("deleteall")
        || argument.equalsIgnoreCase("purge")
        || argument.equalsIgnoreCase("eliminartodas");
  }
}
