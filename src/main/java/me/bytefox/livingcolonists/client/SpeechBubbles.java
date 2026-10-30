package me.bytefox.livingcolonists.client;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.bytefox.livingcolonists.network.ChatTyping;
import me.bytefox.livingcolonists.network.SpeechBubble;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Speech bubbles over colonists (and players, when the server turns that on): white pixel comic
 * bubbles with the last few lines stacked above the head, wrapped to a narrow width, fading out
 * after a while, and three bouncing dots while someone types. Facing the camera like name tags;
 * the text is full bright and drawn with a depth offset, so it never sinks into its bubble. The
 * usual tricks of chat bubble mods, written for this mod. Client thread only.
 *
 * <p>A shader pack (Iris) lights and tints whatever is drawn in the world, and a white bubble comes
 * out dim. While one is in use, the bubbles go on the screen instead, over the speaker's head as the
 * camera sees it, in the GUI pass that shaders leave alone.
 */
public final class SpeechBubbles {
    /** Lines kept over one head, newest at the bottom. */
    private static final int MAX_LINES = 3;
    /** Wrap width, in font pixels. */
    private static final int WIDTH = 150;
    /** Bubbles farther than this from the camera aren't drawn, in blocks. */
    private static final double DRAW_RANGE = 48;
    private static final long FADE_MILLIS = 1200;
    /** Inside margin of a bubble, the gap between stacked bubbles and the tail's height, in font pixels. */
    private static final int PAD = 4;
    private static final int GAP = 3;
    private static final int TAIL = 3;
    private static final int DOT_SPACING = 6;
    /** A white bubble with dark ink and a dark border, like a comic. */
    private static final int PAPER = 0xFFFFFF;
    private static final int INK = 0x1E1E1E;
    private static final int EDGE = 0x2A2A2A;
    /** Typing dots go away by themselves after this long, if the stop never arrives. */
    private static final long TYPING_MILLIS = 30_000;
    private static final float SCALE = 0.025f;
    /** How often to look whether a wall is between the camera and the speaker. */
    private static final long SIGHT_MILLIS = 100;
    /** Behind a wall a bubble stays, faint, like a name tag; but only this near. */
    private static final float HIDDEN = 0.35f;
    private static final double HIDDEN_RANGE = 16;
    /** How long the change between in sight and behind a wall takes. */
    private static final float EASE_MILLIS = 300;

    private record Line(String text, long shown, long until) {
    }

    private static final class Bubble {
        final Deque<Line> lines = new ArrayDeque<>();
        long typingSince = -1;
        /** Whether the camera sees the speaker's head, as of the last check, and when that was. */
        boolean inSight = true;
        long checked;
        /** How bright the bubble is drawn, easing toward 1 in sight and {@link #HIDDEN} behind a wall. */
        float shown = 1;
        long eased;
    }

    /** A bubble to draw on the screen this frame: where the head is, in GUI pixels, and how big. */
    private record Placed(Bubble bubble, float x, float y, float scale, boolean sneaking) {
    }

    private static final Map<Integer, Bubble> BUBBLES = new HashMap<>();
    private static final List<Placed> PLACED = new ArrayList<>();
    /** On the screen a bubble right in front of the camera would cover it. */
    private static final float MAX_SCREEN_SCALE = 1.5f;
    private static boolean typingSent;
    private static boolean irisLooked;
    private static Object iris;
    private static Method irisShaders;

    private SpeechBubbles() {
    }

    public static void apply(SpeechBubble payload) {
        long now = Util.getMillis();
        Bubble bubble = BUBBLES.computeIfAbsent(payload.entity(), id -> new Bubble());
        switch (payload.kind()) {
            case SpeechBubble.LINE -> {
                bubble.typingSince = -1;
                // Long enough to read: a second and a half more than a quick reader needs, up to twenty seconds.
                long length = Math.min(21_000, Math.max(4_500, 4_500 + 105L * payload.text().length()));
                bubble.lines.addLast(new Line(payload.text(), now, now + length));
                while (bubble.lines.size() > MAX_LINES) {
                    bubble.lines.removeFirst();
                }
            }
            case SpeechBubble.TYPING -> bubble.typingSince = now;
            default -> bubble.typingSince = -1;
        }
    }

    public static void clear() {
        BUBBLES.clear();
        typingSent = false;
    }

    /** Tells the server while the player types a chat message (not a command), for the dots over them. */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        if (connection == null) {
            return;
        }
        boolean typing = false;
        if (minecraft.screen instanceof ChatScreen chat) {
            for (var child : chat.children()) {
                if (child instanceof EditBox box && !box.getValue().isBlank() && !box.getValue().startsWith("/")) {
                    typing = true;
                }
            }
        }
        if (typing != typingSent) {
            typingSent = typing;
            PacketDistributor.sendToServer(new ChatTyping(typing));
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || BUBBLES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            BUBBLES.clear();
            return;
        }
        long now = Util.getMillis();
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        PLACED.clear();
        boolean onScreen = shadersInUse();
        Matrix4f toClip = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).mul(pose.last().pose());
        float guiWidth = minecraft.getWindow().getGuiScaledWidth();
        float guiHeight = minecraft.getWindow().getGuiScaledHeight();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Font font = minecraft.font;
        for (Iterator<Map.Entry<Integer, Bubble>> it = BUBBLES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Bubble> entry = it.next();
            Bubble bubble = entry.getValue();
            bubble.lines.removeIf(line -> line.until() < now);
            if (bubble.typingSince >= 0 && now - bubble.typingSince > TYPING_MILLIS) {
                bubble.typingSince = -1;
            }
            if (bubble.lines.isEmpty() && bubble.typingSince < 0) {
                it.remove();
                continue;
            }
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity == null || entity.isRemoved() || entity.isInvisible()
                    || entity == camera.getEntity() && !camera.isDetached()
                    || entity.distanceToSqr(eye) > DRAW_RANGE * DRAW_RANGE) {
                continue;
            }
            Vec3 at = entity.getPosition(partial);
            // The whole bubble eases between bright (in sight) and faint behind walls, never half and half.
            if (now - bubble.checked >= SIGHT_MILLIS) {
                bubble.checked = now;
                Vec3 head = at.add(0, entity.getEyeHeight(), 0);
                bubble.inSight = minecraft.level.clip(new ClipContext(eye, head, ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE, camera.getEntity())).getType() == HitResult.Type.MISS;
            }
            float target = bubble.inSight ? 1 : entity.distanceToSqr(eye) <= HIDDEN_RANGE * HIDDEN_RANGE ? HIDDEN : 0;
            float step = bubble.eased == 0 ? 1 : Math.min(1, (now - bubble.eased) / EASE_MILLIS);
            bubble.eased = now;
            bubble.shown += (target - bubble.shown) * step;
            if (bubble.shown < 0.03f) {
                continue;
            }
            // Above the name tag, which MineColonies draws over every colonist.
            Vec3 anchor = new Vec3(at.x - eye.x, at.y + entity.getBbHeight() + 0.95 - eye.y, at.z - eye.z);
            if (onScreen) {
                place(bubble, anchor, toClip, event.getProjectionMatrix().m11(), guiWidth, guiHeight, entity.isDiscrete());
                continue;
            }
            pose.pushPose();
            pose.translate(anchor.x, anchor.y, anchor.z);
            pose.mulPose(camera.rotation());
            pose.scale(SCALE, -SCALE, SCALE);
            draw(font, pose.last().pose(), buffers, bubble, now, entity.isDiscrete(), !bubble.inSight, false);
            pose.popPose();
        }
        buffers.endBatch();
        // Farther bubbles first, so nearer ones cover them.
        PLACED.sort(Comparator.comparingDouble(Placed::scale));
    }

    /** Where the bubble's anchor lands on the screen, if it does, for {@link #renderOnScreen}. */
    private static void place(Bubble bubble, Vec3 anchor, Matrix4f toClip, float yScale, float guiWidth, float guiHeight,
                              boolean sneaking) {
        Vector4f clip = toClip.transform(new Vector4f((float) anchor.x, (float) anchor.y, (float) anchor.z, 1));
        if (clip.w < 0.1f) {
            return;
        }
        float x = clip.x / clip.w;
        float y = clip.y / clip.w;
        if (Math.abs(x) > 1.2f || Math.abs(y) > 1.2f) {
            return;
        }
        // One font pixel of the world bubble, in GUI pixels at this distance.
        float scale = Math.min(MAX_SCREEN_SCALE, SCALE * yScale / clip.w * guiHeight / 2);
        PLACED.add(new Placed(bubble, (x + 1) / 2 * guiWidth, (1 - y) / 2 * guiHeight, scale, sneaking));
    }

    /** Draws the bubbles placed this frame on the screen, under the rest of the HUD. */
    public static void renderOnScreen(RenderGuiEvent.Pre event) {
        if (PLACED.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.hideGui) {
            GuiGraphics graphics = event.getGuiGraphics();
            PoseStack pose = graphics.pose();
            long now = Util.getMillis();
            for (Placed placed : PLACED) {
                pose.pushPose();
                pose.translate(placed.x(), placed.y(), 0);
                pose.scale(placed.scale(), placed.scale(), 1);
                // Drawn like a bubble seen through a wall, in order, with the bubble as plain GUI rectangles:
                // Iris leaves the GUI's own render types alone, but not the world's text backgrounds.
                draw(minecraft.font, pose.last().pose(), graphics.bufferSource(), placed.bubble(), now, placed.sneaking(),
                        true, true);
                pose.popPose();
            }
            graphics.flush();
        }
        PLACED.clear();
    }

    /** Whether Iris is drawing with a shader pack, through its public API; false without Iris. */
    private static boolean shadersInUse() {
        if (!irisLooked) {
            irisLooked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                iris = api.getMethod("getInstance").invoke(null);
                irisShaders = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                irisShaders = null;
            }
        }
        if (irisShaders == null) {
            return false;
        }
        try {
            return (boolean) irisShaders.invoke(iris);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Draws bottom up: the typing dots lowest, then the newest line, the older ones above it. Each is
     * its own white comic bubble with a pixel border; only the lowest has the tail toward the head.
     */
    private static void draw(Font font, Matrix4f matrix, MultiBufferSource.BufferSource buffers, Bubble bubble, long now,
                             boolean sneaking, boolean throughWalls, boolean onScreen) {
        float dim = (sneaking ? 0.5f : 1) * bubble.shown;
        float bottom = 0;
        boolean tail = true;
        if (bubble.typingSince >= 0) {
            int width = DOT_SPACING * 3 + PAD * 2 - 2;
            int height = font.lineHeight + PAD * 2 - 2;
            frame(matrix, buffers, -width / 2f, bottom - height, width, height, dim, tail, throughWalls, onScreen);
            drawDots(font, matrix, buffers, bottom - height + PAD, now, dim, throughWalls);
            bottom -= height + GAP;
            tail = false;
        }
        List<Line> newestFirst = new ArrayList<>(bubble.lines);
        java.util.Collections.reverse(newestFirst);
        for (Line line : newestFirst) {
            float alpha = dim * Math.min(1, (line.until() - now) / (float) FADE_MILLIS)
                    * Math.min(1, (now - line.shown()) / 150f);
            if (alpha < 0.05f) {
                continue;
            }
            List<FormattedCharSequence> rows = font.split(Component.literal(line.text()), WIDTH);
            int textWidth = 0;
            for (FormattedCharSequence row : rows) {
                textWidth = Math.max(textWidth, font.width(row));
            }
            int width = textWidth + PAD * 2;
            int height = rows.size() * (font.lineHeight + 1) - 1 + PAD * 2;
            float top = bottom - height;
            frame(matrix, buffers, -width / 2f, top, width, height, alpha, tail, throughWalls, onScreen);
            int ink = ((int) (alpha * 255) << 24) | INK;
            for (int i = 0; i < rows.size(); i++) {
                FormattedCharSequence row = rows.get(i);
                // Over the bubble, pulled toward the camera so no letter sinks into the white behind it.
                font.drawInBatch(row, -font.width(row) / 2f, top + PAD + i * (font.lineHeight + 1), ink, false, matrix, buffers,
                        mode(throughWalls), 0, LightTexture.FULL_BRIGHT);
            }
            bottom = top - GAP;
            tail = false;
        }
    }

    /**
     * A comic bubble, all in whole pixels: a white body, a dark one-pixel border stepping around
     * rounded corners, and, if asked, a stepped tail pointing down at the speaker.
     */
    private static void frame(Matrix4f matrix, MultiBufferSource.BufferSource buffers, float x, float y, int width, int height,
                              float alpha, boolean tail, boolean throughWalls, boolean onScreen) {
        RenderType type = onScreen ? RenderType.gui()
                : throughWalls ? RenderType.textBackgroundSeeThrough() : RenderType.textBackground();
        VertexConsumer quads = buffers.getBuffer(type);
        int paper = ((int) (alpha * 255) << 24) | PAPER;
        int edge = ((int) (alpha * 255) << 24) | EDGE;
        float right = x + width;
        float lower = y + height;
        // The body as two crossed rectangles, then a border that steps around rounded pixel corners.
        rect(quads, matrix, !onScreen, x + 1, y + 2, right - 1, lower - 2, paper);
        rect(quads, matrix, !onScreen, x + 2, y + 1, right - 2, lower - 1, paper);
        rect(quads, matrix, !onScreen, x + 2, y, right - 2, y + 1, edge);
        rect(quads, matrix, !onScreen, x + 2, lower - 1, right - 2, lower, edge);
        rect(quads, matrix, !onScreen, x, y + 2, x + 1, lower - 2, edge);
        rect(quads, matrix, !onScreen, right - 1, y + 2, right, lower - 2, edge);
        rect(quads, matrix, !onScreen, x + 1, y + 1, x + 2, y + 2, edge);
        rect(quads, matrix, !onScreen, right - 2, y + 1, right - 1, y + 2, edge);
        rect(quads, matrix, !onScreen, x + 1, lower - 2, x + 2, lower - 1, edge);
        rect(quads, matrix, !onScreen, right - 2, lower - 2, right - 1, lower - 1, edge);
        if (tail) {
            float center = x + width / 2f;
            for (int step = 0; step < TAIL; step++) {
                float half = TAIL - step;
                float row = lower - 1 + step;
                rect(quads, matrix, !onScreen, center - half + 1, row, center + half - 1, row + 1, paper);
                rect(quads, matrix, !onScreen, center - half, row, center - half + 1, row + 1, edge);
                rect(quads, matrix, !onScreen, center + half - 1, row, center + half, row + 1, edge);
            }
            rect(quads, matrix, !onScreen, center - 1, lower - 1 + TAIL, center + 1, lower + TAIL, edge);
        }
        flush(buffers, type, throughWalls || onScreen);
    }

    /**
     * Behind a wall and on the screen nothing is depth-tested, so the bubble is drawn now and its
     * text after it; in sight the text's depth offset keeps it over the bubble anyway.
     */
    private static void flush(MultiBufferSource.BufferSource buffers, RenderType type, boolean now) {
        if (now) {
            buffers.endBatch(type);
        }
    }

    private static Font.DisplayMode mode(boolean throughWalls) {
        return throughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET;
    }

    /**
     * A flat rectangle; in the world facing both ways, so it shows whatever the camera's side. On the
     * screen one side is enough, and two would show a faint bubble twice as strong.
     */
    private static void rect(VertexConsumer quads, Matrix4f matrix, boolean bothSides, float x0, float y0, float x1,
                             float y1, int color) {
        // In the world the text background takes a light value; the GUI's rectangles have none.
        corner(quads, matrix, x0, y0, color, bothSides);
        corner(quads, matrix, x0, y1, color, bothSides);
        corner(quads, matrix, x1, y1, color, bothSides);
        corner(quads, matrix, x1, y0, color, bothSides);
        if (bothSides) {
            corner(quads, matrix, x0, y0, color, true);
            corner(quads, matrix, x1, y0, color, true);
            corner(quads, matrix, x1, y1, color, true);
            corner(quads, matrix, x0, y1, color, true);
        }
    }

    private static void corner(VertexConsumer quads, Matrix4f matrix, float x, float y, int color, boolean lit) {
        VertexConsumer vertex = quads.addVertex(matrix, x, y, 0).setColor(color);
        if (lit) {
            vertex.setLight(LightTexture.FULL_BRIGHT);
        }
    }

    /** Three dots bouncing one after another, like a chat app's "typing…". */
    private static void drawDots(Font font, Matrix4f matrix, MultiBufferSource buffers, float y, long now, float alpha,
                                 boolean throughWalls) {
        int ink = ((int) (alpha * 255) << 24) | INK;
        float start = -DOT_SPACING * 1.5f + 1;
        for (int i = 0; i < 3; i++) {
            float bounce = Math.round(Math.max(0, Math.sin((now / 160.0) - i * 0.9)) * 2);
            font.drawInBatch("•", start + i * DOT_SPACING, y - bounce, ink, false, matrix, buffers,
                    mode(throughWalls), 0, LightTexture.FULL_BRIGHT);
        }
    }
}
