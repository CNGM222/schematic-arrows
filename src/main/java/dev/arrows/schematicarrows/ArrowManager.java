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

/**
 * Finds the schematic blocks that still have to be placed and the ones that were
 * placed wrongly, using a per-chunk cache.
 *
 * <p>Only chunks that can actually have changed are rescanned:</p>
 * <ul>
 *   <li>a chunk entering the scan radius,</li>
 *   <li>a chunk whose blocks changed (hooked in {@code ClientWorldMixin}),
 *       which is how the printer's own placements show up,</li>
 *   <li>when the placement, the config or the player's height changes,</li>
 *   <li>after a long safety interval ({@code rescanSeconds}).</li>
 * </ul>
 * <p>Each tick at most {@code chunksPerTick} chunks are rescanned, so the cost is
 * spread out and an idle player costs nothing.</p>
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
    private record ChunkScan(List<Target> targets, int minY, int maxY, int scannedAt) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-arrows");
    private static final Map<Long, ChunkScan> CACHE = new HashMap<>();
    private static final Set<Long> DIRTY = new LinkedHashSet<>();
    private static final ArrayList<Target> TARGETS = new ArrayList<>();

    private static int hintTicks = -1;
    private static boolean failureReported;
    private static int tick;
    private static int lastConfigSignature = Integer.MIN_VALUE;
    private static long lastPlacementSignature = Long.MIN_VALUE;
    private static ClientWorld lastWorld;
    private static int lastPlayerBlockY = Integer.MIN_VALUE;
    private static BlockPos lastPlayerPos;
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
    }

    public static void clear() {
        CACHE.clear();
        DIRTY.clear();
        TARGETS.clear();
        lastWorld = null;
        lastConfigSignature = Integer.MIN_VALUE;
        lastPlacementSignature = Long.MIN_VALUE;
        lastPlayerBlockY = Integer.MIN_VALUE;
        lastPlayerPos = null;
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
            CACHE.clear();
            DIRTY.clear();
            TARGETS.clear();
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

        if (world != lastWorld) {
            CACHE.clear();
            DIRTY.clear();
            lastWorld = world;
            lastPlayerBlockY = Integer.MIN_VALUE;
            needsMerge = true;
        }
        int configSignature = configSignature(config);
        if (configSignature != lastConfigSignature) {
            lastConfigSignature = configSignature;
            CACHE.clear();
            DIRTY.clear();
            needsMerge = true;
        }
        List<SchematicPlacement> placements = placements(config);
        long placementSignature = placementSignature(placements);
        if (placementSignature != lastPlacementSignature) {
            lastPlacementSignature = placementSignature;
            CACHE.clear();
            DIRTY.clear();
            needsMerge = true;
        }

        BlockPos playerPos = player.getBlockPos();
        int radius = Math.max(1, config.horizontalRadius);
        int vertical = Math.max(1, config.verticalRadius);
        int minY = Math.max(world.getBottomY(), playerPos.getY() - vertical);
        int maxY = Math.min(world.getTopYInclusive(), playerPos.getY() + vertical);
        int minChunkX = (playerPos.getX() - radius) >> 4;
        int maxChunkX = (playerPos.getX() + radius) >> 4;
        int minChunkZ = (playerPos.getZ() - radius) >> 4;
        int maxChunkZ = (playerPos.getZ() + radius) >> 4;

        // Height changes move the vertical scan window, so refresh everything then.
        // A generous step keeps stair climbing from triggering rescans all the time.
        int heightStep = Math.max(8, vertical);
        if (lastPlayerBlockY == Integer.MIN_VALUE || Math.abs(playerPos.getY() - lastPlayerBlockY) >= heightStep) {
            lastPlayerBlockY = playerPos.getY();
            enqueueRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
            needsMerge = true;
        }

        // Drop chunks that left the radius and queue chunks that entered it.
        for (Iterator<Map.Entry<Long, ChunkScan>> iterator = CACHE.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Long, ChunkScan> entry = iterator.next();
            long key = entry.getKey();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                iterator.remove();
                DIRTY.remove(key);
                needsMerge = true;
            }
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                long key = ChunkPos.toLong(chunkX, chunkZ);
                if (!CACHE.containsKey(key)) {
                    DIRTY.add(key);
                }
            }
        }

        // Safety net in case a block change was not seen by the hook.
        int rescanTicks = Math.max(20, config.rescanSeconds * 20);
        for (Map.Entry<Long, ChunkScan> entry : CACHE.entrySet()) {
            if (tick - entry.getValue().scannedAt() >= rescanTicks) {
                DIRTY.add(entry.getKey());
            }
        }

        Set<Item> available = availableItems(player);
        int chunksPerTick = Math.max(1, config.chunksPerTick);
        long positionBudget = Math.max(1_000L, config.scanBudget);
        long[] budget = {positionBudget};
        Iterator<Long> iterator = DIRTY.iterator();
        while (iterator.hasNext() && chunksPerTick > 0 && budget[0] > 0L) {
            long key = iterator.next();
            iterator.remove();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                continue;
            }
            CACHE.put(key, scanChunk(world, schematicWorld, schematicChunks, placements, available,
                    config, chunkX, chunkZ, minY, maxY, budget));
            chunksPerTick--;
            needsMerge = true;
        }

        if (!playerPos.equals(lastPlayerPos)) {
            lastPlayerPos = playerPos;
            needsMerge = true;
        }
        if (needsMerge) {
            merge(player, minY, maxY, config);
            needsMerge = false;
        }
    }

    private static void enqueueRange(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                DIRTY.add(ChunkPos.toLong(chunkX, chunkZ));
            }
        }
    }

    private static ChunkScan scanChunk(ClientWorld world, WorldSchematic schematicWorld,
                                       ChunkManagerSchematic schematicChunks,
                                       List<SchematicPlacement> placements, Set<Item> available,
                                       ArrowsConfig config, int chunkX, int chunkZ, int minY, int maxY,
                                       long[] budget) {
        List<Target> found = new ArrayList<>();
        if (schematicChunks.getChunkIfExists(chunkX, chunkZ) != null) {
            Set<BlockPos> seen = new HashSet<>();
            int x0 = chunkX << 4;
            int z0 = chunkZ << 4;
            int x1 = x0 + 15;
            int z1 = z0 + 15;
            scan:
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
                    int bx1 = Math.max(Math.min(first.getX(), second.getX()), x0);
                    int bx2 = Math.min(Math.max(first.getX(), second.getX()), x1);
                    int bz1 = Math.max(Math.min(first.getZ(), second.getZ()), z0);
                    int bz2 = Math.min(Math.max(first.getZ(), second.getZ()), z1);
                    int by1 = Math.max(Math.min(first.getY(), second.getY()), minY);
                    int by2 = Math.min(Math.max(first.getY(), second.getY()), maxY);
                    if (bx1 > bx2 || by1 > by2 || bz1 > bz2) {
                        continue;
                    }
                    for (int y = by1; y <= by2; y++) {
                        for (int z = bz1; z <= bz2; z++) {
                            for (int x = bx1; x <= bx2; x++) {
                                if (budget[0] <= 0L) {
                                    break scan;
                                }
                                budget[0]--;
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
                                    if (!config.showExtraBlocks || currentIsEmpty) {
                                        continue;
                                    }
                                    found.add(new Target(pos, Items.AIR, Kind.EXTRA, 0.0));
                                    continue;
                                }
                                if (currentIsEmpty) {
                                    Item item = required.getBlock().asItem();
                                    if (item == Items.AIR || !available.contains(item)) {
                                        continue;
                                    }
                                    found.add(new Target(pos, item, Kind.MISSING, 0.0));
                                } else if (config.showWrongBlocks) {
                                    found.add(new Target(pos, required.getBlock().asItem(), Kind.WRONG, 0.0));
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ChunkScan(List.copyOf(found), minY, maxY, tick);
    }

    private static void merge(ClientPlayerEntity player, int minY, int maxY, ArrowsConfig config) {
        List<Target> missing = new ArrayList<>();
        List<Target> wrong = new ArrayList<>();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        for (ChunkScan scan : CACHE.values()) {
            for (Target target : scan.targets()) {
                BlockPos pos = target.pos();
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }
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
        int maxMissing = Math.min(missing.size(), Math.max(1, config.maxArrows));
        int maxWrong = Math.min(wrong.size(), Math.max(1, config.maxBadMarkers));
        TARGETS.clear();
        TARGETS.ensureCapacity(maxWrong + maxMissing);
        // Wrongly placed blocks are the more urgent problem, so they are listed first.
        TARGETS.addAll(wrong.subList(0, maxWrong));
        TARGETS.addAll(missing.subList(0, maxMissing));
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.getBlock() instanceof FluidBlock;
    }

    private static int configSignature(ArrowsConfig config) {
        int signature = config.horizontalRadius;
        signature = signature * 31 + config.verticalRadius;
        signature = signature * 31 + (config.onlySelectedPlacement ? 1 : 0);
        signature = signature * 31 + (config.showWrongBlocks ? 1 : 0);
        signature = signature * 31 + (config.showExtraBlocks ? 1 : 0);
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

    private static Set<Item> availableItems(ClientPlayerEntity player) {
        Set<Item> items = new HashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        ItemStack offHand = player.getOffHandStack();
        if (!offHand.isEmpty()) {
            items.add(offHand.getItem());
        }
        return items;
    }
}
