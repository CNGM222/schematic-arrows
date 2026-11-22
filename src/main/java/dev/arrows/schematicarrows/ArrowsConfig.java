package dev.arrows.schematicarrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON config written to {@code config/schematic-arrows.json}.
 */
public final class ArrowsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-arrows");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("schematic-arrows.json");

    private static ArrowsConfig instance;

    /** Whether arrows are shown right after the game starts. */
    public boolean startEnabled = true;

    /** Hard cap on highlighted blocks (nearest blocks win). */
    public int maxArrows = 96;

    /** How many chunk scans may be submitted per client tick. */
    public int chunksPerTick = 4;

    /** Worker threads used for chunk scanning. 0 = scan on the client thread. */
    public int scanThreads = 2;

    /** Upper bound for {@link #scanThreads}: the number of logical CPU cores. */
    public static int maxScanThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Only look at the placement currently selected in Litematica. */
    public boolean onlySelectedPlacement = false;

    /** Half width of the arrow head in blocks. */
    public float arrowRadius = 0.24f;

    /** Length of the arrow head in blocks. */
    public float arrowHeight = 0.38f;

    /** Length of the line behind the arrow head, in blocks. Set to 0 for a bare triangle. */
    public float arrowStem = 0.22f;

    /** Gap between the block face and the arrow tip, in blocks. */
    public float arrowGap = 0.05f;

    /** Bobbing amplitude of the arrows, in blocks. Set to 0 to disable. */
    public float bobAmplitude = 0.07f;

    /** Line width of the arrows. */
    public float lineWidth = 2.5f;

    /** Colour of the arrow above a block (ARGB). */
    public int aboveColor = 0xFF63F58B;

    /** Colour of the arrow below a block (ARGB). */
    public int belowColor = 0xFF5BC8FF;

    /** Draw a thin outline around every highlighted block. */
    public boolean drawBox = true;

    /** Colour of that outline (ARGB). */
    public int boxColor = 0x66FFFFFF;

    /** Mark blocks whose world state differs from the schematic (wrong block or facing). */
    public boolean showWrongBlocks = true;

    /** Mark blocks that exist in the world where the schematic wants air (printer mis-aimed). */
    public boolean showExtraBlocks = true;

    /** Colour of the X marker for a block with the wrong state (ARGB). */
    public int wrongColor = 0xFFFF4D4D;

    /** Colour of the X marker for an unexpected block (ARGB). */
    public int extraColor = 0xFFFFAA33;

    /** Hard cap on wrong / extra markers (nearest first). */
    public int maxBadMarkers = 64;

    /** Half size of the X marker, in blocks. */
    public float badMarkerSize = 0.20f;

    public static ArrowsConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Restores every value to the built-in defaults (does not save by itself). */
    public void resetDefaults() {
        ArrowsConfig defaults = new ArrowsConfig();
        startEnabled = defaults.startEnabled;
        maxArrows = defaults.maxArrows;
        chunksPerTick = defaults.chunksPerTick;
        scanThreads = defaults.scanThreads;
        onlySelectedPlacement = defaults.onlySelectedPlacement;
        arrowRadius = defaults.arrowRadius;
        arrowHeight = defaults.arrowHeight;
        arrowStem = defaults.arrowStem;
        arrowGap = defaults.arrowGap;
        bobAmplitude = defaults.bobAmplitude;
        lineWidth = defaults.lineWidth;
        aboveColor = defaults.aboveColor;
        belowColor = defaults.belowColor;
        drawBox = defaults.drawBox;
        boxColor = defaults.boxColor;
        showWrongBlocks = defaults.showWrongBlocks;
        showExtraBlocks = defaults.showExtraBlocks;
        wrongColor = defaults.wrongColor;
        extraColor = defaults.extraColor;
        maxBadMarkers = defaults.maxBadMarkers;
        badMarkerSize = defaults.badMarkerSize;
    }

    private static ArrowsConfig load() {
        ArrowsConfig config = new ArrowsConfig();
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH, StandardCharsets.UTF_8);
                ArrowsConfig read = GSON.fromJson(json, ArrowsConfig.class);
                if (read != null) {
                    config = read;
                }
            }
            config.scanThreads = Math.max(0, Math.min(maxScanThreads(), config.scanThreads));
            config.save();
        } catch (Exception e) {
            LOGGER.warn("Failed to load {}, using defaults", PATH, e);
        }
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save {}", PATH, e);
        }
    }
}
