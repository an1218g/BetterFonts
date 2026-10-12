package dev.betterfonts.font;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.betterfonts.BetterFontsMod;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;

/**
 * バニラのフォント層 (FontSet) に差し込む GlyphProvider。
 * 文字ごとに FontStack から字形を stb でラスタライズし、バニラのグリフアトラスへ渡す。
 * これにより GUI・チャット・看板・ネームタグ・文字幅・折り返し・影・太字などがすべて同じ経路で切り替わる。
 * このフォントに無い文字は null を返し、バニラ標準のプロバイダ (ドット文字 / Unifont) にフォールバックする。
 *
 * 座標の約束 (バニラ): ベースラインは行頭 +7 (GUI 単位)。glyph.top = 7 - bearingTop。
 * 設定 (サイズ・縦位置・字間・影の距離) はグリフ生成時に焼き込むので、変更時は BetterFontsMod.refreshFonts() が必要。
 */
public final class BetterGlyphProvider implements GlyphProvider {
    /** 診断用 (Settings の Diagnostics に表示)。 */
    public static volatile int supportedCount = -1, facesCount = -1, bakedCount = 0, queriedCount = 0;

    private String supportedKey = "";
    private IntSet supported = IntSets.EMPTY_SET;

    /** 現在の GUI スケールに合わせた「1 GUI 単位あたりのピクセル数」 (Smoothness で増減)。 */
    static float oversampleNow() {
        float gui = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float q = "HIGH".equals(BetterFontsMod.config.smoothness) ? 2f : 1f;
        return Math.max(1f, Math.min(8f, gui * q));
    }

    @Override
    public UnbakedGlyph getGlyph(final int codepoint) {
        var cfg = BetterFontsMod.config;
        if (!cfg.enabled || codepoint < 32 || (codepoint >= 0x7F && codepoint < 0xA0)) return null;
        queriedCount++;
        FontFace face = FontLibrary.activeStack().pickOrNull(codepoint);
        if (face == null) return null;                      // このフォントに無い → バニラへ
        float em = TextRasterizer.emGui();
        float advance = face.advance(codepoint, em) + cfg.letterSpacing;
        if (codepoint == 32 || Character.isWhitespace(codepoint)) return new EmptyGlyph(Math.max(0f, advance));
        return new Glyph(face, codepoint, Math.max(0f, advance), oversampleNow(), em, cfg.yOffset, cfg.shadowOffset);
    }

    @Override
    public synchronized IntSet getSupportedGlyphs() {
        FontStack st = FontLibrary.activeStack();
        if (!BetterFontsMod.config.enabled || st.isEmpty()) return IntSets.EMPTY_SET;
        if (!st.id().equals(supportedKey)) {
            IntOpenHashSet set = new IntOpenHashSet();
            int[][] ranges = {{0x20, 0xD7FF}, {0xE000, 0xFFFF}, {0x20000, 0x2FFFF}};
            for (int[] r : ranges)
                for (int cp = r[0]; cp <= r[1]; cp++)
                    if (!(cp >= 0x7F && cp < 0xA0) && st.covers(cp)) set.add(cp);
            supported = set;
            supportedKey = st.id();
            supportedCount = set.size();
            facesCount = st.size();
            BetterFontsMod.LOG.info("[BetterFonts] glyph provider: {} faces, {} supported glyphs", st.size(), set.size());
        }
        return supported;
    }

    /** advance と影のずれを設定どおりにした GlyphInfo。 */
    private record Info(float advance, float shadowOffset) implements GlyphInfo {
        @Override public float getAdvance() { return this.advance; }
        @Override public float getShadowOffset() { return this.shadowOffset; }
    }

    private record Glyph(FontFace face, int cp, float advance, float oversample, float em, float yOffset, float shadowOffset)
            implements UnbakedGlyph {
        @Override
        public GlyphInfo info() { return new Info(this.advance, this.shadowOffset); }

        @Override
        public BakedGlyph bake(final UnbakedGlyph.Stitcher stitcher) {
            bakedCount++;
            FontFace.GlyphBmp b = face.glyph(cp, em * oversample);
            if (b == null || b.left() + b.w() <= 0) return new EmptyGlyph(advance).bake(stitcher);

            // ビットマップはペン位置 (x=0) から始める (左ベアリング分は透明で埋める。負の分だけ切る)
            final int w = b.left() + b.w(), h = b.h();
            final int off = Math.max(b.left(), 0), skip = Math.max(-b.left(), 0);
            final byte[] data = new byte[w * h];
            for (int r = 0; r < h; r++)
                for (int c = 0; c < b.w() - skip; c++)
                    data[r * w + off + c] = b.alpha()[r * b.w() + skip + c];
            final float os = oversample;
            final float bearingTop = -b.top() / os - yOffset;  // ベースラインからグリフ上端までの高さ (GUI 単位) - 縦位置補正

            return stitcher.stitch(info(), new GlyphBitmap() {
                @Override public float getOversample() { return os; }
                @Override public int getPixelWidth() { return w; }
                @Override public int getPixelHeight() { return h; }
                @Override public float getBearingTop() { return bearingTop; }
                @Override public boolean isColored() { return true; }       // RGBA (白 + アルファ)。色は頂点カラーで付く

                @Override
                public void upload(final int x, final int y, final GpuTexture texture) {
                    NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                    try {
                        for (int yy = 0; yy < h; yy++)
                            for (int xx = 0; xx < w; xx++)
                                img.setPixel(xx, yy, ((data[yy * w + xx] & 0xFF) << 24) | 0xFFFFFF);
                        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, img, 0, 0, x, y);
                    } finally {
                        img.close();
                    }
                }
            });
        }
    }
}
