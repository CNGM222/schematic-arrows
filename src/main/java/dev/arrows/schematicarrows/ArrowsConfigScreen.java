package dev.arrows.schematicarrows;

import com.mojang.serialization.Codec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Vanilla-styled settings screen: a scrollable list of sliders, toggles and
 * colour pickers backed by {@link ArrowsConfig}.
 *
 * <p>Every widget writes straight into the live config object; the file is saved
 * when the screen is closed, so the effect is visible while the screen is open.</p>
 */
public final class ArrowsConfigScreen extends GameOptionsScreen {
    private static final List<ArrowColor> COLORS = List.of(ArrowColor.values());

    public ArrowsConfigScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.translatable("schematic_arrows.config.title"));
    }

    @Override
    protected void addOptions() {
        ArrowsConfig config = ArrowsConfig.get();

        body.addHeader(Text.translatable("schematic_arrows.config.section.scope"));
        body.addSingleOptionEntry(intOption("schematic_arrows.config.max_arrows", 8, 512,
                config.maxArrows, value -> config.maxArrows = value));
        body.addSingleOptionEntry(intOption("schematic_arrows.config.chunks_per_tick", 1, 16,
                config.chunksPerTick, value -> config.chunksPerTick = value));
        body.addSingleOptionEntry(intOption("schematic_arrows.config.scan_threads", 0, 8,
                config.scanThreads, value -> config.scanThreads = value));
        body.addSingleOptionEntry(booleanOption("schematic_arrows.config.only_selected",
                config.onlySelectedPlacement, value -> config.onlySelectedPlacement = value));

        body.addHeader(Text.translatable("schematic_arrows.config.section.look"));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.arrow_radius", 0.05, 0.60,
                config.arrowRadius, value -> config.arrowRadius = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.arrow_height", 0.10, 1.00,
                config.arrowHeight, value -> config.arrowHeight = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.arrow_stem", 0.00, 0.60,
                config.arrowStem, value -> config.arrowStem = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.arrow_gap", 0.00, 0.30,
                config.arrowGap, value -> config.arrowGap = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.bob_amplitude", 0.00, 0.30,
                config.bobAmplitude, value -> config.bobAmplitude = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.line_width", 1.0, 6.0,
                config.lineWidth, value -> config.lineWidth = value));

        body.addHeader(Text.translatable("schematic_arrows.config.section.colors"));
        body.addSingleOptionEntry(colorOption("schematic_arrows.config.above_color", config.aboveColor,
                false, value -> config.aboveColor = value));
        body.addSingleOptionEntry(colorOption("schematic_arrows.config.below_color", config.belowColor,
                false, value -> config.belowColor = value));
        body.addSingleOptionEntry(booleanOption("schematic_arrows.config.draw_box",
                config.drawBox, value -> config.drawBox = value));
        body.addSingleOptionEntry(colorOption("schematic_arrows.config.box_color", config.boxColor,
                true, value -> config.boxColor = value));

        body.addHeader(Text.translatable("schematic_arrows.config.section.errors"));
        body.addSingleOptionEntry(booleanOption("schematic_arrows.config.show_wrong",
                config.showWrongBlocks, value -> config.showWrongBlocks = value));
        body.addSingleOptionEntry(booleanOption("schematic_arrows.config.show_extra",
                config.showExtraBlocks, value -> config.showExtraBlocks = value));
        body.addSingleOptionEntry(intOption("schematic_arrows.config.max_bad_markers", 8, 256,
                config.maxBadMarkers, value -> config.maxBadMarkers = value));
        body.addSingleOptionEntry(decimalOption("schematic_arrows.config.bad_marker_size", 0.10, 0.50,
                config.badMarkerSize, value -> config.badMarkerSize = value));
        body.addSingleOptionEntry(colorOption("schematic_arrows.config.wrong_color",
                config.wrongColor, false, value -> config.wrongColor = value));
        body.addSingleOptionEntry(colorOption("schematic_arrows.config.extra_color",
                config.extraColor, false, value -> config.extraColor = value));

        body.addHeader(Text.translatable("schematic_arrows.config.section.misc"));
        body.addSingleOptionEntry(booleanOption("schematic_arrows.config.start_enabled",
                config.startEnabled, value -> config.startEnabled = value));

        ButtonWidget reset = ButtonWidget.builder(Text.translatable("schematic_arrows.config.reset"), button -> resetDefaults())
                .build();
        ButtonWidget close = ButtonWidget.builder(Text.translatable("schematic_arrows.config.save_close"), button -> close())
                .build();
        body.addWidgetEntry(reset, close);
    }

    private void resetDefaults() {
        ArrowsConfig config = ArrowsConfig.get();
        config.resetDefaults();
        config.save();
        if (client != null) {
            client.setScreen(new ArrowsConfigScreen(parent));
        }
    }

    @Override
    public void removed() {
        ArrowsConfig.get().save();
        super.removed();
    }

    private static SimpleOption<Integer> intOption(String key, int min, int max, int value, Consumer<Integer> setter) {
        return new SimpleOption<>(key, SimpleOption.constantTooltip(Text.translatable(key + ".tooltip")),
                (optionText, current) -> GameOptions.getGenericValueText(optionText, Text.literal(Integer.toString(current))),
                new SimpleOption.ValidatingIntSliderCallbacks(min, max),
                value, setter);
    }

    private static SimpleOption<Integer> decimalOption(String key, double min, double max, float value,
                                                       Consumer<Float> setter) {
        int minPercent = (int) Math.round(min * 100.0);
        int maxPercent = (int) Math.round(max * 100.0);
        int current = Math.max(minPercent, Math.min(maxPercent, Math.round(value * 100.0f)));
        return new SimpleOption<>(key, SimpleOption.constantTooltip(Text.translatable(key + ".tooltip")),
                (optionText, percent) -> GameOptions.getGenericValueText(optionText,
                        Text.literal(String.format(Locale.ROOT, "%.2f", percent / 100.0))),
                new SimpleOption.ValidatingIntSliderCallbacks(minPercent, maxPercent),
                current, percent -> setter.accept(percent / 100.0f));
    }

    private static SimpleOption<Boolean> booleanOption(String key, boolean value, Consumer<Boolean> setter) {
        return SimpleOption.ofBoolean(key, SimpleOption.constantTooltip(Text.translatable(key + ".tooltip")),
                value, setter);
    }

    private static SimpleOption<ArrowColor> colorOption(String key, int argb, boolean alpha, Consumer<Integer> setter) {
        SimpleOption.PotentialValuesBasedCallbacks<ArrowColor> callbacks =
                new SimpleOption.PotentialValuesBasedCallbacks<>(COLORS,
                        Codec.STRING.xmap(ArrowColor::byId, ArrowColor::id));
        return new SimpleOption<>(key, SimpleOption.constantTooltip(Text.translatable(key + ".tooltip")),
                (optionText, current) -> GameOptions.getGenericValueText(optionText,
                        Text.translatable("schematic_arrows.config.color." + current.id())),
                callbacks, ArrowColor.nearest(argb), selected -> setter.accept(selected.argb(alpha)));
    }

    /** Preset colours; the box outline keeps a low alpha so it never overpowers the arrows. */
    private enum ArrowColor {
        GREEN("green", 0x63F58B),
        BLUE("blue", 0x5BC8FF),
        CYAN("cyan", 0x4DE1E1),
        YELLOW("yellow", 0xFFE066),
        ORANGE("orange", 0xFFA24D),
        RED("red", 0xFF5A5A),
        PINK("pink", 0xFF7AC8),
        WHITE("white", 0xF5F5F5);

        private final String id;
        private final int rgb;

        ArrowColor(String id, int rgb) {
            this.id = id;
            this.rgb = rgb;
        }

        public String id() {
            return id;
        }

        public int argb(boolean translucent) {
            return (translucent ? 0x66 : 0xFF) << 24 | rgb;
        }

        public static ArrowColor byId(String id) {
            for (ArrowColor color : values()) {
                if (color.id.equals(id)) {
                    return color;
                }
            }
            return GREEN;
        }

        public static ArrowColor nearest(int argb) {
            int rgb = argb & 0xFFFFFF;
            ArrowColor best = GREEN;
            int bestDistance = Integer.MAX_VALUE;
            for (ArrowColor color : values()) {
                int dr = ((rgb >> 16) & 0xFF) - ((color.rgb >> 16) & 0xFF);
                int dg = ((rgb >> 8) & 0xFF) - ((color.rgb >> 8) & 0xFF);
                int db = (rgb & 0xFF) - (color.rgb & 0xFF);
                int distance = dr * dr + dg * dg + db * db;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = color;
                }
            }
            return best;
        }
    }
}
