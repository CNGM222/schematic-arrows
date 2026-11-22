package dev.arrows.schematicarrows;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchematicArrowsClient implements ClientModInitializer {
    public static final String MOD_ID = "schematic_arrows";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static KeyBinding configKey;
    private static boolean enabled = true;

    @Override
    public void onInitializeClient() {
        ArrowsConfig config = ArrowsConfig.get();
        enabled = config.startEnabled;

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.schematic_arrows.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.schematic_arrows.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (configKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new ArrowsConfigScreen(null));
                }
            }
            while (toggleKey.wasPressed()) {
                if (client.currentScreen == null) {
                    toggle(client);
                }
            }
            ArrowManager.tick(client);
        });
        WorldRenderEvents.END_MAIN.register(ArrowRenderer::render);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                ArrowManager.markDirty(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                ArrowManager.forgetChunk(chunk.getPos().x, chunk.getPos().z));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ArrowManager.clear());

        LOGGER.info("Schematic Arrows initialized (enabled={}, radius={})",
                enabled, config.horizontalRadius);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(MinecraftClient client) {
        enabled = !enabled;
        ArrowManager.clear();
        ArrowManager.onToggled(enabled);
        if (client.player != null) {
            client.player.sendMessage(Text.translatable(
                    enabled ? "schematic_arrows.status.on" : "schematic_arrows.status.off"), true);
        }
    }
}
