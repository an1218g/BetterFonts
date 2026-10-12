package dev.betterfonts.ui;

import dev.betterfonts.BetterFontsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Mod 独自デザインのボタン。クリック処理は vanilla の Button を継承してそのまま使い、描画だけ差し替える。
 * 26.x では extractContents をオーバーライドする。
 */
public class ModButton extends Button {
    public enum Style { NORMAL, SELECTED, PRIMARY, NAV, NAV_SELECTED }

    private final Style style;
    private final String icon;
    private final String label;

    public ModButton(int x, int y, int w, int h, String label, String icon, Style style, Runnable press) {
        super(x, y, w, h, Component.literal(label), b -> press.run(), DEFAULT_NARRATION);
        this.label = label;
        this.icon = icon;
        this.style = style;
    }

    public ModButton(int x, int y, int w, int h, String label, Style style, Runnable press) {
        this(x, y, w, h, label, null, style, press);
    }

    // 26.x: extractRenderState は final。ボタン内容の描画は extractContents を上書きする
    @Override
    public void extractContents(GuiGraphicsExtractor g, int mx, int my, float dt) {
        var cfg = BetterFontsMod.config;
        Font font = Minecraft.getInstance().font;
        boolean hover = this.active && isHoveredOrFocused();
        int fill, border, bw = 3, text = this.active ? Ui.DARK : 0xFF9A9A9A;
        switch (style) {
            case SELECTED -> { fill = hover ? 0xFFF4F4F4 : Ui.WHITE; border = Ui.GREEN; }
            case PRIMARY -> { fill = hover ? 0xFFD2F3DB : Ui.PILL; border = 0xFFBFE6CA; text = Ui.GREEN; }
            case NAV -> { fill = hover ? 0xFFEDEDED : 0; border = 0; bw = 0; }
            case NAV_SELECTED -> { fill = Ui.BG; border = 0; bw = 0; }
            default -> { fill = hover ? 0xFFF0F0F0 : Ui.WHITE; border = Ui.BLOCK_BORDER; }
        }
        float gs = Ui.gs();
        float r = Math.min(cfg.cornerRadius / 2f, getHeight() * gs / 2f);
        if ((fill >>> 24) != 0)
            Shapes.round(g, getX(), getY(), getWidth(), getHeight(), r, r, r, r, bw, fill, border);

        float iconW = (icon != null && Icons.has(icon)) ? 16 : 0;
        float tw = Ui.width(font, label);
        boolean nav = style == Style.NAV || style == Style.NAV_SELECTED;
        float x0 = nav ? getX() + 8 : getX() + (getWidth() - (iconW + tw)) / 2f;
        if (iconW > 0) Icons.draw(g, icon, x0, getY() + (getHeight() - 12) / 2f, 12, text);
        g.text(font, label, (int) (x0 + iconW), getY() + (getHeight() - 8) / 2, text, false);
    }
}
