package gg.melty.cshudcraft.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own hurt-tilt and view-bob, so the viewmodel can cancel exactly what the world pass applies. */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("tiltViewWhenHurt")
    void cshudcraft$tiltViewWhenHurt(MatrixStack matrices, float tickProgress);

    @Invoker("getFov")
    float cshudcraft$getFov(Camera camera, float tickProgress, boolean changingFov);

    @Invoker("bobView")
    void cshudcraft$bobView(MatrixStack matrices, float tickProgress);
}
