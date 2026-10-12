package dev.betterfonts.mixin;

import dev.betterfonts.BetterFontsMod;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 影の色 (バニラ: 文字色 x 0.25) の ON/OFF と濃さを設定どおりにする。
 * Style で影の色が明示されている文字 (コマンド等) は触らない。
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class FontShadowMixin {
    @Inject(method = "getShadowColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void betterfonts$shadow(Style style, int textColor, CallbackInfoReturnable<Integer> cir) {
        var c = BetterFontsMod.config;
        if (!c.enabled || style.getShadowColor() != null) return;
        int shadow = cir.getReturnValue();
        if (shadow == 0) return;                                   // 影なしで描かれる文字はそのまま
        if (!c.shadowEnabled) { cir.setReturnValue(0); return; }
        if (c.shadowOpacity < 100) {
            int a = Math.round(ARGB.alpha(shadow) * Math.max(0, c.shadowOpacity) / 100f);
            cir.setReturnValue(ARGB.color(a, shadow));
        }
    }
}
