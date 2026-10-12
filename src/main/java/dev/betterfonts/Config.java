package dev.betterfonts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    /** "OPENGL" | "VULKAN" (experimental) */
    public String renderer = "OPENGL";
    /** "LOW" | "NORMAL" | "HIGH" */
    public String smoothness = "NORMAL";
    public String activeFamily = "Noto Sans";
    public String activeStyle = "400";
    /** "1600x900" | "1280x720" | "640x360" | "AUTO"  (単位: 実ピクセル) */
    public String windowPreset = "1280x720";
    /** 角丸の半径 (実ピクセル, 0 = 角丸なし) */
    public int cornerRadius = 24;
    /** ブロックの枠線の太さ (実ピクセル) */
    public int borderPx = 4;
    /** 文字サイズ (% / 100 = バニラ相当) */
    public int fontSizePercent = 100;
    /** 縦位置の補正 (GUI 単位 / 負 = 上, 正 = 下) */
    public float yOffset = 0f;
    /** 文字間の追加幅 (GUI 単位) */
    public float letterSpacing = 0f;
    public boolean shadowEnabled = true;
    /** 影のずれ (GUI 単位 / バニラ = 1) */
    public float shadowOffset = 1f;
    /** 影の濃さ (% / バニラ = 100) */
    public int shadowOpacity = 100;
    /** 選択フォントに無い文字 (日本語など) を補うフォント。空 = なし */
    public String fallbackFamily = "";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("betterfonts.json");
    }

    public static Config load() {
        try {
            if (Files.exists(file())) {
                Config c = GSON.fromJson(Files.readString(file()), Config.class);
                if (c != null) return c;
            }
        } catch (Exception e) {
            BetterFontsMod.LOG.warn("Failed to read config", e);
        }
        Config c = new Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            BetterFontsMod.LOG.warn("Failed to save config", e);
        }
    }
}
