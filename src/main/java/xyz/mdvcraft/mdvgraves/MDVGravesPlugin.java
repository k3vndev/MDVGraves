package xyz.mdvcraft.mdvgraves;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.lang.reflect.Method;
import java.sql.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class MDVGravesPlugin extends JavaPlugin implements Listener {
    private static final Set<String> THIN_REPLACEABLE_MATERIALS = Set.of(
            "SNOW", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "VINE", "CAVE_VINES", "CAVE_VINES_PLANT", "WEEPING_VINES",
            "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT", "GLOW_LICHEN",
            "HANGING_ROOTS", "NETHER_SPROUTS", "CRIMSON_ROOTS", "WARPED_ROOTS",
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA",
            "MELON_STEM", "PUMPKIN_STEM", "ATTACHED_MELON_STEM",
            "ATTACHED_PUMPKIN_STEM", "SWEET_BERRY_BUSH", "TORCHFLOWER_CROP",
            "PITCHER_CROP", "BAMBOO_SAPLING", "LILY_PAD", "PINK_PETALS",
            "WILDFLOWERS", "LEAF_LITTER", "BUSH", "FIREFLY_BUSH", "SHORT_DRY_GRASS",
            "TALL_DRY_GRASS", "PALE_HANGING_MOSS", "PALE_MOSS_CARPET",
            "MOSS_CARPET", "SCULK_VEIN", "COBWEB", "DANDELION", "POPPY",
            "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER",
            "LILY_OF_THE_VALLEY", "WITHER_ROSE", "TORCHFLOWER", "OPEN_EYEBLOSSOM",
            "CLOSED_EYEBLOSSOM", "SPORE_BLOSSOM", "SMALL_DRIPLEAF"
    );
    private NamespacedKey graveKey;
    private Connection connection;
    private final Map<UUID, GraveMeta> graves = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> gravesByBlock = new ConcurrentHashMap<>();
    // Cada bolsa abierta utiliza un único inventario canónico compartido. Incluso si
    // single-viewer-lock se desactiva, nunca se crean dos copias visuales del mismo loot.
    private final Map<UUID, Set<UUID>> activeViewers = new HashMap<>();
    private final Map<UUID, Inventory> openGraveInventories = new HashMap<>();
    private final Set<UUID> forcedClosingGraves = new HashSet<>();
    private BukkitTask cleanupTask;
    private BukkitTask integrityTask;
    private final Map<UUID, Long> graveBackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deathFruitUseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDeathFruitUse> pendingDeathFruitUses = new ConcurrentHashMap<>();
    private Method nbtItemGetMethod;
    private Method nbtItemGetStringMethod;
    private boolean mmoItemsBridgeReady;
    private boolean mmoItemsBridgeWarningLogged;
    private String deathFruitExpectedType = "CONSUMABLE";
    private String deathFruitExpectedId = "FRUTA_DE_LA_MUERTE";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        graveKey = new NamespacedKey(this, "grave_id");
        try {
            openDatabase();
            createSchema();
            loadActiveGraves();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo iniciar SQLite. MDVGraves será desactivado.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        setupMmoItemsBridge();
        scheduleCleanup();
        scheduleOpenGraveIntegrityGuard();
        getLogger().info("MDVGraves 1.0.8 activo. Bolsas cargadas: " + graves.size());
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) cleanupTask.cancel();
        if (integrityTask != null) integrityTask.cancel();
        // Cada bolsa abierta posee un único inventario canónico. Se persiste una sola vez,
        // aunque más de un jugador la estuviera observando.
        for (Map.Entry<UUID, Inventory> entry : new ArrayList<>(openGraveInventories.entrySet())) {
            saveInventoryAndMaybeRemove(entry.getKey(), entry.getValue(), false);
        }
        activeViewers.clear();
        openGraveInventories.clear();
        forcedClosingGraves.clear();
        deathFruitUseLocks.clear();
        pendingDeathFruitUses.clear();
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) { }
    }

    private void openDatabase() throws Exception {
        File dbFile = new File(getDataFolder(), "graves.db");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IOException("No se pudo crear " + getDataFolder());
        }
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=3000");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS graves (
                  grave_id TEXT PRIMARY KEY,
                  owner_uuid TEXT NOT NULL,
                  owner_name TEXT NOT NULL,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  created_at INTEGER NOT NULL,
                  first_opened_at INTEGER,
                  expires_at INTEGER NOT NULL,
                  items BLOB NOT NULL,
                  owner_protected INTEGER NOT NULL DEFAULT 0
                )
                """);
            try {
                st.executeUpdate("ALTER TABLE graves ADD COLUMN owner_protected INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                if (!message.contains("duplicate column")) throw ex;
            }
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_graves_expires ON graves(expires_at)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_graves_location ON graves(world,x,y,z)");
        }
    }

    private void loadActiveGraves() throws SQLException {
        graves.clear();
        gravesByBlock.clear();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at,owner_protected FROM graves")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GraveMeta meta = readMeta(rs);
                    graves.put(meta.id(), meta);
                    gravesByBlock.put(meta.blockKey(), meta.id());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("settings.enabled", true)) return;
        Player player = event.getEntity();

        if (getConfig().getBoolean("utilities.keep-inventory.enabled", true)
                && player.hasPermission("mdvgraves.keepinventory")) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            send(player, "messages.keep-inventory", Map.of());
            return;
        }

        if (!enabledWorld(player.getWorld().getName())) return;
        if (event.getKeepInventory() || event.getDrops().isEmpty()) return;

        List<ItemStack> drops = event.getDrops().stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .map(ItemStack::clone)
                .toList();
        if (drops.isEmpty()) return;

        Block target = findPlacementBlock(player.getLocation());
        if (target == null) {
            getLogger().warning("No se encontró espacio seguro para la bolsa de " + player.getName() + ". Se conservaron drops vanilla.");
            return;
        }

        UUID id = UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        long expires = created + Math.max(1L, getConfig().getLong("settings.unopened-expire-hours", 168L)) * 3600L;
        boolean ownerProtected = getConfig().getBoolean("utilities.private-graves.enabled", true)
                && player.hasPermission("mdvgraves.private");
        GraveMeta meta = new GraveMeta(id, player.getUniqueId(), player.getName(), target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), created, null, expires, ownerProtected);

        SupportPatch supportPatch = stabilizePlacementSupport(target);
        try {
            byte[] blob = serializeItems(drops);
            placeGraveHead(target, id, player);
            insertGrave(meta, blob);
            graves.put(id, meta);
            gravesByBlock.put(meta.blockKey(), id);
            event.getDrops().clear();
            send(player, "messages.grave-created", Map.of(
                    "x", Integer.toString(target.getX()), "y", Integer.toString(target.getY()), "z", Integer.toString(target.getZ()),
                    "owner", player.getName()));
        } catch (Exception ex) {
            // Rollback visual: nunca quitamos drops si la persistencia falló.
            if (isOurHead(target, id)) target.setType(Material.AIR, false);
            rollbackSupportPatch(supportPatch);
            getLogger().log(Level.SEVERE, "No se pudo crear la bolsa de " + player.getName() + ". Se conservaron drops vanilla.", ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingDeathFruitUses.remove(event.getPlayer().getUniqueId());
        deathFruitUseLocks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeathFruitUseStart(PlayerInteractEvent event) {
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true)) return;
        if (event.getHand() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isConfiguredDeathFruit(item)) return;

        pendingDeathFruitUses.put(event.getPlayer().getUniqueId(),
                new PendingDeathFruitUse(event.getHand(), item.clone(), item.getAmount(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        // Bukkit dispara PlayerInteractEvent una vez por cada mano. Solo procesamos la principal
        // para evitar abrir la misma bolsa y enviar mensajes dos veces.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        UUID id = physicalGraveId(event.getClickedBlock());
        if (id == null) return;
        event.setCancelled(true);

        GraveMeta meta = graves.get(id);
        if (meta == null) {
            event.getClickedBlock().setType(Material.AIR, false);
            return;
        }
        Player player = event.getPlayer();
        if (!canAccessGrave(player, meta)) {
            send(player, meta.ownerProtected() ? "messages.grave-private" : "messages.not-owner",
                    Map.of("owner", meta.ownerName()));
            return;
        }
        pruneInactiveViewers(id);
        Set<UUID> viewers = activeViewers.computeIfAbsent(id, ignored -> new LinkedHashSet<>());
        if (getConfig().getBoolean("settings.single-viewer-lock", true)
                && viewers.stream().anyMatch(viewer -> !viewer.equals(player.getUniqueId()))) {
            send(player, "messages.grave-busy", Map.of("owner", meta.ownerName()));
            return;
        }

        try {
            Inventory inventory = openGraveInventories.get(id);
            if (inventory == null) {
                List<ItemStack> items = loadItems(id);
                int size = inventorySize(items.size());
                String title = color(getConfig().getString("inventory.title", "&8Bolsa perdida de &e{owner}"))
                        .replace("{owner}", meta.ownerName());
                GraveHolder holder = new GraveHolder(id);
                inventory = Bukkit.createInventory(holder, size, title);
                holder.inventory = inventory;
                for (ItemStack item : items) inventory.addItem(item.clone());
                openGraveInventories.put(id, inventory);
            }

            viewers.add(player.getUniqueId());
            markFirstOpened(meta);
            player.openInventory(inventory);
            send(player, "messages.grave-opened", Map.of("owner", meta.ownerName()));
        } catch (Exception ex) {
            viewers.remove(player.getUniqueId());
            if (viewers.isEmpty()) {
                activeViewers.remove(id);
                openGraveInventories.remove(id);
            }
            getLogger().log(Level.SEVERE, "No se pudo abrir la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GraveHolder holder)) return;
        UUID id = holder.graveId();
        GraveMeta meta = graves.get(id);
        Block physicalBlock = loadedGraveBlock(meta);
        boolean physicalMissing = physicalBlock != null && !isOurHead(physicalBlock, id);

        Set<UUID> viewers = activeViewers.get(id);
        if (viewers != null) {
            viewers.remove(event.getPlayer().getUniqueId());
            if (viewers.isEmpty()) activeViewers.remove(id);
        }

        // Los cierres forzados persisten el inventario exactamente una vez desde
        // closeAllViewersAndPersist(), no una vez por cada espectador.
        if (forcedClosingGraves.contains(id)) return;
        if (isBeingViewed(id)) {
            if (physicalMissing) verifyOpenGraveNextTick(id);
            return;
        }

        openGraveInventories.remove(id);
        saveInventoryAndMaybeRemove(id, event.getInventory(), true);

        // Cubre el caso extremo en que otro plugin cambió el bloque y el último visor
        // cerró la GUI antes de que alcanzara a ejecutarse el guardián periódico.
        if (physicalMissing && physicalBlock != null && graves.containsKey(id)) {
            restoreGraveHead(physicalBlock, meta);
        }
    }

    private void saveInventoryAndMaybeRemove(UUID id, Inventory inventory, boolean notify) {
        GraveMeta meta = graves.get(id);
        if (meta == null) return;
        List<ItemStack> remaining = Arrays.stream(inventory.getStorageContents())
                .filter(Objects::nonNull).filter(i -> !i.getType().isAir()).map(ItemStack::clone).toList();
        try {
            if (remaining.isEmpty() && getConfig().getBoolean("settings.remove-when-empty", true)) {
                removeGrave(id, false, List.of());
                if (notify && inventory.getViewers().isEmpty()) {
                    Player owner = Bukkit.getPlayer(meta.ownerUuid());
                    if (owner != null) send(owner, "messages.grave-empty-removed", Map.of("owner", meta.ownerName()));
                }
            } else {
                updateItems(id, serializeItems(remaining));
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo guardar la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null) return;
        event.setCancelled(true);
        event.setDropItems(false);

        GraveMeta meta = graves.get(id);
        if (meta == null) return;
        if (isBeingViewed(id)) {
            send(event.getPlayer(), "messages.grave-in-use-break", Map.of("owner", meta.ownerName()));
            verifyOpenGraveNextTick(id);
            return;
        }
        if (!canAccessGrave(event.getPlayer(), meta)) {
            send(event.getPlayer(), meta.ownerProtected() ? "messages.grave-private" : "messages.not-owner",
                    Map.of("owner", meta.ownerName()));
            return;
        }
        breakGrave(event.getPlayer(), id);
    }

    private void breakGrave(Player breaker, UUID id) {
        GraveMeta meta = graves.get(id);
        if (meta == null) return;
        // Regla anti-duplicación: una bolsa abierta nunca entra en la ruta de borrado/drop.
        if (isBeingViewed(id)) {
            if (breaker != null) send(breaker, "messages.grave-in-use-break", Map.of("owner", meta.ownerName()));
            verifyOpenGraveNextTick(id);
            return;
        }
        try {
            List<ItemStack> items = getConfig().getBoolean("settings.break-drops-items", true) ? loadItems(id) : List.of();
            removeGrave(id, true, items);
            if (breaker != null) send(breaker, "messages.grave-broken", Map.of("owner", meta.ownerName()));
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo romper correctamente la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        breakGravesFromExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        breakGravesFromExplosion(event.blockList());
    }

    private void breakGravesFromExplosion(List<Block> affectedBlocks) {
        if (!getConfig().getBoolean("settings.explosions-break-graves", true)) {
            // Modo antiguo opcional: la explosión no destruye bolsas.
            affectedBlocks.removeIf(block -> trackedGraveId(block) != null);
            return;
        }

        Set<UUID> graveIds = new LinkedHashSet<>();
        affectedBlocks.removeIf(block -> {
            UUID id = trackedGraveId(block);
            if (id == null) return false;
            if (isBeingViewed(id)) {
                verifyOpenGraveNextTick(id);
                return true;
            }
            GraveMeta meta = graves.get(id);
            if (meta != null && meta.ownerProtected()
                    && getConfig().getBoolean("utilities.private-graves.protect-from-explosions", true)) {
                return true;
            }
            graveIds.add(id);
            // Se quita de la lista vanilla: MDVGraves hace una única eliminación transaccional
            // y suelta exactamente el inventario persistido, no una cabeza adicional.
            return true;
        });
        for (UUID id : graveIds) breakGrave(null, id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        // Evita que agua o lava desplacen/alteren una bolsa. No afecta otros bloques.
        UUID id = trackedGraveId(event.getToBlock());
        if (id != null && (isBeingViewed(id) || getConfig().getBoolean("settings.protect-from-fluids", true))) {
            event.setCancelled(true);
            if (isBeingViewed(id)) verifyOpenGraveNextTick(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        UUID openId = event.getBlocks().stream().map(this::trackedGraveId)
                .filter(Objects::nonNull).filter(this::isBeingViewed).findFirst().orElse(null);
        boolean containsGrave = event.getBlocks().stream().anyMatch(block -> trackedGraveId(block) != null);
        if (openId != null || (getConfig().getBoolean("settings.protect-from-pistons", true) && containsGrave)) {
            event.setCancelled(true);
            if (openId != null) verifyOpenGraveNextTick(openId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        UUID openId = event.getBlocks().stream().map(this::trackedGraveId)
                .filter(Objects::nonNull).filter(this::isBeingViewed).findFirst().orElse(null);
        boolean containsGrave = event.getBlocks().stream().anyMatch(block -> trackedGraveId(block) != null);
        if (openId != null || (getConfig().getBoolean("settings.protect-from-pistons", true) && containsGrave)) {
            event.setCancelled(true);
            if (openId != null) verifyOpenGraveNextTick(openId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null) return;
        event.setCancelled(true);
        if (isBeingViewed(id)) verifyOpenGraveNextTick(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null) return;
        event.setCancelled(true);
        if (isBeingViewed(id)) verifyOpenGraveNextTick(id);
    }

    private void protectTrackedGraveBlock(Block block, Cancellable event) {
        UUID id = trackedGraveId(block);
        if (id == null) return;
        event.setCancelled(true);
        if (isBeingViewed(id)) verifyOpenGraveNextTick(id);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Limpia cabezas huérfanas sin forzar la carga de chunks durante cleanup.
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (!(state instanceof Skull skull)) continue;
            String raw = skull.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING);
            if (raw == null) continue;
            try {
                UUID id = UUID.fromString(raw);
                if (!graves.containsKey(id)) skull.getBlock().setType(Material.AIR, false);
            } catch (IllegalArgumentException ex) {
                skull.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void scheduleOpenGraveIntegrityGuard() {
        if (integrityTask != null) integrityTask.cancel();
        long ticks = Math.max(1L, getConfig().getLong("settings.open-grave-integrity-check-ticks", 2L));
        integrityTask = Bukkit.getScheduler().runTaskTimer(this, this::verifyAllOpenGraves, ticks, ticks);
    }

    private void verifyAllOpenGraves() {
        for (UUID id : new ArrayList<>(activeViewers.keySet())) {
            pruneInactiveViewers(id);
            if (!isBeingViewed(id)) continue;
            verifyOpenGraveIntegrity(id);
        }
    }

    private void verifyOpenGraveNextTick(UUID id) {
        Bukkit.getScheduler().runTask(this, () -> verifyOpenGraveIntegrity(id));
    }

    private void verifyOpenGraveIntegrity(UUID id) {
        if (!isBeingViewed(id)) return;
        GraveMeta meta = graves.get(id);
        if (meta == null) {
            closeAllViewersAndPersist(id, false);
            return;
        }
        World world = Bukkit.getWorld(meta.world());
        if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4)) return;
        Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
        if (isOurHead(block, id)) return;

        getLogger().warning("La bolsa " + id + " fue alterada mientras estaba abierta. "
                + "Se cerrarán sus visores y se restaurará el bloque sin generar drops.");
        closeAllViewersAndPersist(id, true);
        if (!graves.containsKey(id)) return; // Quedó vacía y fue retirada normalmente.
        restoreGraveHead(block, meta);
    }

    private Block loadedGraveBlock(GraveMeta meta) {
        if (meta == null) return null;
        World world = Bukkit.getWorld(meta.world());
        if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4)) return null;
        return world.getBlockAt(meta.x(), meta.y(), meta.z());
    }

    private void restoreGraveHead(Block block, GraveMeta meta) {
        block.setType(Material.PLAYER_HEAD, false);
        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, meta.id().toString());
        Player owner = Bukkit.getPlayer(meta.ownerUuid());
        if (owner != null) {
            applyTexture(skull, owner);
        } else {
            PlayerProfile profile = Bukkit.createPlayerProfile(meta.ownerUuid(), meta.ownerName());
            skull.setOwnerProfile(profile);
        }
        skull.update(true, false);
    }

    private void closeAllViewersAndPersist(UUID id, boolean notify) {
        Inventory inventory = openGraveInventories.get(id);
        Set<UUID> viewers = new LinkedHashSet<>(activeViewers.getOrDefault(id, Set.of()));
        forcedClosingGraves.add(id);
        try {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) continue;
                if (notify) send(viewer, "messages.grave-forced-closed", Map.of());
                if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder holder
                        && holder.graveId().equals(id)) viewer.closeInventory();
            }
            activeViewers.remove(id);
            openGraveInventories.remove(id);
            if (inventory != null) saveInventoryAndMaybeRemove(id, inventory, false);
        } finally {
            forcedClosingGraves.remove(id);
        }
    }

    private boolean isBeingViewed(UUID id) {
        pruneInactiveViewers(id);
        Set<UUID> viewers = activeViewers.get(id);
        return viewers != null && !viewers.isEmpty();
    }

    private void pruneInactiveViewers(UUID id) {
        Set<UUID> viewers = activeViewers.get(id);
        if (viewers == null) return;
        viewers.removeIf(viewerId -> {
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null) return true;
            Inventory top = player.getOpenInventory().getTopInventory();
            return !(top.getHolder() instanceof GraveHolder holder) || !holder.graveId().equals(id);
        });
        if (viewers.isEmpty()) activeViewers.remove(id);
    }

    private void scheduleCleanup() {
        if (cleanupTask != null) cleanupTask.cancel();
        long seconds = Math.max(60L, getConfig().getLong("settings.cleanup-interval-seconds", 300L));
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> cleanupExpired(false), seconds * 20L, seconds * 20L);
    }

    private int cleanupExpired(boolean manual) {
        long now = Instant.now().getEpochSecond();
        List<UUID> expired = graves.values().stream().filter(g -> g.expiresAt() <= now).map(GraveMeta::id).toList();
        int removed = 0;
        for (UUID id : expired) {
            if (isBeingViewed(id)) continue;
            GraveMeta meta = graves.get(id);
            if (meta == null) continue;
            try {
                removeGrave(id, false, List.of());
                Player owner = Bukkit.getPlayer(meta.ownerUuid());
                if (owner != null) send(owner, "messages.grave-expired-owner", Map.of("owner", meta.ownerName()));
                removed++;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "No se pudo limpiar la bolsa " + id, ex);
            }
        }
        return removed;
    }

    private void removeGrave(UUID id, boolean dropItems, List<ItemStack> items) throws SQLException {
        GraveMeta meta = graves.get(id);
        if (meta == null) return;
        // DB primero: si falla, no dropea ni borra bloque, evitando duplicación.
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM graves WHERE grave_id=?")) {
            ps.setString(1, id.toString());
            if (ps.executeUpdate() == 0) return;
        }
        graves.remove(id);
        gravesByBlock.remove(meta.blockKey());
        activeViewers.remove(id);
        openGraveInventories.remove(id);
        forcedClosingGraves.remove(id);

        World world = Bukkit.getWorld(meta.world());
        if (world != null && world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4)) {
            Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
            if (isOurHead(block, id)) block.setType(Material.AIR, false);
            if (dropItems) {
                Location dropAt = new Location(world, meta.x() + 0.5, meta.y() + 0.3, meta.z() + 0.5);
                for (ItemStack item : items) world.dropItemNaturally(dropAt, item.clone());
            }
        }
    }

    private void markFirstOpened(GraveMeta meta) throws SQLException {
        if (meta.firstOpenedAt() != null) return;
        long opened = Instant.now().getEpochSecond();
        long expires = opened + Math.max(1L, getConfig().getLong("settings.opened-expire-minutes", 20L)) * 60L;
        try (PreparedStatement ps = connection.prepareStatement("UPDATE graves SET first_opened_at=?, expires_at=? WHERE grave_id=?")) {
            ps.setLong(1, opened);
            ps.setLong(2, expires);
            ps.setString(3, meta.id().toString());
            ps.executeUpdate();
        }
        GraveMeta updated = meta.withOpened(opened, expires);
        graves.put(meta.id(), updated);
    }

    private void insertGrave(GraveMeta meta, byte[] items) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO graves(grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at,items,owner_protected)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            ps.setString(1, meta.id().toString());
            ps.setString(2, meta.ownerUuid().toString());
            ps.setString(3, meta.ownerName());
            ps.setString(4, meta.world());
            ps.setInt(5, meta.x()); ps.setInt(6, meta.y()); ps.setInt(7, meta.z());
            ps.setLong(8, meta.createdAt());
            ps.setNull(9, Types.BIGINT);
            ps.setLong(10, meta.expiresAt());
            ps.setBytes(11, items);
            ps.setInt(12, meta.ownerProtected() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void updateItems(UUID id, byte[] items) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE graves SET items=? WHERE grave_id=?")) {
            ps.setBytes(1, items);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
    }

    private List<ItemStack> loadItems(UUID id) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT items FROM graves WHERE grave_id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                return deserializeItems(rs.getBytes(1));
            }
        }
    }

    private byte[] serializeItems(List<ItemStack> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(gzip)) {
            out.writeInt(items.size());
            for (ItemStack item : items) out.writeObject(item);
        }
        return bytes.toByteArray();
    }

    private List<ItemStack> deserializeItems(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) return List.of();
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int size = in.readInt();
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) items.add((ItemStack) in.readObject());
            return items;
        }
    }

    private void placeGraveHead(Block block, UUID id, Player owner) {
        block.setType(Material.PLAYER_HEAD, false);
        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, id.toString());
        applyTexture(skull, owner);
        skull.update(true, false);
    }

    private void applyTexture(Skull skull, Player owner) {
        String texture = selectTexture(owner);
        PlayerProfile profile;
        if (texture != null && !texture.isBlank()) {
            profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "MDVGrave");
            String skinUrl = extractTextureUrl(texture.trim());
            if (skinUrl != null) {
                try {
                    PlayerTextures textures = profile.getTextures();
                    textures.setSkin(URI.create(skinUrl).toURL());
                    profile.setTextures(textures);
                } catch (Exception ex) {
                    getLogger().log(Level.WARNING, "Textura Base64 inválida; se usará la cabeza del jugador.", ex);
                    profile = owner.getPlayerProfile().clone();
                }
            } else {
                getLogger().warning("No se encontró una URL textures.minecraft.net en la textura Base64; se usará la cabeza del jugador.");
                profile = owner.getPlayerProfile().clone();
            }
        } else {
            profile = owner.getPlayerProfile().clone();
        }
        // API Bukkit compatible con Paper/Purpur 1.21.6; evita mezclar los dos PlayerProfile de Paper.
        skull.setOwnerProfile(profile);
    }

    private String extractTextureUrl(String base64Texture) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
            int key = json.indexOf("\"url\"");
            if (key < 0) return null;
            int colon = json.indexOf(':', key);
            int firstQuote = json.indexOf('\"', colon + 1);
            int secondQuote = json.indexOf('\"', firstQuote + 1);
            if (colon < 0 || firstQuote < 0 || secondQuote < 0) return null;
            return json.substring(firstQuote + 1, secondQuote).replace("\\/", "/");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String selectTexture(Player player) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("textures.ranks");
        if (ranks != null) {
            for (String key : ranks.getKeys(false)) {
                String permission = ranks.getString(key + ".permission", "");
                String texture = ranks.getString(key + ".texture", "");
                if (!permission.isBlank() && player.hasPermission(permission) && !texture.isBlank()) return texture;
            }
        }
        return getConfig().getString("textures.default", "");
    }

    private Block findPlacementBlock(Location death) {
        int radius = Math.max(0, getConfig().getInt("settings.placement-search-radius", 2));
        World world = death.getWorld();
        int baseX = death.getBlockX();
        int baseZ = death.getBlockZ();
        int startY = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 1, death.getBlockY()));

        // Primero revisa exactamente la columna donde murió el jugador. Si no existe
        // una superficie utilizable, amplía la búsqueda por anillos cercanos.
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    Block target = findFirstSurfaceAbove(world, baseX + dx, startY, baseZ + dz);
                    if (target != null) return target;
                }
            }
        }
        return null;
    }

    /**
     * Busca hacia abajo desde la altura de muerte y devuelve el bloque reemplazable
     * inmediatamente superior al primer bloque sólido encontrado. Esto evita bolsas
     * flotando cuando el jugador muere en caída, vuelo o sobre un precipicio.
     */
    private Block findFirstSurfaceAbove(World world, int x, int startY, int z) {
        int minY = world.getMinHeight();
        for (int y = startY; y > minY; y--) {
            Block support = world.getBlockAt(x, y - 1, z);
            if (canReplace(support)) continue;

            Block target = world.getBlockAt(x, y, z);
            return canReplace(target) ? target : null;
        }
        return null;
    }


    /**
     * Algunos bloques de suelo parcial (por ejemplo DIRT_PATH y FARMLAND) no son un
     * soporte estable para una PLAYER_HEAD de suelo. La corrección solo se evalúa
     * cuando realmente va a crearse una tumba, por lo que no añade tareas periódicas.
     *
     * Los reemplazos son configurables. Si la creación de la tumba falla, el bloque
     * original se restaura para no modificar el mapa por un error de persistencia.
     */
    private SupportPatch stabilizePlacementSupport(Block target) {
        if (!getConfig().getBoolean("settings.placement-support-fixes.enabled", true)) return null;
        Block support = target.getRelative(BlockFace.DOWN);
        ConfigurationSection replacements = getConfig().getConfigurationSection("settings.placement-support-fixes.replacements");
        if (replacements == null) return null;

        String configured = replacements.getString(support.getType().name());
        if (configured == null || configured.isBlank()) return null;

        Material replacement = Material.matchMaterial(configured.trim());
        if (replacement == null || replacement.isAir() || !replacement.isBlock()) {
            getLogger().warning("Reemplazo inválido para soporte de tumba " + support.getType()
                    + ": " + configured);
            return null;
        }

        BlockData original = support.getBlockData().clone();
        support.setType(replacement, false);
        return new SupportPatch(support, original);
    }

    private void rollbackSupportPatch(SupportPatch patch) {
        if (patch == null) return;
        try {
            patch.block().setBlockData(patch.original(), false);
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "No se pudo restaurar el bloque de soporte tras fallar una tumba.", ex);
        }
    }

    private boolean canReplace(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA
                || type == Material.FIRE || type == Material.SOUL_FIRE) return true;
        if (!getConfig().getBoolean("settings.replace-thin-blocks", true)) return false;
        return isThinReplaceable(type);
    }

    /**
     * Bloques de superficie, vegetación y cultivos que la bolsa puede sustituir sin
     * generar drops. Se usan nombres para mantener compatibilidad con materiales
     * vegetales añadidos en revisiones 1.21.x sin depender de Tags experimentales.
     */
    private boolean isThinReplaceable(Material type) {
        String name = type.name();
        if (name.endsWith("_CARPET") || name.endsWith("_SAPLING")
                || name.endsWith("_TULIP") || name.endsWith("_FLOWER")
                || name.endsWith("_MUSHROOM") || name.endsWith("_FUNGUS")
                || name.endsWith("_ROOTS") || name.endsWith("_VINES")
                || name.endsWith("_VINES_PLANT")) return true;

        return THIN_REPLACEABLE_MATERIALS.contains(name);
    }

    /** Devuelve el ID registrado para una ubicación, aunque otro plugin haya cambiado el bloque. */
    private UUID trackedGraveId(Block block) {
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        UUID cached = gravesByBlock.get(key);
        return cached != null ? cached : physicalGraveId(block);
    }

    /** Lee exclusivamente la cabeza física y su PDC; no confía en el caché de ubicación. */
    private UUID physicalGraveId(Block block) {
        if (!(block.getState() instanceof Skull skull)) return null;
        String raw = skull.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean isOurHead(Block block, UUID id) {
        return id.equals(physicalGraveId(block));
    }


    private boolean canAccessGrave(Player player, GraveMeta meta) {
        if (meta.ownerUuid().equals(player.getUniqueId()) || player.hasPermission("mdvgraves.admin")) return true;
        if (getConfig().getBoolean("settings.only-owner-can-open", false)) return false;
        if (!getConfig().getBoolean("utilities.private-graves.enabled", true)) return true;

        Player owner = Bukkit.getPlayer(meta.ownerUuid());
        boolean currentlyProtected = owner != null && owner.hasPermission("mdvgraves.private");
        return !meta.ownerProtected() && !currentlyProtected;
    }

    private boolean enabledWorld(String world) {
        return getConfig().getStringList("settings.enabled-worlds").stream().anyMatch(name -> name.equalsIgnoreCase(world));
    }

    private int inventorySize(int itemCount) {
        int min = Math.max(9, Math.min(54, getConfig().getInt("inventory.minimum-size", 27)));
        min = ((min + 8) / 9) * 9;
        int needed = Math.max(min, ((Math.max(1, itemCount) + 8) / 9) * 9);
        return Math.min(54, needed);
    }

    private GraveMeta readMeta(ResultSet rs) throws SQLException {
        long openedRaw = rs.getLong("first_opened_at");
        Long opened = rs.wasNull() ? null : openedRaw;
        return new GraveMeta(UUID.fromString(rs.getString("grave_id")), UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getLong("created_at"), opened, rs.getLong("expires_at"), rs.getInt("owner_protected") != 0);
    }

    private void send(CommandSender sender, String path, Map<String, String> replacements) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return;
        String prefix = getConfig().getString("messages.prefix", "");
        String result = prefix + raw;
        for (Map.Entry<String, String> entry : replacements.entrySet()) result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        sender.sendMessage(color(result));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBackGraveAlias(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("utilities.back-grave.intercept-back-grave", true)) return;
        String message = event.getMessage().trim();
        if (!message.matches("(?i)^/back\\s+(grave|graves|bolsa)$")) return;
        event.setCancelled(true);
        executeGraveBack(event.getPlayer());
    }

    private boolean executeGraveBack(Player player) {
        attemptGraveBack(player, false, false, false, true);
        return true;
    }

    /**
     * Ejecuta el viaje a la última tumba del jugador.
     *
     * @param bypassEnabled    ignora utilities.back-grave.enabled (admin/fruta)
     * @param bypassPermission ignora mdvgraves.back
     * @param bypassCooldown   ignora y no aplica cooldown
     * @param sendMessages     usa los mensajes normales de /graveback
     */
    private GraveBackResult attemptGraveBack(Player player, boolean bypassEnabled, boolean bypassPermission,
                                             boolean bypassCooldown, boolean sendMessages) {
        if (!bypassEnabled && !getConfig().getBoolean("utilities.back-grave.enabled", true)) {
            if (sendMessages) send(player, "messages.back-disabled", Map.of());
            return GraveBackResult.DISABLED;
        }
        if (!bypassPermission && !player.hasPermission("mdvgraves.back")) {
            if (sendMessages) send(player, "messages.no-permission", Map.of());
            return GraveBackResult.NO_PERMISSION;
        }

        long cooldownSeconds = Math.max(0L, getConfig().getLong("utilities.back-grave.cooldown-seconds", 30L));
        if (!bypassCooldown && cooldownSeconds > 0L && !player.hasPermission("mdvgraves.back.cooldown.bypass")) {
            long now = System.currentTimeMillis();
            long availableAt = graveBackCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (availableAt > now) {
                long remaining = Math.max(1L, (availableAt - now + 999L) / 1000L);
                if (sendMessages) send(player, "messages.back-cooldown", Map.of("seconds", Long.toString(remaining)));
                return GraveBackResult.COOLDOWN;
            }
        }

        GraveMeta latest = latestGrave(player.getUniqueId());
        if (latest == null) {
            if (sendMessages) send(player, "messages.back-no-graves", Map.of());
            return GraveBackResult.NO_GRAVES;
        }

        World world = Bukkit.getWorld(latest.world());
        if (world == null) {
            if (sendMessages) send(player, "messages.back-world-unavailable", Map.of("world", latest.world()));
            return GraveBackResult.WORLD_UNAVAILABLE;
        }

        world.getChunkAt(latest.x() >> 4, latest.z() >> 4).load();
        int radius = Math.max(0, getConfig().getInt("utilities.back-grave.safe-search-radius", 3));
        Location destination = findSafeTeleportLocation(latest, world, radius, player.getLocation());
        if (destination == null) {
            if (sendMessages) send(player, "messages.back-no-safe-location", Map.of(
                    "world", latest.world(),
                    "x", Integer.toString(latest.x()),
                    "y", Integer.toString(latest.y()),
                    "z", Integer.toString(latest.z())));
            return GraveBackResult.NO_SAFE_LOCATION;
        }

        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.COMMAND)) {
            if (sendMessages) send(player, "messages.back-teleport-failed", Map.of());
            return GraveBackResult.TELEPORT_FAILED;
        }

        if (!bypassCooldown && cooldownSeconds > 0L && !player.hasPermission("mdvgraves.back.cooldown.bypass")) {
            graveBackCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }

        if (sendMessages) {
            playConfiguredSound(player, "utilities.back-grave.sound", "entity.enderman.teleport");
            send(player, "messages.back-teleported", graveLocationPlaceholders(latest));
        }
        return GraveBackResult.SUCCESS;
    }

    private GraveMeta latestGrave(UUID owner) {
        return graves.values().stream()
                .filter(meta -> meta.ownerUuid().equals(owner))
                .max(Comparator.comparingLong(GraveMeta::createdAt))
                .orElse(null);
    }

    private Map<String, String> graveLocationPlaceholders(GraveMeta meta) {
        return Map.of(
                "world", meta.world(),
                "x", Integer.toString(meta.x()),
                "y", Integer.toString(meta.y()),
                "z", Integer.toString(meta.z()));
    }

    private void playConfiguredSound(Player player, String path, String fallback) {
        String sound = getConfig().getString(path, fallback);
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    /**
     * Comando interno para la Fruta de la Muerte. Debe ejecutarlo la consola desde
     * una acción del CONSUMABLE de MMOItems. La fruta NO debe autoconsumirse en MI:
     * MDVGraves quita exactamente una unidad solo después de un teleport exitoso.
     */
    private boolean executeDeathFruit(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            send(sender, "messages.death-fruit-console-only", Map.of());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&cUso: /mdvgraves deathfruit <jugador>"));
            return true;
        }
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true)) return true;

        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            send(sender, "messages.player-not-found", Map.of("player", args[1]));
            return true;
        }

        long now = System.currentTimeMillis();
        long lockMs = Math.max(0L, getConfig().getLong("utilities.death-fruit.use-lock-ms", 750L));
        long lockedUntil = deathFruitUseLocks.getOrDefault(player.getUniqueId(), 0L);
        if (lockedUntil > now) return true;
        if (lockMs > 0L) deathFruitUseLocks.put(player.getUniqueId(), now + lockMs);

        PendingDeathFruitUse pending = resolveDeathFruitUse(player, now);
        if (pending == null) {
            send(player, "messages.death-fruit-not-held", Map.of());
            return true;
        }
        boolean alreadyConsumedByMmoItems = wasFruitAlreadyConsumed(player, pending);

        GraveBackResult result = attemptGraveBack(player, true, true, true, false);
        if (result != GraveBackResult.SUCCESS) {
            // Seguridad extra: si la versión/config de MMOItems descontó el consumible
            // antes de ejecutar el comando, se devuelve exactamente 1 unidad al fallar.
            if (alreadyConsumedByMmoItems) refundDeathFruit(player, pending.snapshot());
            sendDeathFruitFailure(player, result);
            return true;
        }

        // Si MMOItems ya descontó una unidad no tocamos el stack. Si no lo hizo
        // (config recomendada), MDVGraves consume exactamente una tras el teleport.
        if (!alreadyConsumedByMmoItems && !consumeDeathFruit(player, pending.hand())) {
            getLogger().warning("La Fruta de la Muerte de " + player.getName()
                    + " no pudo consumirse después de un graveback exitoso.");
        }

        playConfiguredSound(player, "utilities.death-fruit.sound", "entity.enderman.teleport");
        send(player, "messages.death-fruit-success", Map.of());
        return true;
    }

    private void sendDeathFruitFailure(Player player, GraveBackResult result) {
        switch (result) {
            case NO_GRAVES -> send(player, "messages.death-fruit-no-graves", Map.of());
            case WORLD_UNAVAILABLE -> {
                GraveMeta latest = latestGrave(player.getUniqueId());
                send(player, "messages.back-world-unavailable",
                        latest == null ? Map.of("world", "?") : Map.of("world", latest.world()));
            }
            case NO_SAFE_LOCATION -> {
                GraveMeta latest = latestGrave(player.getUniqueId());
                if (latest == null) send(player, "messages.death-fruit-no-graves", Map.of());
                else send(player, "messages.back-no-safe-location", graveLocationPlaceholders(latest));
            }
            case TELEPORT_FAILED -> send(player, "messages.back-teleport-failed", Map.of());
            default -> send(player, "messages.death-fruit-failed", Map.of());
        }
    }

    private PendingDeathFruitUse resolveDeathFruitUse(Player player, long now) {
        PendingDeathFruitUse pending = pendingDeathFruitUses.remove(player.getUniqueId());
        long maxAge = Math.max(250L, getConfig().getLong("utilities.death-fruit.pending-use-max-age-ms", 2000L));
        if (pending != null && now - pending.createdAt() <= maxAge && isConfiguredDeathFruit(pending.snapshot())) {
            return pending;
        }

        EquipmentSlot hand = findDeathFruitHand(player);
        if (hand == null) return null;
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        return new PendingDeathFruitUse(hand, stack.clone(), stack.getAmount(), now);
    }

    private boolean wasFruitAlreadyConsumed(Player player, PendingDeathFruitUse pending) {
        ItemStack current = pending.hand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isConfiguredDeathFruit(current)) return true;
        return current.getAmount() < pending.originalAmount();
    }

    private void refundDeathFruit(Player player, ItemStack snapshot) {
        ItemStack refund = snapshot.clone();
        refund.setAmount(1);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(refund);
        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
    }

    private EquipmentSlot findDeathFruitHand(Player player) {
        if (isConfiguredDeathFruit(player.getInventory().getItemInMainHand())) return EquipmentSlot.HAND;
        if (isConfiguredDeathFruit(player.getInventory().getItemInOffHand())) return EquipmentSlot.OFF_HAND;
        return null;
    }

    private boolean consumeDeathFruit(Player player, EquipmentSlot hand) {
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isConfiguredDeathFruit(stack)) return false;
        if (stack.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
        }
        return true;
    }

    private void setupMmoItemsBridge() {
        mmoItemsBridgeReady = false;
        mmoItemsBridgeWarningLogged = false;
        nbtItemGetMethod = null;
        nbtItemGetStringMethod = null;
        deathFruitExpectedType = Optional.ofNullable(getConfig().getString("utilities.death-fruit.mmoitems-type", "CONSUMABLE"))
                .orElse("CONSUMABLE");
        deathFruitExpectedId = Optional.ofNullable(getConfig().getString("utilities.death-fruit.mmoitems-id", "FRUTA_DE_LA_MUERTE"))
                .orElse("FRUTA_DE_LA_MUERTE");
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true)) return;
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null
                || Bukkit.getPluginManager().getPlugin("MythicLib") == null) {
            getLogger().warning("Fruta de la Muerte activada, pero MMOItems/MythicLib no están disponibles.");
            return;
        }
        try {
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtItemGetMethod = nbtItemClass.getMethod("get", ItemStack.class);
            nbtItemGetStringMethod = nbtItemClass.getMethod("getString", String.class);
            mmoItemsBridgeReady = true;
        } catch (ReflectiveOperationException ex) {
            getLogger().log(Level.WARNING, "No se pudo inicializar el puente MMOItems/MythicLib para la Fruta de la Muerte.", ex);
        }
    }

    private boolean isConfiguredDeathFruit(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta() || !mmoItemsBridgeReady) return false;
        try {
            Object nbt = nbtItemGetMethod.invoke(null, stack);
            String type = String.valueOf(nbtItemGetStringMethod.invoke(nbt, "MMOITEMS_ITEM_TYPE"));
            String id = String.valueOf(nbtItemGetStringMethod.invoke(nbt, "MMOITEMS_ITEM_ID"));
            return deathFruitExpectedType.equalsIgnoreCase(type)
                    && deathFruitExpectedId.equalsIgnoreCase(id);
        } catch (ReflectiveOperationException ex) {
            if (!mmoItemsBridgeWarningLogged) {
                mmoItemsBridgeWarningLogged = true;
                getLogger().log(Level.WARNING, "No se pudo leer un MMOItem para la Fruta de la Muerte.", ex);
            }
            return false;
        }
    }

    private Location findSafeTeleportLocation(GraveMeta meta, World world, int radius, Location facing) {
        int baseX = meta.x();
        int baseY = meta.y() + 1;
        int baseZ = meta.z();

        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = 0; dy <= 3; dy++) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (isSafeStandingSpot(world, x, y, z, meta.id())) {
                            return new Location(world, x + 0.5, y, z + 0.5, facing.getYaw(), facing.getPitch());
                        }
                    }
                    for (int dy = -1; dy >= -3; dy--) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (isSafeStandingSpot(world, x, y, z, meta.id())) {
                            return new Location(world, x + 0.5, y, z + 0.5, facing.getYaw(), facing.getPitch());
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeStandingSpot(World world, int x, int y, int z, UUID graveId) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable()) return false;
        return floor.getType().isSolid() || graveId.equals(trackedGraveId(floor));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("graveback")) {
            if (args.length >= 1) {
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)
                        && !sender.hasPermission("mdvgraves.back.others")
                        && !sender.hasPermission("mdvgraves.admin")) {
                    send(sender, "messages.no-permission", Map.of());
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    send(sender, "messages.player-not-found", Map.of("player", args[0]));
                    return true;
                }
                GraveBackResult result = attemptGraveBack(target, true, true, true, true);
                if (result == GraveBackResult.SUCCESS) {
                    send(sender, "messages.back-other-success", Map.of("player", target.getName()));
                } else {
                    send(sender, "messages.back-other-failed", Map.of("player", target.getName()));
                }
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cUso desde consola: /graveback <jugador>"));
                return true;
            }
            return executeGraveBack(player);
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("deathfruit")
                || args[0].equalsIgnoreCase("fruta")
                || args[0].equalsIgnoreCase("frutamuerte"))) {
            return executeDeathFruit(sender, args);
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("back")
                || args[0].equalsIgnoreCase("volver") || args[0].equalsIgnoreCase("regresar"))) {
            if (args.length >= 2) {
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)
                        && !sender.hasPermission("mdvgraves.back.others")
                        && !sender.hasPermission("mdvgraves.admin")) {
                    send(sender, "messages.no-permission", Map.of());
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    send(sender, "messages.player-not-found", Map.of("player", args[1]));
                    return true;
                }
                GraveBackResult result = attemptGraveBack(target, true, true, true, true);
                if (result == GraveBackResult.SUCCESS) {
                    send(sender, "messages.back-other-success", Map.of("player", target.getName()));
                } else {
                    send(sender, "messages.back-other-failed", Map.of("player", target.getName()));
                }
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cUso: /mdvgraves back <jugador>"));
                return true;
            }
            return executeGraveBack(player);
        }

        if (!sender.hasPermission("mdvgraves.admin")) {
            send(sender, "messages.no-permission", Map.of());
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(color("&6MDVGraves &f1.0.8 &7| Bolsas activas: &e" + graves.size()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            setupMmoItemsBridge();
            scheduleCleanup();
            scheduleOpenGraveIntegrityGuard();
            send(sender, "messages.reload", Map.of());
            return true;
        }
        if (args[0].equalsIgnoreCase("cleanup")) {
            int count = cleanupExpired(true);
            send(sender, "messages.cleanup", Map.of("count", Integer.toString(count)));
            return true;
        }
        if (args[0].equalsIgnoreCase("deleteall") || args[0].equalsIgnoreCase("purge")
                || args[0].equalsIgnoreCase("eliminartodas")) {
            if (args.length < 2 || !(args[1].equalsIgnoreCase("confirm") || args[1].equalsIgnoreCase("confirmar"))) {
                send(sender, "messages.delete-all-confirm", Map.of("count", Integer.toString(graves.size())));
                return true;
            }
            try {
                int count = deleteAllGraves();
                send(sender, "messages.delete-all-done", Map.of("count", Integer.toString(count)));
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "No se pudieron eliminar todas las bolsas.", ex);
                send(sender, "messages.delete-all-error", Map.of());
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("graveback")) {
            if (args.length == 1 && (sender instanceof org.bukkit.command.ConsoleCommandSender
                    || sender.hasPermission("mdvgraves.back.others") || sender.hasPermission("mdvgraves.admin"))) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
            }
            return List.of();
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("back")
                || args[0].equalsIgnoreCase("volver") || args[0].equalsIgnoreCase("regresar"))
                && (sender instanceof org.bukkit.command.ConsoleCommandSender
                || sender.hasPermission("mdvgraves.back.others") || sender.hasPermission("mdvgraves.admin"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }

        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>();
        if (sender.hasPermission("mdvgraves.back")) options.add("back");
        if (sender.hasPermission("mdvgraves.admin")) {
            options.add("info");
            options.add("reload");
            options.add("cleanup");
            options.add("deleteall");
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }

    private int deleteAllGraves() throws SQLException {
        // Cierra primero cualquier bolsa abierta. InventoryCloseEvent persiste su estado
        // antes del borrado global, evitando carreras o inventarios visuales obsoletos.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder) {
                player.closeInventory();
            }
        }

        List<GraveMeta> snapshot = new ArrayList<>(graves.values());
        int deleted;
        // Una sola operación SQLite: más barata que borrar tumba por tumba.
        try (Statement st = connection.createStatement()) {
            deleted = st.executeUpdate("DELETE FROM graves");
        }

        graves.clear();
        gravesByBlock.clear();
        activeViewers.clear();
        openGraveInventories.clear();
        forcedClosingGraves.clear();

        // Solo toca bloques en chunks ya cargados. Los chunks descargados no se fuerzan;
        // sus cabezas huérfanas serán retiradas por ChunkLoadEvent cuando vuelvan a cargar.
        for (GraveMeta meta : snapshot) {
            World world = Bukkit.getWorld(meta.world());
            if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4)) continue;
            Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
            if (isOurHead(block, meta.id())) block.setType(Material.AIR, false);
        }
        return deleted;
    }

    private enum GraveBackResult {
        SUCCESS,
        DISABLED,
        NO_PERMISSION,
        COOLDOWN,
        NO_GRAVES,
        WORLD_UNAVAILABLE,
        NO_SAFE_LOCATION,
        TELEPORT_FAILED
    }

    private record SupportPatch(Block block, BlockData original) { }

    private record PendingDeathFruitUse(EquipmentSlot hand, ItemStack snapshot, int originalAmount, long createdAt) { }

    private record BlockKey(String world, int x, int y, int z) { }

    private record GraveMeta(UUID id, UUID ownerUuid, String ownerName, String world, int x, int y, int z,
                             long createdAt, Long firstOpenedAt, long expiresAt, boolean ownerProtected) {
        BlockKey blockKey() { return new BlockKey(world, x, y, z); }
        GraveMeta withOpened(long opened, long expires) {
            return new GraveMeta(id, ownerUuid, ownerName, world, x, y, z, createdAt, opened, expires, ownerProtected);
        }
    }

    private static final class GraveHolder implements InventoryHolder {
        private final UUID graveId;
        private Inventory inventory;
        private GraveHolder(UUID graveId) { this.graveId = graveId; }
        UUID graveId() { return graveId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
