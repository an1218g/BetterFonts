package dev.betterfonts;

import com.mojang.blaze3d.platform.InputConstants;
import dev.betterfonts.font.FontLibrary;
import dev.betterfonts.font.GoogleFontsApi;
import dev.betterfonts.ui.BetterFontsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterFontsMod implements ClientModInitializer {
    public static final String ID = "betterfonts";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    public static Config config = new Config();
    private static KeyMapping openKey;
    /** FontManager (Mixin で捕捉)。フォント再読み込みに使う。 */
    public static net.minecraft.client.gui.font.FontManager fontManager;
    /** バニラのフォント層への差し込みが有効か。true の間は旧オーバーレイ方式を止める。 */
    public static boolean providerActive;
    private static float lastGuiScale = -1;

    /** 設定・フォント・GUI スケールが変わったとき: バニラのグリフ表を作り直す (resource reload は不要)。 */
    public static void refreshFonts() {
        dev.betterfonts.font.TextRasterizer.clear();
        Minecraft mc = Minecraft.getInstance();
        LOG.info("[BetterFonts] refreshFonts (manager={}, active={})", fontManager != null, providerActive);
        if (fontManager != null && mc.options != null) fontManager.updateOptions(mc.options);
    }

    @Override
    public void onInitializeClient() {
        config = Config.load();
        FontLibrary.init();
        GoogleFontsApi.loadCatalog();

        // [要確認] 26.x の KeyMapping.Category / コンストラクタ
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterfonts.open", InputConstants.KEY_F9, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            float gs = (float) mc.getWindow().getGuiScale();     // GUI スケールが変わったら、解像度に合わせて作り直す
            if (lastGuiScale < 0) { lastGuiScale = gs; refreshFonts(); }   // 初回: 起動時の設定でグリフ表を作る
            else if (gs != lastGuiScale) { lastGuiScale = gs; refreshFonts(); }
            while (openKey.consumeClick()) {
                mc.setScreenAndShow(new BetterFontsScreen()); // 26.2+ の名前 (旧 setScreen)
            }
        });
    }
}
