package dev.betterfonts.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 入力処理は vanilla の EditBox のまま、描画だけ Mod のフォントで行う入力欄。
 * 26.x では AbstractWidget.extractRenderState が final なので、extractWidgetRenderState を上書きする
 * (vanilla の黒い背景・ドット文字をまるごと置き換える)。
 */
public class ModEditBox extends EditBox {
    private final String hint;

    public ModEditBox(Font font, int x, int y, int w, int h, String hint) {
        super(font, x, y, w, h, Component.literal(hint));
        this.hint = hint;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Font font = Minecraft.getInstance().font;
        String v = getValue();
        int x = getX(), w = getWidth();
        int y = getY() + (getHeight() - 8) / 2;
        boolean focused = isFocused();

        if (v.isEmpty() && !focused) g.text(font, hint, x, y, 0xFF8A8A8A, false);

        int cur = Math.max(0, Math.min(getCursorPosition(), v.length()));
        int start = 0;                                              // カーソルが見える位置まで左を切る
        while (start < cur && Ui.width(font, v.substring(start, cur)) > w - 4) start++;

        g.enableScissor(x, getY(), x + w, getY() + getHeight());
        if (!v.isEmpty()) g.text(font, v.substring(start), x, y, Ui.DARK, false);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = (int) (x + Ui.width(font, v.substring(start, cur)));
            g.fill(cx, y - 1, cx + 1, y + 9, Ui.DARK);
        }
        g.disableScissor();
    }
}
