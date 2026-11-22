package dev.arrows.schematicarrows.mixin;

import dev.arrows.schematicarrows.ArrowManager;
import dev.arrows.schematicarrows.SchematicArrowsClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change the client receives (including the ones caused by the
 * printer) goes through {@code ClientWorld#handleBlockUpdate}. We only remember
 * which chunk got dirty; the actual rescan happens on the client tick, budgeted.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void schematicArrows$markChunkDirty(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        if (SchematicArrowsClient.isEnabled()) {
            ArrowManager.markDirty(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
}
