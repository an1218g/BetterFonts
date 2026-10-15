package dev.betterfonts.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.betterfonts.BetterFontsMod;
import dev.betterfonts.font.BetterGlyphProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * minecraft:default フォントの先頭に BetterGlyphProvider を差し込む。
 * resolveProviders の戻り値 (フォントID → プロバイダ列) は、後段で逆順にされて FontSet に渡されるため、
 * 列の「末尾」に足したものが最優先になる。
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void betterfonts$capture(CallbackInfo ci) {
        BetterFontsMod.fontManager = (FontManager) (Object) this;
        BetterFontsMod.LOG.info("[BetterFonts] FontManager captured");
    }

    @Inject(method = "resolveProviders", at = @At("RETURN"), require = 0)
    private void betterfonts$addProvider(CallbackInfoReturnable<Map<Identifier, List<GlyphProvider.Conditional>>> cir) {
        Map<Identifier, List<GlyphProvider.Conditional>> map = cir.getReturnValue();
        List<GlyphProvider.Conditional> list = map.get(Minecraft.DEFAULT_FONT);
        if (list == null) {
            BetterFontsMod.LOG.warn("[BetterFonts] default font not found in {}", map.keySet());
            return;
        }
        List<GlyphProvider.Conditional> copy = new ArrayList<>(list);
        copy.add(new GlyphProvider.Conditional(new BetterGlyphProvider(), FontOption.Filter.ALWAYS_PASS));
        map.put(Minecraft.DEFAULT_FONT, copy);
        BetterFontsMod.providerActive = true;
        BetterFontsMod.LOG.info("[BetterFonts] glyph provider injected into {} ({} providers before)", Minecraft.DEFAULT_FONT, list.size());
    }
}
