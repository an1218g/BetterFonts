package dev.betterfonts.ui;

import dev.betterfonts.BetterFontsMod;
import dev.betterfonts.font.FontFace;
import dev.betterfonts.font.FontLibrary;
import dev.betterfonts.font.TextRasterizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** 色・テキスト幅・共通パーツ。 */
public final class Ui {
    public static final int DARK = 0xFF111111, GRAY = 0xFF666666, GREEN = 0xFF1E7B45, WHITE = 0xFFFFFFFF,
            BG = 0xFFF4F4F4, BLOCK_BORDER = 0xFFA4A4A4, PILL = 0xFFE3F8E8;

    public static float gs() { return (float) Minecraft.getInstance().getWindow().getGuiScale(); }

    /** 実際に描画されるフォントでの幅 (GUI 単位)。置換されない文字列はバニラ幅。 */
    public static float width(Font font, String s) {
        var st = FontLibrary.activeStack();
        if (BetterFontsMod.config.enabled && !st.isEmpty() && s.indexOf('\u00A7') < 0 && FontFace.covers(st, s))
            return FontFace.measure(st, s, TextRasterizer.emGui());
        return font.width(s);
    }

    public static List<String> wrap(Font font, String s, float maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() > 0 && width(font, next) > maxW) { lines.add(cur.toString()); cur = new StringBuilder(word); }
            else { cur = new StringBuilder(next); }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /** 枠線付きブロック (太さ・色は設定/仕様どおり 4px / #a4a4a4)。 */
    public static void card(GuiGraphicsExtractor g, float x, float y, float w, float h) {
        var c = BetterFontsMod.config;
        float r = c.cornerRadius / 2f;
        Shapes.round(g, x, y, w, h, r, r, r, r, c.borderPx, WHITE, BLOCK_BORDER);
    }

    /** バッジ (In use / Downloaded)。右端 xRight に揃えて描く。 */
    public static void pill(GuiGraphicsExtractor g, Font font, String text, float xRight, float y) {
        float w = width(font, text) + 10, h = 12;
        float x = xRight - w;
        float r = h * gs() / 2f;
        Shapes.round(g, x, y, w, h, r, r, r, r, 0, PILL, 0);
        g.text(font, text, (int) (x + 5), (int) (y + 2), GREEN, false);
    }
}
