package dev.betterfonts.font;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.stb.STBTruetype.*;

/** stb_truetype ラッパー。複数フォント (フォールバックスタック) で文字列をラスタライズする。 */
public final class FontFace implements AutoCloseable {
    public record Bitmap(int w, int h, byte[] alpha, int baseline, int pad) {}
    private record Layout(int[] cps, FontFace[] faces, float[] xs, float width) {}

    private static final int PAD = 2;
    private final String id;
    private final ByteBuffer data;
    private final STBTTFontinfo info;

    private FontFace(String id, ByteBuffer data, STBTTFontinfo info) {
        this.id = id; this.data = data; this.info = info;
    }

    public static FontFace load(String id, byte[] bytes) {
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes).flip();
        STBTTFontinfo info = STBTTFontinfo.malloc();
        if (!stbtt_InitFont(info, buf)) {
            info.free();
            MemoryUtil.memFree(buf);
            return null;
        }
        return new FontFace(id, buf, info);
    }

    public String id() { return id; }

    /** 1 グリフ分のビットマップ。left/top = 原点(ペン位置)・ベースラインからの px オフセット (y は下向き)。 */
    public record GlyphBmp(int w, int h, byte[] alpha, int left, int top) {}

    /** emUnits を 1em としたときの advance (emUnits と同じ単位)。 */
    public float advance(int cp, float emUnits) {
        try (MemoryStack ms = MemoryStack.stackPush()) {
            IntBuffer adv = ms.mallocInt(1), lsb = ms.mallocInt(1);
            stbtt_GetCodepointHMetrics(info, cp, adv, lsb);
            return adv.get(0) * scale(emUnits);
        }
    }

    /** 1 文字をラスタライズ。字形が空 (空白など) なら null。 */
    public GlyphBmp glyph(int cp, float emPx) {
        try (MemoryStack ms = MemoryStack.stackPush()) {
            float sc = scale(emPx);
            IntBuffer x0 = ms.mallocInt(1), y0 = ms.mallocInt(1), x1 = ms.mallocInt(1), y1 = ms.mallocInt(1);
            stbtt_GetCodepointBitmapBox(info, cp, sc, sc, x0, y0, x1, y1);
            int w = x1.get(0) - x0.get(0), h = y1.get(0) - y0.get(0);
            if (w <= 0 || h <= 0) return null;
            ByteBuffer buf = MemoryUtil.memAlloc(w * h);
            try {
                stbtt_MakeCodepointBitmap(info, buf, w, h, w, sc, sc, cp);
                byte[] a = new byte[w * h];
                buf.get(0, a);
                return new GlyphBmp(w, h, a, x0.get(0), y0.get(0));
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }
    public boolean hasGlyph(int cp) { return stbtt_FindGlyphIndex(info, cp) != 0; }
    private float scale(float emPx) { return stbtt_ScaleForMappingEmToPixels(info, emPx); }

    /** 空白・制御文字以外が、スタックのどれかのフォントに含まれるか。 */
    public static boolean covers(List<FontFace> st, String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 32 && pickOrNull(st, cp) == null) return false;
        }
        return true;
    }

    private static FontFace pickOrNull(List<FontFace> st, int cp) {
        if (st instanceof FontStack fs) return fs.pickOrNull(cp);
        for (FontFace f : st) if (f.hasGlyph(cp)) return f;
        return null;
    }

    private static FontFace pick(List<FontFace> st, int cp) {
        FontFace f = pickOrNull(st, cp);
        return f != null ? f : st.get(0);
    }

    private static Layout layout(List<FontFace> st, String text, float emPx, MemoryStack ms) {
        int[] cps = text.codePoints().toArray();
        FontFace[] fs = new FontFace[cps.length];
        float[] xs = new float[cps.length];
        for (int i = 0; i < cps.length; i++) fs[i] = pick(st, cps[i]);
        IntBuffer adv = ms.mallocInt(1), lsb = ms.mallocInt(1);
        float x = 0;
        for (int i = 0; i < cps.length; i++) {
            xs[i] = x;
            float sc = fs[i].scale(emPx);
            stbtt_GetCodepointHMetrics(fs[i].info, cps[i], adv, lsb);
            x += adv.get(0) * sc;
            if (i + 1 < cps.length && fs[i + 1] == fs[i])
                x += stbtt_GetCodepointKernAdvance(fs[i].info, cps[i], cps[i + 1]) * sc;
        }
        return new Layout(cps, fs, xs, x);
    }

    /** 文字列の幅 (emPx と同じ単位)。 */
    public static float measure(List<FontFace> st, String text, float emPx) {
        try (MemoryStack ms = MemoryStack.stackPush()) {
            return layout(st, text, emPx, ms).width();
        }
    }

    public static Bitmap render(List<FontFace> st, String text, float emPx, boolean subpixel) {
        try (MemoryStack ms = MemoryStack.stackPush()) {
            Layout lay = layout(st, text, emPx, ms);
            IntBuffer asc = ms.mallocInt(1), desc = ms.mallocInt(1), gap = ms.mallocInt(1);
            Set<FontFace> used = new LinkedHashSet<>();
            used.add(st.get(0));
            used.addAll(Arrays.asList(lay.faces()));
            float a = 0, d = 0;
            for (FontFace f : used) {
                stbtt_GetFontVMetrics(f.info, asc, desc, gap);
                float sc = f.scale(emPx);
                a = Math.max(a, asc.get(0) * sc);
                d = Math.max(d, -desc.get(0) * sc);
            }
            int baseline = (int) Math.ceil(a) + PAD;
            int h = baseline + (int) Math.ceil(d) + PAD;
            int w = (int) Math.ceil(lay.width()) + PAD * 2;
            byte[] alpha = new byte[w * h];

            IntBuffer x0 = ms.mallocInt(1), y0 = ms.mallocInt(1), x1 = ms.mallocInt(1), y1 = ms.mallocInt(1);
            for (int i = 0; i < lay.cps().length; i++) {
                int cp = lay.cps()[i];
                if (cp <= 32) continue;
                FontFace f = lay.faces()[i];
                float sc = f.scale(emPx);
                float px = lay.xs()[i] + PAD;
                int ix = (int) Math.floor(px);
                float fx = subpixel ? px - ix : 0f;
                stbtt_GetCodepointBitmapBoxSubpixel(f.info, cp, sc, sc, fx, 0f, x0, y0, x1, y1);
                int gw = x1.get(0) - x0.get(0), gh = y1.get(0) - y0.get(0);
                if (gw <= 0 || gh <= 0) continue;
                ByteBuffer bmp = MemoryUtil.memAlloc(gw * gh);
                try {
                    stbtt_MakeCodepointBitmapSubpixel(f.info, bmp, gw, gh, gw, sc, sc, fx, 0f, cp);
                    for (int yy = 0; yy < gh; yy++) {
                        int dy = baseline + y0.get(0) + yy;
                        if (dy < 0 || dy >= h) continue;
                        for (int xx = 0; xx < gw; xx++) {
                            int dx = ix + x0.get(0) + xx;
                            if (dx < 0 || dx >= w) continue;
                            int v = bmp.get(yy * gw + xx) & 0xFF;
                            int idx = dy * w + dx;
                            if (v > (alpha[idx] & 0xFF)) alpha[idx] = (byte) v;
                        }
                    }
                } finally {
                    MemoryUtil.memFree(bmp);
                }
            }
            return new Bitmap(w, h, alpha, baseline, PAD);
        }
    }

    @Override
    public void close() {
        info.free();
        MemoryUtil.memFree(data);
    }
}
