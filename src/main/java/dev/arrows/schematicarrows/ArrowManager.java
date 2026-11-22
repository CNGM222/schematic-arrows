package dev.arrows.schematicarrows;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds the schematic blocks that still have to be placed and the ones that were
 * placed wrongly, with a per-chunk cache that is independent of the player.
 *
 * <p>The whole placement is cached, not a radius around the player, so turning,
 * walking or flying never removes a marker. A chunk is only rescanned when:</p>
 * <ul>
 *   <li>a block in it changed (the printer placed something, or the player did),</li>
 *   <li>it just loaded, or the placement / settings changed.</li>
 * </ul>
 *
 * <p>Chunk scans run on a small worker pool; the client thread only submits the
 * chunk bounds and merges the finished results. Inventory changes never scan:
 * the merge step filters the cached targets by what the player is carrying.</p>
 */
public final class ArrowManager {
    public enum Kind {
        MISSING,
        WRONG,
        EXTRA
    }

    /** One block position that should be highlighted. */
    public record Target(BlockPos pos, Item item, Kind kind, double distSq) {
    }

    /** Cached scan result of one 16x16 chunk column. */
    private record ChunkScan(List<Target> targets, int scannedAt) {
    }

    /** A box clipped to one chunk, ready to be walked by a worker thread. */
    private record Bounds(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    private record ChunkTask(long key, int generation, int chunkX, int chunkZ,
                             List<Bounds> regions, boolean showWrong, boolean showExtra) {
    }

    private record ChunkResult(long key, int generation, List<Target> targets) {
    }

    /** Absolute box, collected once per tick on the client thread. */
    private record BoxBounds(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-arrows");
    private static final int MERGE_INTERVAL_TICKS = 2;
    private static final Map<Long, ChunkScan> CACHE = new HashMap<>();
    private static final Set<Long> DIRTY = new LinkedHashSet<>();
    private static final ArrayList<Target> TARGETS = new ArrayList<>();
    private static final ConcurrentLinkedQueue<ChunkResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final Set<Long> IN_FLIGHT_KEYS = ConcurrentHashMap.newKeySet();

    private static ExecutorService executor;
    private static int executorThreads = -1;
    private static int generation;

    private static int hintTicks = -1;
    private static boolean failureReported;
    private static int tick;
    private static int lastConfigSignature = Integer.MIN_VALUE;
    private static long lastPlacementSignature = Long.MIN_VALUE;
    private static ClientWorld lastWorld;
    private static BlockPos lastPlayerPos;
    private static Map<Item, Integer> lastCounts = Map.of();
    private static int lastMergeTick;
    private static boolean needsMerge = true;

    private ArrowManager() {
    }

    public static List<Target> targets() {
        return TARGETS;
    }

    /** Called from the block-update hook: the chunk's contents may have changed. */
    public static void markDirty(int chunkX, int chunkZ) {
        DIRTY.add(ChunkPos.toLong(chunkX, chunkZ));
    }

    public static void forgetChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        CACHE.remove(key);
        DIRTY.remove(key);
        needsMerge = true;
    }

    public static void clear() {
        generation++;
        CACHE.clear();
        DIRTY.clear();
        TARGETS.clear();
        RESULTS.clear();
        IN_FLIGHT_KEYS.clear();
        IN_FLIGHT.set(0);
        lastWorld = null;
        lastConfigSignature = Integer.MIN_VALUE;
        lastPlacementSignature = Long.MIN_VALUE;
        lastPlayerPos = null;
        lastCounts = Map.of();
        lastMergeTick = 0;
        needsMerge = true;
    }

    /** Called when the player toggles the arrows on: reminds them if nothing shows up. */
    public static void onToggled(boolean enabled) {
        hintTicks = enabled ? 60 : -1;
    }

    public static void tick(MinecraftClient client) {
        if (!SchematicArrowsClient.isEnabled() || client.player == null || client.world == null) {
            clear();
            hintTicks = -1;
            return;
        }
        if (hintTicks > 0 && --hintTicks == 0 && TARGETS.isEmpty() && client.player != null) {
            client.player.sendMessage(Text.translatable("schematic_arrows.status.empty"), true);
        }
        tick++;
        try {
            update(client);
        } catch (RuntimeException | LinkageError e) {
            clear();
            if (!failureReported) {
                failureReported = true;
                LOGGER.error("Failed to read the Litematica schematic placement", e);
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("schematic_arrows.status.error"), true);
                }
            }
        }
    }

    private static void update(MinecraftClient client) {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (schematicWorld == null || player == null || world == null) {
            clear();
            return;
        }
        ChunkManagerSchematic schematicChunks = schematicWorld.getChunkSource();
        if (schematicChunks == null) {
            clear();
            return;
        }
        ArrowsConfig config = ArrowsConfig.get();
        ensureExecutor(config.scanThreads);

        List<SchematicPlacement> placements = placements(config);
        boolean rebuild = world != lastWorld;
        if (rebuild) {
            lastWorld = world;
        }
        long placementSignature = placementSignature(placements);
        if (placementSignature != lastPlacementSignature) {
            lastPlacementSignature = placementSignature;
            rebuild = true;
        }
        int configSignature = configSignature(config);
        if (configSignature != lastConfigSignature) {
            lastConfigSignature = configSignature;
            rebuild = true;
        }
        if (rebuild) {
            generation++;
            CACHE.clear();
            DIRTY.clear();
            TARGETS.clear();
            RESULTS.clear();
            // Chunks with a scan still in flight would otherwise be lost.
            DIRTY.addAll(IN_FLIGHT_KEYS);
            IN_FLIGHT_KEYS.clear();
            IN_FLIGHT.set(0);
            needsMerge = true;
            enqueueForRadius(placements, schematicChunks, player, config.scanRadius);
        }

        drainResults();

        Map<Item, Integer> counts = availableCounts(player);
        if (!counts.equals(lastCounts)) {
            // Inventory changes never rescan: the merge step filters by the items carried.
            needsMerge = true;
            lastCounts = Map.copyOf(counts);
        }

        submitChunks(world, schematicWorld, schematicChunks, collectBoxes(placements, world), config);

        BlockPos playerPos = player.getBlockPos();
        boolean changedChunk = lastPlayerPos == null
                || (lastPlayerPos.getX() >> 4) != (playerPos.getX() >> 4)
                || (lastPlayerPos.getZ() >> 4) != (playerPos.getZ() >> 4);
        if (!playerPos.equals(lastPlayerPos)) {
            lastPlayerPos = playerPos;
            needsMerge = true;
        }
        if (changedChunk && config.scanRadius > 0) {
            enqueueForRadius(placements, schematicChunks, player, config.scanRadius);
        }
        if (needsMerge && tick - lastMergeTick >= MERGE_INTERVAL_TICKS) {
            merge(player, config, counts);
            lastMergeTick = tick;
            needsMerge = false;
        }
    }

    private static void drainResults() {
        ChunkResult result;
        while ((result = RESULTS.poll()) != null) {
            IN_FLIGHT.updateAndGet(value -> Math.max(0, value - 1));
            IN_FLIGHT_KEYS.remove(result.key());
            if (result.generation() != generation) {
                continue;
            }
            CACHE.put(result.key(), new ChunkScan(result.targets(), tick));
            needsMerge = true;
        }
    }

    private static void submitChunks(ClientWorld world, WorldSchematic schematicWorld,
                                     ChunkManagerSchematic schematicChunks,
                                     List<BoxBounds> boxes, ArrowsConfig config) {
        int threads = Math.max(0, config.scanThreads);
        int perTick = Math.max(1, config.chunksPerTick);
        int inFlightLimit = Math.max(threads, 1) * 4;
        int submitted = 0;
        Iterator<Long> iterator = DIRTY.iterator();
        while (iterator.hasNext() && submitted < perTick) {
            long key = iterator.next();
            iterator.remove();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            List<Bounds> regions = regionsForChunk(boxes, chunkX, chunkZ);
            if (regions.isEmpty()) {
                CACHE.put(key, new ChunkScan(List.of(), tick));
                needsMerge = true;
                submitted++;
                continue;
            }
            if (threads <= 0) {
                CACHE.put(key, new ChunkScan(scan(regions, schematicWorld, world,
                        config.showWrongBlocks, config.showExtraBlocks), tick));
                needsMerge = true;
                submitted++;
                continue;
            }
            if (IN_FLIGHT.get() >= inFlightLimit) {
                DIRTY.add(key);
                break;
            }
            ChunkTask task = new ChunkTask(key, generation, chunkX, chunkZ, regions,
                    config.showWrongBlocks, config.showExtraBlocks);
            IN_FLIGHT.incrementAndGet();
            IN_FLIGHT_KEYS.add(key);
            executor.execute(() -> {
                List<Target> targets;
                try {
                    if (schematicChunks.getChunkIfExists(task.chunkX, task.chunkZ) == null) {
                        targets = List.of();
                    } else {
                        targets = scan(task.regions, schematicWorld, world, task.showWrong, task.showExtra);
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("Chunk scan failed at {},{}", task.chunkX, task.chunkZ, e);
                    targets = List.of();
                }
                RESULTS.add(new ChunkResult(task.key, task.generation, targets));
            });
            submitted++;
        }
    }

    /** Collects every placement box once per tick; the clipping to chunks stays cheap. */
    private static List<BoxBounds> collectBoxes(List<SchematicPlacement> placements, ClientWorld world) {
        int worldMinY = world.getBottomY();
        int worldMaxY = world.getTopYInclusive();
        List<BoxBounds> boxes = new ArrayList<>();
        for (SchematicPlacement placement : placements) {
            if (!placement.isEnabled() || !placement.isRenderingEnabled()) {
                continue;
            }
            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values()) {
                BlockPos first = box.getPos1();
                BlockPos second = box.getPos2();
                if (first == null || second == null) {
                    continue;
                }
                int x1 = Math.min(first.getX(), second.getX());
                int x2 = Math.max(first.getX(), second.getX());
                int y1 = Math.max(Math.min(first.getY(), second.getY()), worldMinY);
                int y2 = Math.min(Math.max(first.getY(), second.getY()), worldMaxY);
                int z1 = Math.min(first.getZ(), second.getZ());
                int z2 = Math.max(first.getZ(), second.getZ());
                if (x1 <= x2 && y1 <= y2 && z1 <= z2) {
                    boxes.add(new BoxBounds(x1, y1, z1, x2, y2, z2));
                }
            }
        }
        return boxes;
    }

    private static List<Bounds> regionsForChunk(List<BoxBounds> boxes, int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        List<Bounds> regions = new ArrayList<>();
        for (BoxBounds box : boxes) {
            int x1 = Math.max(box.x1(), x0);
            int x2 = Math.min(box.x2(), x0 + 15);
            int z1 = Math.max(box.z1(), z0);
            int z2 = Math.min(box.z2(), z0 + 15);
            if (x1 <= x2 && z1 <= z2) {
                regions.add(new Bounds(x1, box.y1(), z1, x2, box.y2(), z2));
            }
        }
        return regions;
    }

    /** Runs on a worker thread: only block-state reads, no Litematica collections. */
    private static List<Target> scan(List<Bounds> regions, WorldSchematic schematicWorld, ClientWorld world,
                                     boolean showWrong, boolean showExtra) {
        List<Target> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (Bounds bounds : regions) {
            for (int y = bounds.y1(); y <= bounds.y2(); y++) {
                for (int z = bounds.z1(); z <= bounds.z2(); z++) {
                    for (int x = bounds.x1(); x <= bounds.x2(); x++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!seen.add(pos)) {
                            continue;
                        }
                        BlockState required = schematicWorld.getBlockState(pos);
                        BlockState current = world.getBlockState(pos);
                        if (current == required) {
                            continue;
                        }
                        boolean requiredIsEmpty = isEmpty(required);
                        boolean currentIsEmpty = isEmpty(current);
                        if (requiredIsEmpty) {
                            if (showExtra && !currentIsEmpty) {
                                found.add(new Target(pos, Items.AIR, Kind.EXTRA, 0.0));
                            }
                            continue;
                        }
                        if (currentIsEmpty) {
                            Item item = required.getBlock().asItem();
                            if (item != Items.AIR) {
                                found.add(new Target(pos, item, Kind.MISSING, 0.0));
                            }
                        } else if (showWrong) {
                            found.add(new Target(pos, required.getBlock().asItem(), Kind.WRONG, 0.0));
                        }
                    }
                }
            }
        }
        return found;
    }

    private static void enqueuePlacementChunks(List<SchematicPlacement> placements,
                                               ChunkManagerSchematic schematicChunks) {
        for (SchematicPlacement placement : placements) {
            if (!placement.isEnabled() || !placement.isRenderingEnabled()) {
                continue;
            }
            for (ChunkPos chunk : placement.getTouchedChunks()) {
                if (schematicChunks.getChunkIfExists(chunk.x, chunk.z) != null) {
                    DIRTY.add(ChunkPos.toLong(chunk.x, chunk.z));
                }
            }
        }
    }

    /**
     * Queues chunks to scan: the whole placement when {@code radius <= 0}, otherwise the
     * square around the player. Chunks that already have a cached scan are skipped, so
     * walking around does not rescan anything.
     */
    private static void enqueueForRadius(List<SchematicPlacement> placements, ChunkManagerSchematic schematicChunks,
                                         ClientPlayerEntity player, int radius) {
        if (radius <= 0) {
            enqueuePlacementChunks(placements, schematicChunks);
            return;
        }
        int minChunkX = (player.getBlockPos().getX() - radius) >> 4;
        int maxChunkX = (player.getBlockPos().getX() + radius) >> 4;
        int minChunkZ = (player.getBlockPos().getZ() - radius) >> 4;
        int maxChunkZ = (player.getBlockPos().getZ() + radius) >> 4;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                long key = ChunkPos.toLong(chunkX, chunkZ);
                if (!CACHE.containsKey(key)) {
                    DIRTY.add(key);
                }
            }
        }
    }

    private static void merge(ClientPlayerEntity player, ArrowsConfig config, Map<Item, Integer> counts) {
        List<Target> missing = new ArrayList<>();
        List<Target> wrong = new ArrayList<>();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        for (ChunkScan scan : CACHE.values()) {
            for (Target target : scan.targets()) {
                if (target.kind() == Kind.MISSING && counts.getOrDefault(target.item(), 0) <= 0) {
                    // The stack ran out since this chunk was scanned: hide the arrows now.
                    continue;
                }
                BlockPos pos = target.pos();
                double dx = pos.getX() + 0.5 - playerX;
                double dy = pos.getY() + 0.5 - playerY;
                double dz = pos.getZ() + 0.5 - playerZ;
                Target withDistance = new Target(pos, target.item(), target.kind(),
                        dx * dx + dy * dy + dz * dz);
                if (target.kind() == Kind.MISSING) {
                    missing.add(withDistance);
                } else {
                    wrong.add(withDistance);
                }
            }
        }
        Comparator<Target> byDistance = Comparator.comparingDouble(Target::distSq);
        missing.sort(byDistance);
        wrong.sort(byDistance);
        int maxWrong = Math.min(wrong.size(), Math.max(1, config.maxBadMarkers));
        int maxMissing = Math.max(1, config.maxArrows);
        TARGETS.clear();
        TARGETS.ensureCapacity(maxWrong + Math.min(missing.size(), maxMissing));
        // Wrongly placed blocks are the more urgent problem, so they are listed first.
        TARGETS.addAll(wrong.subList(0, maxWrong));
        // Never show more arrows for an item than the player can actually place.
        Map<Item, Integer> remaining = new HashMap<>(counts);
        int accepted = 0;
        for (Target target : missing) {
            if (accepted >= maxMissing) {
                break;
            }
            int left = remaining.getOrDefault(target.item(), 0);
            if (left <= 0) {
                continue;
            }
            remaining.put(target.item(), left - 1);
            TARGETS.add(target);
            accepted++;
        }
    }

    private static void ensureExecutor(int threads) {
        int wanted = Math.max(0, Math.min(ArrowsConfig.maxScanThreads(), threads));
        if (executorThreads == wanted) {
            return;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            generation++;
            DIRTY.addAll(CACHE.keySet());
            DIRTY.addAll(IN_FLIGHT_KEYS);
            IN_FLIGHT_KEYS.clear();
            IN_FLIGHT.set(0);
            RESULTS.clear();
        }
        executorThreads = wanted;
        if (wanted > 0) {
            executor = Executors.newFixedThreadPool(wanted, runnable -> {
                Thread thread = new Thread(runnable, "schematic-arrows-scan");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                return thread;
            });
        }
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.getBlock() instanceof FluidBlock;
    }

    private static int configSignature(ArrowsConfig config) {
        int signature = config.showWrongBlocks ? 1 : 0;
        signature = signature * 31 + (config.showExtraBlocks ? 1 : 0);
        signature = signature * 31 + (config.onlySelectedPlacement ? 1 : 0);
        signature = signature * 31 + config.scanRadius;
        return signature;
    }

    private static long placementSignature(List<SchematicPlacement> placements) {
        long signature = placements.size();
        for (SchematicPlacement placement : placements) {
            signature = signature * 31 + System.identityHashCode(placement);
            signature = signature * 31 + (placement.isEnabled() ? 1 : 0);
            signature = signature * 31 + (placement.isRenderingEnabled() ? 1 : 0);
            signature = signature * 31 + placement.getOrigin().asLong();
            signature = signature * 31 + placement.getSubRegionCount();
        }
        return signature;
    }

    private static List<SchematicPlacement> placements(ArrowsConfig config) {
        var manager = DataManager.getSchematicPlacementManager();
        if (manager == null) {
            return List.of();
        }
        if (config.onlySelectedPlacement) {
            SchematicPlacement selected = manager.getSelectedSchematicPlacement();
            return selected == null ? List.of() : List.of(selected);
        }
        List<SchematicPlacement> all = manager.getAllSchematicsPlacements();
        return all == null ? List.of() : all;
    }

    /** Counts how many blocks of each item the player can still place. */
    private static Map<Item, Integer> availableCounts(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        // PlayerInventory#size() already covers the main slots, armour and the off hand.
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}
