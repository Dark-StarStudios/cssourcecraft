package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ViewModelRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The CS first-person viewmodel is submitted together with the world's entities. The hand pass is not usable with
 * Iris shaders (custom layers submitted there never reach the screen), the entity pass is.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "pushEntityRenders(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/state/WorldRenderState;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V",
            at = @At("TAIL"))
    private void cshudcraft$viewmodel(MatrixStack matrices, WorldRenderState state, OrderedRenderCommandQueue queue, CallbackInfo ci) {
        ViewModelRenderer.renderInWorld(matrices, queue, state.cameraRenderState.orientation);
    }
}
