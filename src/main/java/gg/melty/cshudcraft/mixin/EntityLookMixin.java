package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ClientCs;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scope sensitivity: the mouse deltas that turn the local player's head are scaled while zoomed. */
@Mixin(Entity.class)
public abstract class EntityLookMixin {
    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double cshudcraft$lookX(double dx) {
        return dx * ClientCs.scopeLookScale(this);
    }

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double cshudcraft$lookY(double dy) {
        return dy * ClientCs.scopeLookScale(this);
    }
}
