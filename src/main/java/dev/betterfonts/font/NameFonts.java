package dev.betterfonts.font;

import dev.betterfonts.BetterFontsMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一覧のフォント名を「そのフォント自身の Regular」で描くための取得係。
 * インストール済みならローカルのフォント、未インストールなら Google Fonts の &text= で
 * 名前の文字だけのサブセット(数KB)を取得してキャッシュする。取得中は空リスト (呼び出し側は代替描画)。
 */
public final class NameFonts {
    private enum St { PENDING, READY, FAILED }
    private static final Map<String, St> STATE = new ConcurrentHashMap<>();
    private static final Map<String, FontStack> FACES = new HashMap<>();   // レンダースレッド専用
    private static final List<FontFace> NONE = List.of();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "betterfonts-names");
        t.setDaemon(true);
        return t;
    });

    private static Path file(String name) {
        return FontLibrary.cacheDir().resolve("_names").resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".ttf");
    }

    public static void forget(String name) { FACES.remove(name); }

    public static List<FontFace> get(String name) {
        if (name.equals(FontLibrary.BUILTIN) && FontLibrary.builtin() != null) {
            return FACES.computeIfAbsent(name, k -> new FontStack(List.of(FontLibrary.builtin())));
        }
        FontStack hit = FACES.get(name);
        if (hit != null) return hit;

        if (FontLibrary.isInstalled(name)) {
            List<FontFace> l = FontLibrary.loadStyle(false, name, FontLibrary.defaultStyle(name));
            if (!l.isEmpty()) { FontStack fs = new FontStack(l); FACES.put(name, fs); return fs; }
        }

        St s = STATE.get(name);
        if (s == St.FAILED || s == St.PENDING) return NONE;
        Path p = file(name);
        if (s == St.READY || Files.exists(p)) {
            try {
                FontFace f = FontFace.load("name:" + name, Files.readAllBytes(p));
                if (f != null) { FontStack fs = new FontStack(List.of(f)); FACES.put(name, fs); return fs; }
            } catch (Exception ignored) {}
            STATE.put(name, St.FAILED);
            return NONE;
        }
        GoogleFontsApi.Family fam = GoogleFontsApi.family(name);
        if (fam == null) { STATE.put(name, St.FAILED); return NONE; }
        STATE.put(name, St.PENDING);
        POOL.execute(() -> {
            try {
                GoogleFontsApi.downloadNameSubset(fam, p);
                STATE.put(name, St.READY);
            } catch (Exception e) {
                BetterFontsMod.LOG.debug("Name subset failed for {}: {}", name, e.toString());
                STATE.put(name, St.FAILED);
            }
        });
        return NONE;
    }
}
