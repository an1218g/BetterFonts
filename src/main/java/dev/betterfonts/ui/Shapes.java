package dev.betterfonts.ui;

import dev.betterfonts.font.TextRasterizer;
import dev.betterfonts.font.TextRasterizer.Tex;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角丸 + 枠線の矩形を、実ピクセル解像度のアンチエイリアス付きテクスチャとして生成して描く。
 * (バニラの fill() は GUI 単位の矩形しか描けないため)
 */
public final class Shapes {
    private record Key(int w, int h, int tl, int tr, int br, int bl, int bw, int fill, int border) {}
    private static final Map<Key, Tex> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Tex> e) {
            if (size() > 160) { TextRasterizer.release(e.getValue().id()); return true; }
            return false;
        }
    };

    public static void clear() {
        CACHE.values().forEach(t -> TextRasterizer.release(t.id()));
        CACHE.clear();
    }

    /** x,y,w,h は GUI 単位。半径・枠線は実ピクセル。bwPx=0 で枠線なし。 */
    public static void round(GuiGraphicsExtractor g, float x, float y, float w, float h,
                             float rtl, float rtr, float rbr, float rbl, int bwPx, int fill, int border) {
        float gs = Ui.gs();
        int wp = Math.max(1, Math.round(w * gs)), hp = Math.max(1, Math.round(h * gs));
        Key k = new Key(wp, hp, Math.round(rtl), Math.round(rtr), Math.round(rbr), Math.round(rbl), bwPx, fill, border);
        Tex t = CACHE.get(k);
        if (t == null) {
            t = new Tex(TextRasterizer.uploadArgb(make(k), wp, hp), wp, hp, 0, 0, gs);
            CACHE.put(k, t);
        }
        TextRasterizer.blit(g, t, x, y, 1f / gs, 0xFFFFFFFF);
    }

    private static int[] make(Key k) {
        int w = k.w(), h = k.h();
        int[] out = new int[w * h];
        float fa = (k.fill() >>> 24) / 255f, ba = (k.border() >>> 24) / 255f;
        int fr = (k.fill() >> 16) & 0xFF, fg = (k.fill() >> 8) & 0xFF, fb = k.fill() & 0xFF;
        int br = (k.border() >> 16) & 0xFF, bg = (k.border() >> 8) & 0xFF, bb = k.border() & 0xFF;
        float hw = w / 2f, hh = h / 2f, maxR = Math.min(hw, hh);
        for (int y = 0; y < h; y++) {
            float py = y + 0.5f;
            for (int x = 0; x < w; x++) {
                float px = x + 0.5f;
                float r = px < hw ? (py < hh ? k.tl() : k.bl()) : (py < hh ? k.tr() : k.br());
                r = Math.min(r, maxR);
                float qx = Math.abs(px - hw) - (hw - r), qy = Math.abs(py - hh) - (hh - r);
                float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
                float d = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
                float aOut = clamp(0.5f - d);
                if (aOut <= 0) continue;
                float aIn = k.bw() > 0 ? clamp(0.5f - (d + k.bw())) : 1f;
                float wb = aOut * (1 - aIn) * ba, wf = aIn * fa, a = wb + wf;
                if (a <= 0) continue;
                int rr = Math.round((br * wb + fr * wf) / a), gg = Math.round((bg * wb + fg * wf) / a),
                    bl = Math.round((bb * wb + fb * wf) / a);
                out[y * w + x] = (Math.round(a * 255) << 24) | (rr << 16) | (gg << 8) | bl;
            }
        }
        return out;
    }

    private static float clamp(float v) { return v < 0 ? 0 : Math.min(v, 1); }
}
