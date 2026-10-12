package dev.betterfonts.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.betterfonts.BetterFontsMod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Fonts のカタログ取得とダウンロード。
 * カタログ: fonts.google.com/metadata/fonts (非公式だが API キー不要。失敗時はディスクキャッシュ)
 * 本体: fonts.googleapis.com/css に古い UA でアクセスすると TTF の URL が返る。
 */
public final class GoogleFontsApi {
    public record Family(String name, String category, List<String> styles, int popularity, List<String> subsets) {}

    /** UA が curl 系だと Google Fonts は WOFF/WOFF2 ではなく TTF を返す。 */
    private static final String LEGACY_UA = "curl/8.5.0";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile List<Family> catalog = List.of();
    private static volatile java.util.Map<String, Family> byName = java.util.Map.of();

    public static Family family(String name) { return byName.get(name); }

    public static List<Family> catalog() { return catalog; }

    public static CompletableFuture<Void> loadCatalog() {
        return CompletableFuture.runAsync(() -> {
            Path cache = FontLibrary.root().resolve("catalog.json");
            String json = null;
            try {
                json = get("https://fonts.google.com/metadata/fonts", null);
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, json);
            } catch (Exception e) {
                BetterFontsMod.LOG.warn("Catalog download failed, using cache: {}", e.toString());
                try { if (Files.exists(cache)) json = Files.readString(cache); } catch (IOException ignored) {}
            }
            if (json != null) {
                try {
                    List<Family> list = parse(json);
                    java.util.Map<String, Family> m = new java.util.HashMap<>();
                    for (Family fm : list) m.put(fm.name(), fm);
                    byName = m;
                    catalog = list;
                } catch (Exception e) { BetterFontsMod.LOG.warn("Catalog parse failed", e); }
            }
        });
    }

    private static List<Family> parse(String json) {
        int brace = json.indexOf('{');           // ")]}'" プレフィックス対策
        JsonObject root = JsonParser.parseString(json.substring(brace)).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("familyMetadataList");
        List<Family> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            List<String> styles = new ArrayList<>(o.getAsJsonObject("fonts").keySet());
            styles.sort(Comparator.comparingInt(GoogleFontsApi::weightOf).thenComparing(s -> s.endsWith("i")));
            int pop = o.has("popularity") ? o.get("popularity").getAsInt() : Integer.MAX_VALUE;
            List<String> subsets = new ArrayList<>();
            if (o.has("subsets")) for (JsonElement se : o.getAsJsonArray("subsets")) subsets.add(se.getAsString());
            out.add(new Family(o.get("family").getAsString(), o.get("category").getAsString(), styles, pop, subsets));
        }
        out.sort(Comparator.comparingInt(Family::popularity));
        return out;
    }

    private record Job(String key, int idx, int count, String url) {}

    /**
     * 指定スタイルを dir に保存する (同期・別スレッドで呼ぶこと)。
     * 日本語など大きなフォントは Google が文字範囲ごとに多数のファイルへ分割して返すので、
     * 分割された分は "<key>~<n>.ttf" として全部保存する (並列ダウンロード、完了後に一括で確定)。
     */
    private static java.util.Set<String> downloadCss(Family f, Path dir, java.util.Collection<String> keys, List<String> subsets,
                                                     java.util.function.Consumer<String> progress) throws Exception {
        Files.createDirectories(dir);
        // subset を指定しないと Google は日本語などを含まないラテン文字だけの小さなファイルを返す
        String url = "https://fonts.googleapis.com/css?family=" + f.name().replace(' ', '+') + ":" + String.join(",", keys)
                + (subsets.isEmpty() ? "" : "&subset=" + String.join(",", subsets));
        String css = get(url, LEGACY_UA);
        Matcher m = Pattern.compile("@font-face\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(css);
        Pattern style = Pattern.compile("font-style:\\s*(\\w+)");
        Pattern weight = Pattern.compile("font-weight:\\s*(\\d+)");
        Pattern src = Pattern.compile("url\\((https://[^)]+)\\)");
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        while (m.find()) {
            String block = m.group(1);
            Matcher st = style.matcher(block), w = weight.matcher(block), u = src.matcher(block);
            if (!st.find() || !w.find() || !u.find()) continue;
            String key = w.group(1) + (st.group(1).equals("italic") ? "i" : "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(u.group(1));
        }
        if (groups.isEmpty()) throw new IOException("No font files found for " + f.name());

        List<Job> jobs = new ArrayList<>();
        for (var e : groups.entrySet())
            for (int i = 0; i < e.getValue().size(); i++) jobs.add(new Job(e.getKey(), i, e.getValue().size(), e.getValue().get(i)));
        int total = jobs.size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(8, total));
        try {
            List<java.util.concurrent.Future<Path>> futs = new ArrayList<>();
            for (Job j : jobs) futs.add(pool.submit(() -> {
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(j.url()))
                        .header("User-Agent", LEGACY_UA).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + j.url());
                if (!FontLibrary.isSfnt(r.body())) throw new IOException("Downloaded file is not TTF/OTF (url: " + j.url() + ")");
                String name = j.count() == 1 ? j.key() + ".ttf" : j.key() + "~" + j.idx() + ".ttf";
                Path part = dir.resolve(name + ".part");
                Files.write(part, r.body());
                progress.accept("Downloading " + f.name() + " (" + done.incrementAndGet() + "/" + total + ")...");
                return part;
            }));
            List<Path> parts = new ArrayList<>();
            for (var fu : futs) parts.add(fu.get());
            for (String key : groups.keySet()) deleteStyleFiles(dir, key);   // 古い分割ファイルを消してから確定
            for (Path p : parts) {
                String fn = p.getFileName().toString();
                Files.move(p, dir.resolve(fn.substring(0, fn.length() - 5)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new java.util.LinkedHashSet<>(groups.keySet());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw c instanceof Exception ex ? ex : e;
        } finally {
            pool.shutdownNow();
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".part"))::iterator) Files.deleteIfExists(p);
            } catch (Exception ignored) {}
        }
    }

    private interface Strategy { java.util.Set<String> run() throws Exception; }

    /** 3 方式を順に試し、取得後に「そのスクリプトの字形が入っているか」を検証する。全部だめなら理由つきでエラー。 */
    public static void download(Family f, Path dir, java.util.Collection<String> keys,
                                java.util.function.Consumer<String> progress) throws Exception {
        List<String> subs = f.subsets().stream().filter(x -> !x.equals("menu")).toList();
        String[] names = {"list", "css", "zip"};
        Strategy[] strategies = {
                () -> downloadManifest(f, dir, keys, progress),
                () -> downloadCss(f, dir, keys, subs, progress),
                () -> downloadZip(f, dir, keys, progress)};
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < strategies.length; i++) {
            try {
                java.util.Set<String> saved = strategies[i].run();
                if (verify(f, dir, saved)) return;
                why.append(names[i]).append(": no glyphs for ").append(f.subsets()).append("; ");
            } catch (Exception e) {
                why.append(names[i]).append(": ").append(e.getMessage()).append("; ");
            }
            BetterFontsMod.LOG.warn("Font download strategy '{}' failed for {}: {}", names[i], f.name(), why);
        }
        for (String k : keys) deleteStyleFiles(dir, k);
        throw new IOException("Could not get a full font. " + why);
    }

    /** そのフォントが宣言しているスクリプト (日本語・キリル等) の代表文字を実際に持っているか。 */
    private static final java.util.Map<String, Integer> SAMPLE = java.util.Map.ofEntries(
            java.util.Map.entry("japanese", 0x3042), java.util.Map.entry("korean", 0xAC00),
            java.util.Map.entry("chinese-simplified", 0x4E2D), java.util.Map.entry("chinese-traditional", 0x4E2D),
            java.util.Map.entry("chinese-hongkong", 0x4E2D), java.util.Map.entry("cyrillic", 0x0414),
            java.util.Map.entry("greek", 0x0394), java.util.Map.entry("thai", 0x0E01),
            java.util.Map.entry("hebrew", 0x05D0), java.util.Map.entry("arabic", 0x0627),
            java.util.Map.entry("devanagari", 0x0905));

    private static boolean verify(Family f, Path dir, java.util.Set<String> keys) throws IOException {
        java.util.Set<Integer> need = new java.util.LinkedHashSet<>();
        for (String sub : f.subsets()) { Integer cp = SAMPLE.get(sub); if (cp != null) need.add(cp); }
        if (keys.isEmpty()) return false;
        if (need.isEmpty()) return true;
        for (String key : keys) {
            java.util.Set<Integer> have = new java.util.HashSet<>();
            try (var st = Files.list(dir)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    String n = p.getFileName().toString();
                    if (!(n.equals(key + ".ttf") || (n.startsWith(key + "~") && n.endsWith(".ttf")))) continue;
                    FontFace ff = FontFace.load("verify", Files.readAllBytes(p));
                    if (ff == null) continue;
                    for (int cp : need) if (ff.hasGlyph(cp)) have.add(cp);
                    ff.close();
                }
            }
            if (!have.containsAll(need)) return false;
        }
        return true;
    }

    /** ファイル名の一覧から、要求スタイルに対応する静的 TTF を選ぶ。 key → ファイル名 */
    private static java.util.Map<String, String> select(Family f, java.util.Collection<String> names,
                                                        java.util.Collection<String> keys) {
        String want = f.name().replaceAll("[\\s_]", "").toLowerCase();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String variable = null;
        for (String n : names) {
            String file = n.substring(n.lastIndexOf('/') + 1);
            if (!file.toLowerCase().endsWith(".ttf")) continue;
            if (file.contains("[")) { if (variable == null && !file.toLowerCase().contains("italic")) variable = n; continue; }
            int dash = file.lastIndexOf('-');
            if (dash < 0) continue;
            if (!file.substring(0, dash).replaceAll("[\\s_]", "").toLowerCase().equals(want)) continue;  // 幅違いの別ファミリーを除外
            String key = styleKeyFromFile(file.substring(dash + 1, file.length() - 4));
            if (key != null && keys.contains(key)) out.putIfAbsent(key, n);
        }
        if (out.isEmpty() && variable != null && keys.contains("400")) out.put("400", variable);   // 可変フォントのみ → 既定インスタンス
        return out;
    }

    private static byte[] fetchFont(String url) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", LEGACY_UA).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        if (!FontLibrary.isSfnt(r.body())) throw new IOException("not TTF/OTF");
        return r.body();
    }

    /** fonts.google.com/download/list (公式サイトの Get font が使うファイル一覧) → gstatic の静的 TTF を直接取得。 */
    private static java.util.Set<String> downloadManifest(Family f, Path dir, java.util.Collection<String> keys,
                                                          java.util.function.Consumer<String> progress) throws Exception {
        String q = java.net.URLEncoder.encode(f.name(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String json = get("https://fonts.google.com/download/list?family=" + q, "Mozilla/5.0");
        JsonObject root = JsonParser.parseString(json.substring(json.indexOf('{'))).getAsJsonObject();
        JsonArray refs = root.getAsJsonObject("manifest").getAsJsonArray("fileRefs");
        java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
        for (JsonElement el : refs) {
            JsonObject o = el.getAsJsonObject();
            urls.put(o.get("filename").getAsString(), o.get("url").getAsString());
        }
        java.util.Map<String, String> sel = select(f, urls.keySet(), keys);
        if (sel.isEmpty()) throw new IOException("no matching styles in list");

        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Map<String, java.util.concurrent.CompletableFuture<byte[]>> futs = new java.util.LinkedHashMap<>();
        for (var en : sel.entrySet()) {
            String url = urls.get(en.getValue());
            futs.put(en.getKey(), java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] b = fetchFont(url);
                    progress.accept("Downloading " + f.name() + " (" + done.incrementAndGet() + "/" + sel.size() + ")...");
                    return b;
                } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }));
        }
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try {
            for (var en : futs.entrySet()) out.put(en.getKey(), en.getValue().join());
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
        Files.createDirectories(dir);
        for (var en : out.entrySet()) {
            deleteStyleFiles(dir, en.getKey());
            Files.write(dir.resolve(en.getKey() + ".ttf"), en.getValue());
        }
        return new java.util.LinkedHashSet<>(out.keySet());
    }

    /** ZIP 配布から静的 TTF を取り出す (最後のフォールバック)。 */
    private static java.util.Set<String> downloadZip(Family f, Path dir, java.util.Collection<String> keys,
                                                     java.util.function.Consumer<String> progress) throws Exception {
        progress.accept("Downloading " + f.name() + " (zip)...");
        String q = java.net.URLEncoder.encode(f.name(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://fonts.google.com/download?family=" + q))
                .header("User-Agent", "Mozilla/5.0").build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] z = r.body();
        if (r.statusCode() != 200 || z.length < 4 || z[0] != 'P' || z[1] != 'K') throw new IOException("zip HTTP " + r.statusCode());
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(z))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".ttf")) continue;
                byte[] b = zin.readAllBytes();
                if (FontLibrary.isSfnt(b)) entries.put(e.getName(), b);
            }
        }
        java.util.Map<String, String> sel = select(f, entries.keySet(), keys);
        if (sel.isEmpty()) throw new IOException("no matching styles in zip");
        Files.createDirectories(dir);
        for (var en : sel.entrySet()) {
            deleteStyleFiles(dir, en.getKey());
            Files.write(dir.resolve(en.getKey() + ".ttf"), entries.get(en.getValue()));
        }
        return new java.util.LinkedHashSet<>(sel.keySet());
    }

    private static String styleKeyFromFile(String s) {
        boolean it = s.endsWith("Italic");
        String w = it ? s.substring(0, s.length() - 6) : s;
        if (w.isEmpty()) w = "Regular";
        int weight = switch (w) {
            case "Thin" -> 100; case "ExtraLight" -> 200; case "Light" -> 300; case "Regular" -> 400;
            case "Medium" -> 500; case "SemiBold" -> 600; case "Bold" -> 700; case "ExtraBold" -> 800;
            case "Black" -> 900; default -> -1;
        };
        return weight < 0 ? null : weight + (it ? "i" : "");
    }

    private static void deleteStyleFiles(Path dir, String key) throws IOException {
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.equals(key + ".ttf") || (n.startsWith(key + "~") && n.endsWith(".ttf"))) Files.deleteIfExists(p);
            }
        }
    }

    /** ファミリー名の文字だけを含む極小サブセット (&text=) を取得する。一覧でフォント名を自分のフォントで描くために使う。 */
    public static void downloadNameSubset(Family f, Path out) throws Exception {
        String key = f.styles().contains("400") ? "400" : f.styles().get(0);
        String text = java.net.URLEncoder.encode(f.name(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String css = get("https://fonts.googleapis.com/css?family=" + f.name().replace(' ', '+') + ":" + key + "&text=" + text, LEGACY_UA);
        Matcher u = Pattern.compile("url\\((https://[^)]+)\\)").matcher(css);
        if (!u.find()) throw new IOException("No font url for " + f.name());
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(u.group(1)))
                .header("User-Agent", LEGACY_UA).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || !FontLibrary.isSfnt(r.body())) throw new IOException("Bad subset for " + f.name());
        Files.createDirectories(out.getParent());
        Files.write(out, r.body());
    }

    private static String get(String url, String ua) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (ua != null) b.header("User-Agent", ua);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    public static int weightOf(String key) {
        return Integer.parseInt(key.replace("i", ""));
    }

    public static String styleLabel(String key) {
        String base = switch (weightOf(key)) {
            case 100 -> "Thin"; case 200 -> "ExtraLight"; case 300 -> "Light"; case 400 -> "Regular";
            case 500 -> "Medium"; case 600 -> "SemiBold"; case 700 -> "Bold"; case 800 -> "ExtraBold";
            case 900 -> "Black"; default -> key;
        };
        return key.endsWith("i") ? base + " Italic" : base;
    }
}
