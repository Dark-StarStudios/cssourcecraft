package gg.melty.cshudcraft;

import gg.melty.cshudcraft.mdl.MdlModel;
import gg.melty.cshudcraft.mdl.MdlPose;
import gg.melty.cshudcraft.mixin.GameRendererInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.GameMode;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the CS 1.6 first-person hands and weapon (the v_*.mdl from the player's own install) in place
 * of Minecraft's arm, animated by ClientCs, with the model's own muzzle flash sprite.
 */
public final class ViewModelRenderer {
    /** GoldSrc units to Minecraft view-space units. Only the near plane cares; perspective is unchanged. */
    static final float SCALE = 1f / 24f;
    static final boolean DEBUG = "1".equals(System.getenv("CSHUDCRAFT_VMDEBUG"));
    /** CSHUDCRAFT_VMHAND=1: old behaviour, draw in the hand pass (does not show under Iris shaders). */
    static final boolean LEGACY_HAND = "1".equals(System.getenv("CSHUDCRAFT_VMHAND"));
    /** CSHUDCRAFT_VMLAYER: cutout (default) | eyes | emissive. Which render layer draws the model (Iris test). */
    static final String LAYER_MODE = System.getenv().getOrDefault("CSHUDCRAFT_VMLAYER", "cutout");

    public record Loaded(MdlModel model, Identifier[] textures) {}

    private static final Map<String, Loaded> CACHE = new HashMap<>();
    private static final Map<String, String> FAILED = new HashMap<>();
    private static int framesDrawn;

    private ViewModelRenderer() {}

    public static int framesDrawn() { return framesDrawn; }

    /** Loads (once) a model from the player's CS install and registers its textures. Null if it can't be read. */
    public static Loaded model(String file) {
        if (file == null || file.equals("-")) return null;
        Loaded l = CACHE.get(file);
        if (l != null || FAILED.containsKey(file)) return l;
        CsAssets assets = CsHudCraft.ASSETS;
        try {
            Path p = assets.resolve("models/" + file);
            if (p == null) throw new java.io.IOException("models/" + file + " not found");
            Path t = assets.resolve("models/" + file.replace(".mdl", "T.mdl"));
            MdlModel m = MdlModel.load(p, t);
            Identifier[] ids = new Identifier[m.textures.size()];
            for (int i = 0; i < ids.length; i++) {
                MdlModel.Texture tex = m.textures.get(i);
                NativeImage img = new NativeImage(tex.width(), tex.height(), true);
                for (int y = 0; y < tex.height(); y++)
                    for (int x = 0; x < tex.width(); x++) img.setColorArgb(x, y, tex.argb()[y * tex.width() + x]);
                ids[i] = Identifier.of(CsHudCraft.MOD_ID, "mdl/" + file.toLowerCase(Locale.ROOT).replace(".mdl", "") + "/" + i);
                String name = file + " " + tex.name();
                MinecraftClient.getInstance().getTextureManager().registerTexture(ids[i], new NativeImageBackedTexture(() -> name, img));
            }
            l = new Loaded(m, ids);
            CACHE.put(file, l);
            return l;
        } catch (Exception e) {
            FAILED.put(file, e.toString());
            CsHudCraft.LOG.error("[CSHudCraft] Could not load CS model {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Hand-pass hook: the CS viewmodel replaces Minecraft's hands. Iris drops custom layers submitted in the hand pass,
     * so the model is drawn in the world entity pass instead (renderInWorld), the same way the third-person weapon is.
     * @return true when Minecraft's own hands must be skipped this frame
     */
    public static boolean render(float tickProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, ClientPlayerEntity player, int light) {
        if (!CsHudCraft.csActive()) return false;
        if (LEGACY_HAND) draw(tickProgress, matrices, queue, null, null, 1f, light);
        return true;
    }

    /** World entity pass: draws the viewmodel in camera space (orientation = camera rotation). */
    public static void renderInWorld(MatrixStack matrices, OrderedRenderCommandQueue queue, Quaternionf orientation) {
        if (LEGACY_HAND || orientation == null || !CsHudCraft.csActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) return;
        if (!mc.options.getPerspective().isFirstPerson() || mc.options.hudHidden) return;
        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) return;
        if (mc.getCameraEntity() instanceof LivingEntity le && le.isSleeping()) return;
        float tickProgress = mc.getRenderTickCounter().getTickProgress(true);
        int light = mc.getEntityRenderDispatcher().getLight(player, tickProgress);
        // the world pass bends the whole view (hurt tilt, walking bob) through the projection; cancel it so the
        // viewmodel stays fixed in front of the eyes like it did in the hand pass
        Matrix4f undo = new Matrix4f();
        float fovScale = 1f;
        try {
            GameRendererInvoker inv = (GameRendererInvoker) (Object) mc.gameRenderer;
            float camTick = mc.gameRenderer.getCamera().getLastTickProgress();
            MatrixStack bob = new MatrixStack();
            inv.cshudcraft$tiltViewWhenHurt(bob, camTick);
            if (mc.options.getBobView().getValue()) inv.cshudcraft$bobView(bob, camTick);
            bob.peek().getPositionMatrix().invert(undo);
            // the world pass uses the "dynamic" fov (sprinting, speed effects, zoom), the old hand pass did not:
            // stretch the model sideways by the ratio of the two projections so it stays where it was
            float fovWorld = inv.cshudcraft$getFov(mc.gameRenderer.getCamera(), tickProgress, true);
            float fovHand = inv.cshudcraft$getFov(mc.gameRenderer.getCamera(), tickProgress, false);
            float k = (float) (Math.tan(Math.toRadians(fovWorld) / 2) / Math.tan(Math.toRadians(fovHand) / 2));
            if (k > 0.2f && k < 5f) fovScale = k;
        } catch (Exception e) {
            undo.identity();
            fovScale = 1f;
        }
        draw(tickProgress, matrices, queue, orientation, undo, fovScale, light);
    }

    private static void draw(float tickProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, Quaternionf orientation, Matrix4f undoBob, float fovScale, int light) {
        ClientCs.Anim anim = ClientCs.viewAnim();
        if (anim == null || ClientCs.scoped()) return; // CS hides the weapon in the scope
        Loaded l = model(anim.model());
        if (l == null) return;
        MdlModel m = l.model();
        float[][] bones = MdlPose.compute(m, anim.sequence(), anim.frame());
        float[] turn = ClientCs.boneInspect();
        Sheets.Inspect row = ClientCs.boneInspectRow();
        if (turn != null && row != null) {
            for (String bn : row.bones()) {
                int bi = MdlPose.findBone(m, bn);
                if (bi < 0) continue;
                float side = bones[bi][7] >= 0 ? 1f : -1f; // GoldSrc +Y is left: mirror each hand to its side
                if (row.bones().length == 1) side = 1f;
                MdlPose.turnSubtree(m, bones, bi, turn[0] * side, turn[1], turn[2] * side, turn[3]);
            }
        }

        matrices.push();
        if (orientation != null) {
            // world pass: rotate camera-relative world space back into view space, so the model sits in front of the eyes
            matrices.multiply(orientation);
            if (undoBob != null) matrices.multiplyPositionMatrix(undoBob);
            if (fovScale != 1f) matrices.scale(fovScale, fovScale, 1f);
        } else if (!"keep".equals(System.getenv("CSHUDCRAFT_VMPOSE"))) {
            // hand pass (legacy): we are handed the inverse camera rotation; the viewmodel lives in plain view space
            matrices.peek().getPositionMatrix().identity();
            matrices.peek().getNormalMatrix().identity();
        }
        if (CsHudCraft.CONFIG.leftHanded()) matrices.scale(-1f, 1f, 1f); // CS cl_righthand 0: the viewmodel mirrored
        ClientCs.applyViewModelMotion(matrices, tickProgress);
        // GoldSrc view space (X forward, Y left, Z up) -> Minecraft view space (-Z forward, X right, Y up)
        Map<Integer, List<float[]>> byTexture = new HashMap<>();
        float[] p = new float[3], n = new float[3];
        for (MdlModel.Part part : m.parts) {
            int nv = part.verts().length / 3;
            float[] tv = new float[nv * 3];
            for (int v = 0; v < nv; v++) {
                MdlPose.transform(bones[part.vertBone()[v]], part.verts()[v * 3], part.verts()[v * 3 + 1], part.verts()[v * 3 + 2], p);
                tv[v * 3] = -p[1] * SCALE;
                tv[v * 3 + 1] = p[2] * SCALE;
                tv[v * 3 + 2] = -p[0] * SCALE;
            }
            int nn = part.norms().length / 3;
            float[] tn = new float[nn * 3];
            for (int v = 0; v < nn; v++) {
                MdlPose.rotate(bones[part.normBone()[v]], part.norms()[v * 3], part.norms()[v * 3 + 1], part.norms()[v * 3 + 2], n);
                tn[v * 3] = -n[1];
                tn[v * 3 + 1] = n[2];
                tn[v * 3 + 2] = -n[0];
            }
            for (MdlModel.Mesh mesh : part.meshes()) {
                int skin = mesh.skinRef() < m.skinRefs.length ? m.skinRefs[mesh.skinRef()] : mesh.skinRef();
                if (skin < 0 || skin >= m.textures.size()) continue;
                MdlModel.Texture tex = m.textures.get(skin);
                int[] tris = mesh.tris();
                float[] out = new float[(tris.length / 4) * 8];
                for (int i = 0, o = 0; i < tris.length; i += 4, o += 8) {
                    int vi = Math.min(tris[i], nv - 1), ni = Math.min(Math.max(0, tris[i + 1]), Math.max(0, nn - 1));
                    out[o] = tv[vi * 3]; out[o + 1] = tv[vi * 3 + 1]; out[o + 2] = tv[vi * 3 + 2];
                    if (nn > 0) { out[o + 3] = tn[ni * 3]; out[o + 4] = tn[ni * 3 + 1]; out[o + 5] = tn[ni * 3 + 2]; }
                    else { out[o + 4] = 1; }
                    out[o + 6] = tris[i + 2] / (float) tex.width();
                    out[o + 7] = tris[i + 3] / (float) tex.height();
                }
                byTexture.computeIfAbsent(skin, k -> new ArrayList<>()).add(out);
            }
        }
        for (Map.Entry<Integer, List<float[]>> e : byTexture.entrySet()) {
            int skin = e.getKey();
            boolean additive = (m.textures.get(skin).flags() & MdlModel.NF_ADDITIVE) != 0;
            Identifier texId = l.textures()[skin];
            RenderLayer layer = LAYER_MODE.equals("eyes") ? RenderLayers.eyes(texId)
                    : LAYER_MODE.equals("emissive") ? RenderLayers.entityTranslucentEmissive(texId)
                    : additive ? RenderLayers.eyes(texId) : RenderLayers.entityCutoutNoCull(texId);
            List<float[]> chunks = e.getValue();
            queue.submitCustom(matrices, layer, (entry, vc) -> {
                // entity layers draw quads: each triangle goes out as a quad with its last corner repeated
                for (float[] c : chunks) {
                    for (int t = 0; t + 24 <= c.length; t += 24) {
                        for (int k = 0; k < 4; k++) {
                            int o = t + Math.min(k, 2) * 8;
                            vc.vertex(entry, c[o], c[o + 1], c[o + 2]).color(255, 255, 255, 255).texture(c[o + 6], c[o + 7])
                                    .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, c[o + 3], c[o + 4], c[o + 5]);
                        }
                    }
                }
            });
        }
        muzzleFlash(matrices, queue, m, bones);
        if (DEBUG) {
            if (framesDrawn % 200 == 0) {
                CsHudCraft.LOG.info("[CSHudCraft] vm debug: textures={} chunks={} light={} pose={}", l.textures().length,
                        byTexture.values().stream().mapToInt(List::size).sum(), light, matrices.peek().getPositionMatrix());
            }
            Identifier t0 = l.textures()[0];
            debugQuad(queue, matrices, RenderLayers.entityCutoutNoCull(t0), -0.5f, 255, 0, 0); // red   = entityCutoutNoCull
            debugQuad(queue, matrices, RenderLayers.eyes(t0), 0f, 0, 255, 0);                   // green = eyes
            debugQuad(queue, matrices, RenderLayers.entityTranslucentEmissive(t0), 0.5f, 0, 0, 255); // blue = entityTranslucentEmissive
        }
        matrices.pop();
        framesDrawn++;
    }

    private static void debugQuad(OrderedRenderCommandQueue queue, MatrixStack matrices, RenderLayer layer, float cx, int r, int g, int b) {
        queue.submitCustom(matrices, layer, (entry, vc) -> {
            float h = 0.15f, z = -1f;
            vc.vertex(entry, cx - h, -h, z).color(r, g, b, 255).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(entry, 0, 0, 1);
            vc.vertex(entry, cx + h, -h, z).color(r, g, b, 255).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(entry, 0, 0, 1);
            vc.vertex(entry, cx + h, h, z).color(r, g, b, 255).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(entry, 0, 0, 1);
            vc.vertex(entry, cx - h, h, z).color(r, g, b, 255).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(entry, 0, 0, 1);
        });
    }

    private static void muzzleFlash(MatrixStack matrices, OrderedRenderCommandQueue queue, MdlModel m, float[][] bones) {
        ClientCs.Flash f = ClientCs.muzzleFlash();
        if (f == null || f.attachment() >= m.attachments.size()) return;
        CsAssets.Sheet spr = CsHudCraft.ASSETS.glowSheet("muzzleflash" + Math.max(1, Math.min(3, f.sprite())));
        if (spr == null) return;
        MdlModel.Attachment a = m.attachments.get(f.attachment());
        float[] p = new float[3];
        MdlPose.transform(bones[a.bone()], a.org()[0], a.org()[1], a.org()[2], p);
        float x = -p[1] * SCALE, y = p[2] * SCALE, z = -p[0] * SCALE;
        float half = spr.width() * f.scale() * 0.5f * SCALE;
        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f.roll()));
        queue.submitCustom(matrices, RenderLayers.entityTranslucentEmissive(spr.id()), (entry, vc) -> {
            int full = 0xF000F0;
            vc.vertex(entry, -half, -half, 0).color(255, 255, 255, 255).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(full).normal(entry, 0, 0, 1);
            vc.vertex(entry, half, -half, 0).color(255, 255, 255, 255).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(full).normal(entry, 0, 0, 1);
            vc.vertex(entry, half, half, 0).color(255, 255, 255, 255).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(full).normal(entry, 0, 0, 1);
            vc.vertex(entry, -half, half, 0).color(255, 255, 255, 255).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(full).normal(entry, 0, 0, 1);
        });
        matrices.pop();
    }
}
