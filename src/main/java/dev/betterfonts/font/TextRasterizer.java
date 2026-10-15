package dev.betterfonts.font;

import com.mojang.blaze3d.platform.NativeImage;
import dev.betterfonts.BetterFontsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文字列 → テクスチャ → バニラの GUI パイプラインで描画 (GL / Vulkan どちらでも動く)。
 * バニラ API を触る箇所はこのクラスに集約: テクスチャ生成 (uploadArgb) と blit。
 */
public final class TextRasterizer {
    public static final float EM_GUI = 9f;
    private static final int MAX_CACHE = 600;

    /** 設定のフォントサイズを反映した 1em (GUI 単位)。 */
    public static float emGui() {
        return EM_GUI * Math.max(50, Math.min(300, BetterFontsMod.config.fontSizePercent)) / 100f;
    }

    public record Tex(Identifier id, int w, int h, int baseline, int pad, float pxPerGui) {}
    private record Key(String faces, float emPx, boolean sub, String text) {}

    private static int counter;
    private static final Map<Key, Tex> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Tex> e) {
            if (size() > MAX_CACHE) { release(e.getValue().id()); return true; }
            return false;
        }
    };

    public static void clear() {
        CACHE.values().forEach(t -> release(t.id()));
        CACHE.clear();
    }

    public static void release(Identifier id) {
        Minecraft.getInstance().getTextureManager().release(id);
    }

    /** ARGB ピクセル配列をテクスチャとして登録する。 */
    public static Identifier uploadArgb(int[] argb, int w, int h) {
        // [要確認] NativeImage コンストラクタ / setPixel の色順 (ARGB 想定) / DynamicTexture のコンストラクタ
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.setPixel(x, y, argb[y * w + x]);
        Identifier id = Identifier.fromNamespaceAndPath(BetterFontsMod.ID, "gen/" + (counter++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "betterfonts gen", img));
        return id;
    }

    /** 8bit アルファ → 白 + アルファのテクスチャ。 */
    public static Identifier uploadAlpha(byte[] alpha, int w, int h) {
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) argb[i] = ((alpha[i] & 0xFF) << 24) | 0xFFFFFF;
        return uploadArgb(argb, w, h);
    }

    private static Tex get(List<FontFace> st, String text, float emGui) {
        var cfg = BetterFontsMod.config;
        float q = "HIGH".equals(cfg.smoothness) ? 2f : 1f;           // High = 2x スーパーサンプリング
        boolean sub = !"LOW".equals(cfg.smoothness);                  // Low = サブピクセル配置なし
        float ppg = (float) Minecraft.getInstance().getWindow().getGuiScale() * q;
        String ids = st instanceof FontStack fs ? fs.id() : st.stream().map(FontFace::id).collect(Collectors.joining("|"));
        Key k = new Key(ids, emGui * ppg, sub, text);
        Tex t = CACHE.get(k);
        if (t != null) return t;
        FontFace.Bitmap b = FontFace.render(st, text, emGui * ppg, sub);
        if (b == null || b.w() <= 0) return null;
        t = new Tex(uploadAlpha(b.alpha(), b.w(), b.h()), b.w(), b.h(), b.baseline(), b.pad(), ppg);
        CACHE.put(k, t);
        return t;
    }

    /** ミキシンから呼ぶ。描画したら true、バニラに任せるべきなら false。 */
    public static boolean drawString(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean shadow) {
        if (text.indexOf('\u00A7') >= 0) return false;                // § 書式コードはバニラに任せる
        List<FontFace> st = FontLibrary.activeStack();
        if (st.isEmpty() || !FontFace.covers(st, text)) return false; // どのフォントにも無い文字はバニラへ
        if (text.isBlank()) return true;
        draw(g, st, text, x, y, color, shadow, EM_GUI);
        return true;
    }

    /** 任意のフォントスタックで描画。戻り値は GUI 単位の幅。 */
    public static float draw(GuiGraphicsExtractor g, List<FontFace> st, String text, float x, float y,
                             int color, boolean shadow, float emGui) {
        Tex t = get(st, text, emGui);
        if (t == null) return 0;
        if ((color >>> 24) == 0) color |= 0xFF000000;
        float s = 1f / t.pxPerGui();
        float tx = x - t.pad() * s;
        float ty = y + emGui * (7f / 9f) - t.baseline() * s;          // バニラのベースラインは行頭 +7
        if (shadow) {
            int sc = (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2);
            blit(g, t, tx + 1, ty + 1, s, sc);
        }
        blit(g, t, tx, ty, s, color);
        return t.w() * s;
    }

    public static void blit(GuiGraphicsExtractor g, Tex t, float x, float y, float scale, int color) {
        // [要確認] pose() は Matrix3x2fStack、blit のオーバーロード (region + color 付き) を想定
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), 0, 0, 0f, 0f, t.w(), t.h(), t.w(), t.h(), t.w(), t.h(), color);
        g.pose().popMatrix();
    }
}
