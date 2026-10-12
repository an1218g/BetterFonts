package dev.betterfonts.mixin;

import dev.betterfonts.BetterFontsMod;
import dev.betterfonts.font.TextRasterizer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * バニラの文字描画の「上に被せる」フック。
 * [要確認] メソッド記述子は 26.3 の実ソースで合わせること。外れても require=0 なので起動は落ちず、単に無効化される。
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void betterfonts$string(Font font, String s, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (BetterFontsMod.providerActive || s == null || s.isEmpty() || !BetterFontsMod.config.enabled) return;
        if (TextRasterizer.drawString((GuiGraphicsExtractor) (Object) this, s, x, y, color, shadow)) ci.cancel();
    }

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void betterfonts$component(Font font, Component c, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (BetterFontsMod.providerActive || c == null || !BetterFontsMod.config.enabled) return;
        // 装飾なしの単純なテキストのみ置換 (色・太字・クリックイベント付きはバニラへ)
        if (!c.getSiblings().isEmpty() || !c.getStyle().isEmpty() || !(c.getContents() instanceof PlainTextContents)) return;
        if (TextRasterizer.drawString((GuiGraphicsExtractor) (Object) this, c.getString(), x, y, color, shadow)) ci.cancel();
    }

    /** ボタンや入力欄など、バニラウィジェットが使う経路。装飾なしのときだけ置換。 */
    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void betterfonts$sequence(Font font, FormattedCharSequence seq, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (BetterFontsMod.providerActive || seq == null || !BetterFontsMod.config.enabled) return;
        StringBuilder sb = new StringBuilder();
        boolean[] plain = {true};
        seq.accept((i, style, cp) -> {
            if (!style.isEmpty()) plain[0] = false;
            sb.appendCodePoint(cp);
            return plain[0];
        });
        if (!plain[0] || sb.length() == 0) return;
        if (TextRasterizer.drawString((GuiGraphicsExtractor) (Object) this, sb.toString(), x, y, color, shadow)) ci.cancel();
    }
}
