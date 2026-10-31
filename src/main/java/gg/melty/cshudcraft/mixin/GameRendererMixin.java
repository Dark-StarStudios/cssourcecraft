package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ClientCs;
import gg.melty.cshudcraft.CsHudCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-frame CS view punch, and scope zoom for the sniper rifles, AUG and SG552 (weapons.json zoomFov). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void cshudcraft$frame(CallbackInfo ci) {
        ClientCs.frame(MinecraftClient.getInstance());
    }

    private static final boolean KEEP_BOB = "1".equals(System.getenv("CSHUDCRAFT_KEEPBOB"));

    /** CS has no walking camera bob, and the viewmodel must stay still: no view bob while CS mode is on. */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void cshudcraft$noBob(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        if (!KEEP_BOB && CsHudCraft.csActive()) ci.cancel();
    }

    private static final boolean KEEP_FOV_EFFECTS = "1".equals(System.getenv("CSHUDCRAFT_KEEPFOV"));

    /**
     * CS keeps one field of view: while CS mode is on, sprinting and speed effects no longer widen it (the player's
     * FOV setting stays as it is), and the scope zooms in from there.
     */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void cshudcraft$zoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!changingFov) return;
        float fov = cir.getReturnValue();
        if (!KEEP_FOV_EFFECTS && CsHudCraft.csActive()) fov = MinecraftClient.getInstance().options.getFov().getValue();
        float z = ClientCs.zoomFov();
        if (z > 0) fov = Math.min(fov, z);
        cir.setReturnValue(fov);
    }
}
