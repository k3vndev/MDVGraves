package xyz.mdvcraft.mdvgraves;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
    private NamespacedKey graveKey;
    private Connection connection;
    private final Map<UUID, GraveMeta> graves = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> gravesByBlock = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeViewers = new HashMap<>();
    private BukkitTask cleanupTask;

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
        scheduleCleanup();
        getLogger().info("MDVGraves 1.0.5 activo. Bolsas cargadas: " + graves.size());
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) cleanupTask.cancel();
        // Guarda inventarios que todavía estén abiertos antes del cierre.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder holder) {
                saveInventoryAndMaybeRemove(holder.graveId(), player.getOpenInventory().getTopInventory(), false);
            }
        }
        activeViewers.clear();
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
                  items BLOB NOT NULL
                )
                """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_graves_expires ON graves(expires_at)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_graves_location ON graves(world,x,y,z)");
        }
    }

    private void loadActiveGraves() throws SQLException {
        graves.clear();
        gravesByBlock.clear();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at FROM graves")) {
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
        GraveMeta meta = new GraveMeta(id, player.getUniqueId(), player.getName(), target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), created, null, expires);

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
            getLogger().log(Level.SEVERE, "No se pudo crear la bolsa de " + player.getName() + ". Se conservaron drops vanilla.", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        // Bukkit dispara PlayerInteractEvent una vez por cada mano. Solo procesamos la principal
        // para evitar abrir la misma bolsa y enviar mensajes dos veces.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        UUID id = graveId(event.getClickedBlock());
        if (id == null) return;
        event.setCancelled(true);

        GraveMeta meta = graves.get(id);
        if (meta == null) {
            event.getClickedBlock().setType(Material.AIR, false);
            return;
        }
        Player player = event.getPlayer();
        if (getConfig().getBoolean("settings.only-owner-can-open", false)
                && !meta.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("mdvgraves.admin")) {
            send(player, "messages.not-owner", Map.of("owner", meta.ownerName()));
            return;
        }
        UUID viewer = activeViewers.get(id);
        if (getConfig().getBoolean("settings.single-viewer-lock", true) && viewer != null && !viewer.equals(player.getUniqueId())) {
            send(player, "messages.grave-busy", Map.of("owner", meta.ownerName()));
            return;
        }

        try {
            List<ItemStack> items = loadItems(id);
            int size = inventorySize(items.size());
            String title = color(getConfig().getString("inventory.title", "&8Bolsa perdida de &e{owner}"))
                    .replace("{owner}", meta.ownerName());
            GraveHolder holder = new GraveHolder(id);
            Inventory inventory = Bukkit.createInventory(holder, size, title);
            holder.inventory = inventory;
            for (ItemStack item : items) inventory.addItem(item.clone());

            activeViewers.put(id, player.getUniqueId());
            markFirstOpened(meta);
            player.openInventory(inventory);
            send(player, "messages.grave-opened", Map.of("owner", meta.ownerName()));
        } catch (Exception ex) {
            activeViewers.remove(id);
            getLogger().log(Level.SEVERE, "No se pudo abrir la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GraveHolder holder)) return;
        UUID id = holder.graveId();
        activeViewers.remove(id, event.getPlayer().getUniqueId());
        saveInventoryAndMaybeRemove(id, event.getInventory(), true);
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
        UUID id = graveId(event.getBlock());
        if (id == null) return;
        event.setCancelled(true);
        event.setDropItems(false);
        breakGrave(event.getPlayer(), id);
    }

    private void breakGrave(Player breaker, UUID id) {
        GraveMeta meta = graves.get(id);
        if (meta == null) return;
        UUID viewerId = activeViewers.get(id);
        if (viewerId != null) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder holder
                    && holder.graveId().equals(id)) {
                // InventoryCloseEvent guarda primero el contenido actual. Después se carga esa versión para dropearla.
                viewer.closeInventory();
            }
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
            affectedBlocks.removeIf(block -> graveId(block) != null);
            return;
        }

        Set<UUID> graveIds = new LinkedHashSet<>();
        affectedBlocks.removeIf(block -> {
            UUID id = graveId(block);
            if (id == null) return false;
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
        if (getConfig().getBoolean("settings.protect-from-fluids", true)
                && graveId(event.getToBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (getConfig().getBoolean("settings.protect-from-pistons", true)
                && event.getBlocks().stream().anyMatch(block -> graveId(block) != null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (getConfig().getBoolean("settings.protect-from-pistons", true)
                && event.getBlocks().stream().anyMatch(block -> graveId(block) != null)) event.setCancelled(true);
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
            if (activeViewers.containsKey(id)) continue;
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
            INSERT INTO graves(grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at,items)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
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

    private boolean canReplace(Block block) {
        Material type = block.getType();
        return type.isAir() || type == Material.WATER || type == Material.LAVA || type == Material.FIRE
                || type == Material.SOUL_FIRE || type == Material.TALL_GRASS || type == Material.SHORT_GRASS
                || type == Material.SNOW || type == Material.VINE || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT;
    }

    private UUID graveId(Block block) {
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        UUID cached = gravesByBlock.get(key);
        if (cached != null) return cached;
        if (!(block.getState() instanceof Skull skull)) return null;
        String raw = skull.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean isOurHead(Block block, UUID id) {
        UUID found = graveId(block);
        return id.equals(found);
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
                rs.getLong("created_at"), opened, rs.getLong("expires_at"));
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mdvgraves.admin")) {
            send(sender, "messages.no-permission", Map.of());
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(color("&6MDVGraves &f1.0.5 &7| Bolsas activas: &e" + graves.size()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            scheduleCleanup();
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

    private record BlockKey(String world, int x, int y, int z) { }

    private record GraveMeta(UUID id, UUID ownerUuid, String ownerName, String world, int x, int y, int z,
                             long createdAt, Long firstOpenedAt, long expiresAt) {
        BlockKey blockKey() { return new BlockKey(world, x, y, z); }
        GraveMeta withOpened(long opened, long expires) {
            return new GraveMeta(id, ownerUuid, ownerName, world, x, y, z, createdAt, opened, expires);
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
