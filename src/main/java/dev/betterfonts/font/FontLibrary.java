package dev.betterfonts.font;

import dev.betterfonts.BetterFontsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * ダウンロード済みフォントの管理と、現在使用中フォントの解決。
 * 1 つのスタイル (例 "400") は 1 ファイル "400.ttf"、または Google 側で分割されている場合は
 * "400~0.ttf", "400~1.ttf", ... の複数ファイル (日本語フォントなど)。
 */
public final class FontLibrary {
    public static final String BUILTIN = "Noto Sans";
    private static final Map<String, List<FontFace>> STYLE_CACHE = new HashMap<>();
    private static final Set<String> INSTALLED = new TreeSet<>();
    private static final Set<String> MISSING = new HashSet<>();
    private static FontFace builtinFace;
    private static int version;
    private static String stackKey = "";
    private static FontStack stackVal = new FontStack(List.of());

    /** キャッシュの保存形式。形式を変えたら上げる (古いキャッシュは起動時に消える)。v2 = 分割ファイル対応。 */
    private static final String CACHE_FORMAT = "2";

    public static void init() {
        try {
            Files.createDirectories(fontsDir());
            Path marker = root().resolve("cache-format");
            String cur = Files.exists(marker) ? Files.readString(marker).trim() : "";
            if (!CACHE_FORMAT.equals(cur)) {          // 旧形式 (日本語フォントがラテン文字の断片だけ) のキャッシュを破棄
                deleteDir(cacheDir());
                Files.writeString(marker, CACHE_FORMAT);
            }
        } catch (Exception ignored) {}
        rescan();
    }

    public static Path root() { return FabricLoader.getInstance().getGameDir().resolve("betterfonts"); }
    public static Path fontsDir() { return root().resolve("fonts"); }
    public static Path cacheDir() { return root().resolve("cache"); }
    public static Path familyDir(boolean cache, String family) {
        return (cache ? cacheDir() : fontsDir()).resolve(family);
    }

    /** TTF / OTF / TTC のマジックナンバーを確認 (WOFF などを弾く)。 */
    public static boolean isSfnt(byte[] b) {
        if (b == null || b.length < 4) return false;
        int m = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        return m == 0x00010000 || m == 0x4F54544F || m == 0x74727565 || m == 0x74746366;
    }

    private static boolean isValidFile(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return isSfnt(in.readNBytes(4));
        } catch (Exception e) { return false; }
    }

    /** フォルダを走査し、壊れたファイルは削除して一覧を更新する。 */
    public static synchronized void rescan() {
        INSTALLED.clear();
        MISSING.clear();
        STYLE_CACHE.clear();
        version++;
        try (Stream<Path> dirs = Files.list(fontsDir())) {
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                String n = d.getFileName().toString();
                if (!styles(false, n).isEmpty()) INSTALLED.add(n);
            }
        } catch (Exception ignored) {}
    }

    public static boolean isInstalled(String family) { return INSTALLED.contains(family); }

    public static List<String> installedFamilies() {
        List<String> out = new ArrayList<>();
        out.add(BUILTIN);
        for (String n : INSTALLED) if (!n.equals(BUILTIN)) out.add(n);
        return out;
    }

    /** "400~3.ttf" → "400" */
    private static String baseKey(String fileName) {
        String n = fileName.substring(0, fileName.length() - 4);
        int t = n.indexOf('~');
        return t >= 0 ? n.substring(0, t) : n;
    }

    /** 有効なスタイルキー一覧 (分割ファイルは 1 つにまとめる)。無効なファイルはここで削除される。 */
    public static List<String> styles(boolean cache, String family) {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> s = Files.list(familyDir(cache, family))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".ttf")) continue;
                if (!isValidFile(p)) { try { Files.delete(p); } catch (Exception ignored) {} continue; }
                keys.add(baseKey(n));
            }
        } catch (Exception ignored) {}
        List<String> out = new ArrayList<>(keys);
        out.sort(Comparator.comparingInt(GoogleFontsApi::weightOf).thenComparing(k -> k.endsWith("i")));
        return out;
    }

    public static String defaultStyle(String family) {
        List<String> s = styles(false, family);
        return s.isEmpty() || s.contains("400") ? "400" : s.get(0);
    }

    /** 1 スタイル分のフォント (分割されていれば全部)。無ければ空リスト。 */
    public static synchronized List<FontFace> loadStyle(boolean cache, String family, String key) {
        Path dir = familyDir(cache, family);
        String ck = dir + "/" + key;
        List<FontFace> hit = STYLE_CACHE.get(ck);
        if (hit != null) return hit;
        if (!cache && MISSING.contains(ck)) return List.of();     // DL 中のキャッシュは「まだ無い」だけなので記憶しない
        List<FontFace> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".ttf") && baseKey(n).equals(key)) files.add(p);
            }
            files.sort(Comparator.comparing(Path::toString));
            for (Path p : files) {
                FontFace f = FontFace.load(p.toString(), Files.readAllBytes(p));
                if (f != null) out.add(f);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) { if (!cache) MISSING.add(ck); return List.of(); }
        STYLE_CACHE.put(ck, out);
        return out;
    }

    public static FontFace builtin() {
        if (builtinFace == null) {
            try (InputStream in = FontLibrary.class.getResourceAsStream("/assets/betterfonts/fonts/NotoSans-Regular.ttf")) {
                if (in != null) builtinFace = FontFace.load("builtin:NotoSans", in.readAllBytes());
            } catch (Exception e) {
                BetterFontsMod.LOG.warn("Cannot load built-in Noto Sans", e);
            }
        }
        return builtinFace;
    }

    /** 描画に使うフォントの優先順: 選択中 → フォールバック → 内蔵 Noto Sans。設定が変わるまで同じ物を返す。 */
    public static synchronized FontStack activeStack() {
        var c = BetterFontsMod.config;
        String key = c.activeFamily + "|" + c.activeStyle + "|" + c.fallbackFamily + "|" + version;
        if (key.equals(stackKey)) return stackVal;
        List<FontFace> out = new ArrayList<>();
        if (!BUILTIN.equals(c.activeFamily) || isInstalled(BUILTIN)) {
            List<FontFace> l = loadStyle(false, c.activeFamily, c.activeStyle);
            if (l.isEmpty()) l = loadStyle(false, c.activeFamily, defaultStyle(c.activeFamily));
            out.addAll(l);
        }
        String fb = c.fallbackFamily;
        if (fb != null && !fb.isEmpty() && isInstalled(fb)) out.addAll(loadStyle(false, fb, defaultStyle(fb)));
        FontFace b = builtin();
        if (b != null) out.add(b);
        stackKey = key;
        stackVal = new FontStack(out);
        return stackVal;
    }

    /** インストール完了後に呼ぶ (メインスレッドで)。キャッシュを片付けて一覧を更新する。 */
    public static void finishInstall(String family) {
        deleteDir(familyDir(true, family));
        rescan();
    }

    /** DL済みフォントを削除 (キャッシュも)。使用中なら内蔵 Noto Sans に戻す。 */
    public static void delete(String family) {
        deleteDir(familyDir(false, family));
        deleteDir(familyDir(true, family));
        NameFonts.forget(family);
        rescan();
        var c = BetterFontsMod.config;
        if (family.equals(c.activeFamily)) { c.activeFamily = BUILTIN; c.activeStyle = "400"; c.save(); }
        if (family.equals(c.fallbackFamily)) { c.fallbackFamily = ""; c.save(); }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        } catch (Exception e) { BetterFontsMod.LOG.warn("Delete failed: {}", dir, e); }
    }
}
