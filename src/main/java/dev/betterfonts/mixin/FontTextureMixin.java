package dev.betterfonts.mixin;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.betterfonts.BetterFontsMod;
import dev.betterfonts.font.BetterGlyphProvider;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自作グリフ専用のアトラスだけ、サンプラーを線形補間 (LINEAR) にする。
 * バニラ標準のドット文字 (Unifont 等) は従来どおり最近傍のまま。
 */
@Mixin(FontTexture.class)
public abstract class FontTextureMixin {
    @Unique private boolean betterfonts$linear;

    /** コンストラクタの「FilterMode.NEAREST」を、自作グリフ用アトラスのときだけ LINEAR に差し替える。 */
    @Redirect(method = "<init>",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lcom/mojang/renderpearl/api/textures/FilterMode;NEAREST:Lcom/mojang/renderpearl/api/textures/FilterMode;"),
            require = 0)
    private FilterMode betterfonts$filter() {
        BetterFontsMod.linearHook = true;
        boolean linear = BetterGlyphProvider.bakingLinear();
        this.betterfonts$linear = linear;
        return linear ? FilterMode.LINEAR : FilterMode.NEAREST;
    }

    /** 線形アトラスには自作グリフだけ、通常アトラスにはバニラのグリフだけを入れる。 */
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void betterfonts$route(GlyphInfo info, GlyphBitmap glyph, CallbackInfoReturnable<BakedSheetGlyph> cir) {
        if (!BetterFontsMod.linearHook) return;              // フックが効いていなければ何もしない (無限にアトラスが増えるのを防ぐ)
        if (this.betterfonts$linear != BetterGlyphProvider.bakingLinear()) cir.setReturnValue(null);
    }
}
