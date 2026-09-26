package pl.dowimixworsafe.betterlistintegration;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PortableShulkerService implements Listener {
    private static final NamespacedKey KEY = new NamespacedKey("betterlist", "shulker_id");
    private final JavaPlugin plugin;
    private final PartyManager parties;
    private java.nio.file.Path lossFile;
    private final Map<String, JsonObject> states = new HashMap<>();
    private final Map<String, Set<UUID>> watchers = new HashMap<>();
    private final Map<String, String> locations = new HashMap<>();
    private final Map<Location, String> breaking = new HashMap<>();

    public PortableShulkerService(JavaPlugin plugin, PartyManager parties) {
        this.plugin = plugin;
        this.parties = parties;
        lossFile = plugin.getDataFolder().toPath().resolve("lost_shulkers.json");
        loadLosses();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    private void loadLosses() {
        if (!java.nio.file.Files.exists(lossFile)) return;
        try {
            var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(lossFile)).getAsJsonObject();
            saved.entrySet().forEach(entry -> {
                if (valid(entry.getKey()) && entry.getValue().isJsonObject()
                        && "lost".equals(entry.getValue().getAsJsonObject().get("state").getAsString()))
                    states.put(entry.getKey(), entry.getValue().getAsJsonObject());
            });
        } catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to persist shulker history", e); }
    }

    private void saveLosses() {
        if (lossFile == null) return;
        try {
            JsonObject saved = new JsonObject();
            states.forEach((id, state) -> { if ("lost".equals(state.get("state").getAsString())) saved.add(id, state); });
            java.nio.file.Files.createDirectories(lossFile.getParent());
            var temporary = lossFile.resolveSibling(lossFile.getFileName() + ".tmp");
            java.nio.file.Files.writeString(temporary, saved.toString());
            java.nio.file.Files.move(temporary, lossFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to persist shulker history", e); }
    }

    private static boolean valid(String id) {
        if (id == null || !id.startsWith("shulker:")) return false;
        try { return id.equals("shulker:" + UUID.fromString(id.substring(8))); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static String id(ShulkerBox box) { return box.getPersistentDataContainer().get(KEY, PersistentDataType.STRING); }

    private static String id(ItemStack stack) {
        if (stack == null || !(stack.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) return null;
        String id = meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return valid(id) ? id : id(box);
    }

    private static void stamp(ItemStack stack, String id) {
        if (!(stack.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox box)) return;
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        box.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        meta.setBlockState(box);
        stack.setItemMeta(meta);
    }

    public void handle(Player player, JsonObject request) {
        String type = request.get("type").getAsString();
        if (type.equals("SHULKER_BIND")) {
            Location location = parse(request.get("location").getAsString());
            if (location == null || location.getWorld() != player.getWorld()
                    || player.getLocation().distanceSquared(location.clone().add(.5, .5, .5)) > 64
                    || !loaded(location) || !(location.getBlock().getState() instanceof ShulkerBox box)
                    || !(player.getOpenInventory().getTopInventory().getHolder() instanceof ShulkerBox open)
                    || !open.getLocation().equals(location)) return;
            String id = id(box);
            if (!valid(id)) {
                id = "shulker:" + UUID.randomUUID();
                box.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
                box.update(false, false);
            }
            JsonObject state = state(box);
            publish(state);
            boolean track = request.get("track").getAsBoolean();
            if (track) watchers.computeIfAbsent(id, k -> new HashSet<>()).add(player.getUniqueId());
            JsonObject reply = state.deepCopy();
            reply.addProperty("track", track);
            if (request.has("request")) reply.add("request", request.get("request"));
            send(player, reply);
        } else if (type.equals("SHULKER_SUBSCRIBE")) {
            String id = request.get("id").getAsString();
            if (!valid(id)) return;
            Set<UUID> members = watchers.computeIfAbsent(id, k -> new HashSet<>());
            if (!request.get("subscribe").getAsBoolean()) { members.remove(player.getUniqueId()); return; }
            members.add(player.getUniqueId());
            if (request.has("location")) {
                locations.putIfAbsent(id, request.get("location").getAsString());
                Location location = parse(request.get("location").getAsString());
                if (location != null && loaded(location) && location.getBlock().getState() instanceof ShulkerBox box
                        && id.equals(id(box)) && !(states.containsKey(id) && "lost".equals(states.get(id).get("state").getAsString()))) states.put(id, state(box));
            }
            if (states.containsKey(id)) send(player, states.get(id));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { rememberDrop(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) { event.blockList().forEach(this::rememberDrop); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) { event.blockList().forEach(this::rememberDrop); }

    private void rememberDrop(org.bukkit.block.Block block) {
        if (!(block.getState() instanceof ShulkerBox box) || !valid(id(box))) return;
        Location location = block.getLocation();
        String id = id(box);
        breaking.put(location, id);
        Bukkit.getScheduler().runTask(plugin, () -> breaking.remove(location, id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (!(stack.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox)) return;
        String id = breaking.remove(event.getLocation().getBlock().getLocation());
        if (id == null) return;
        stamp(stack, id);
        event.getEntity().setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState() instanceof ShulkerBox box) || !valid(id(box))) return;
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            stamp(stack, id(box));
            item.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String id = id(event.getItemInHand());
        if (!valid(id)) return;
        Location location = event.getBlockPlaced().getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (location.getBlock().getState() instanceof ShulkerBox box) {
                box.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
                box.update(false, false);
                publish(state(box));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(org.bukkit.event.entity.EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        String reason = switch (event.getCause()) {
            case DESPAWN -> "despawn";
            case OUT_OF_WORLD -> "void";
            case DEATH, EXPLODE, HIT -> "destroyed";
            default -> null;
        };
        if (reason == null) return;
        if ("destroyed".equals(reason) && item.getLastDamageCause() != null) {
            reason = switch (item.getLastDamageCause().getCause()) {
                case FIRE, FIRE_TICK, LAVA -> "fire";
                case VOID -> "void";
                default -> reason;
            };
        }
        String id = id(item.getItemStack());
        if (!valid(id)) return;
        ShulkerBox box = (ShulkerBox) ((BlockStateMeta) item.getItemStack().getItemMeta()).getBlockState();
        JsonObject lost = packet(id, "lost", location(item.getLocation()), "", items(box.getInventory().getContents()));
        lost.addProperty("reason", reason);
        lost.addProperty("lostAt", System.currentTimeMillis());
        publish(lost);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { watchers.values().forEach(set -> set.remove(event.getPlayer().getUniqueId())); }

    private static boolean loaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static String location(Location location) {
        return location.getWorld().getKey() + ";" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static Location parse(String text) {
        int semicolon = text.indexOf(';');
        if (semicolon < 0) return null;
        try {
            World world = Bukkit.getWorld(NamespacedKey.fromString(text.substring(0, semicolon)));
            if (world == null) return null;
            String[] xyz = text.substring(semicolon + 1).replace("[", "").replace("]", "").split(",");
            if (xyz.length != 3) return null;
            return new Location(world, Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (IllegalArgumentException e) { return null; }
    }

    private static JsonObject items(ItemStack[] stacks) {
        JsonObject result = new JsonObject();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;
            String item = stack.getType().getKey().toString();
            result.addProperty(item, stack.getAmount() + (result.has(item) ? result.get(item).getAsInt() : 0));
        }
        return result;
    }

    private static JsonObject packet(String id, String state, String location, String holder, JsonObject items) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "SHULKER_STATE");
        result.addProperty("v", "5");
        result.addProperty("id", id);
        result.addProperty("state", state);
        result.addProperty("location", location);
        result.addProperty("holder", holder);
        result.add("items", items);
        return result;
    }

    private static JsonObject state(ShulkerBox box) {
        return packet(id(box), "placed", location(box.getLocation()), "", items(box.getInventory().getContents()));
    }

    private void observe(Map<String, JsonObject> seen, ItemStack stack, String state, String holder) {
        String id = id(stack);
        if (!valid(id) || !watchers.containsKey(id)) return;
        ShulkerBox box = (ShulkerBox) ((BlockStateMeta) stack.getItemMeta()).getBlockState();
        seen.put(id, packet(id, state, "", holder, items(box.getInventory().getContents())));
    }

    private void tick() {
        if (watchers.values().stream().allMatch(Set::isEmpty)) return;
        Map<String, JsonObject> seen = new HashMap<>();
        for (var entry : locations.entrySet()) {
            String id = entry.getKey();
            if (watchers.getOrDefault(id, Set.of()).isEmpty()) continue;
            JsonObject old = states.get(id);
            Location location = parse(entry.getValue());
            if (location == null || !loaded(location)) continue;
            if (location.getBlock().getState() instanceof ShulkerBox box && id.equals(id(box))) seen.put(id, state(box));
            else if (old != null && "placed".equals(old.get("state").getAsString()))
                seen.put(id, packet(id, "unknown", "", "", old.getAsJsonObject("items").deepCopy()));
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) observe(seen, item.getItemStack(), "dropped", "");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack stack : player.getInventory().getContents()) observe(seen, stack, "carried", player.getName());
            observe(seen, player.getItemOnCursor(), "carried", player.getName());
            for (ItemStack stack : player.getOpenInventory().getTopInventory().getContents()) observe(seen, stack, "unknown", "");
        }
        for (var entry : states.entrySet()) {
            JsonObject old = entry.getValue();
            if (!seen.containsKey(entry.getKey()) && Set.of("carried", "dropped").contains(old.get("state").getAsString()))
                seen.put(entry.getKey(), packet(entry.getKey(), "unknown", "", "", old.getAsJsonObject("items").deepCopy()));
        }
        seen.values().forEach(this::publish);
    }

    private void publish(JsonObject state) {
        String id = state.get("id").getAsString();
        if (states.containsKey(id) && "lost".equals(states.get(id).get("state").getAsString())) return;
        if ("placed".equals(state.get("state").getAsString())) locations.put(id, state.get("location").getAsString());
        if (state.equals(states.put(id, state))) return;
        if ("lost".equals(state.get("state").getAsString())) saveLosses();
        for (UUID uuid : watchers.getOrDefault(id, Set.of())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) send(player, state);
        }
    }

    private void send(Player player, JsonObject state) { parties.sendPacket(player, "betterlist:sync", state); }
}
