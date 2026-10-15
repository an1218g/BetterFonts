package dev.betterfonts.ui;

import dev.betterfonts.BetterFontsMod;
import dev.betterfonts.Config;
import dev.betterfonts.font.FontFace;
import dev.betterfonts.font.FontLibrary;
import dev.betterfonts.font.FontStack;
import dev.betterfonts.font.GoogleFontsApi;
import dev.betterfonts.font.GoogleFontsApi.Family;
import dev.betterfonts.font.NameFonts;
import dev.betterfonts.font.TextRasterizer;
import dev.betterfonts.ui.ModButton.Style;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Browse / Library / Settings / Preview。
 * ウィンドウサイズは「実ピクセル」で指定 (GUI スケールに依存しない)。クリック処理は ModButton (Button 継承) に任せる。
 * [要確認] 26.3: Screen.extractRenderState / GuiGraphicsExtractor.fill・text・enableScissor / EditBox.setBordered・setTextColor
 */
public class BetterFontsScreen extends Screen {
    private enum Mode { BROWSE, LIBRARY, SETTINGS, PREVIEW }
    private record Row(String name, String sub, boolean downloaded, boolean inUse) {}
    private record Ctl(String label, boolean selected, int w, Runnable act) {}
    private record Item(String title, String desc, List<Ctl> ctls) {}
    private record Placed(Item item, int y, int h, List<String> lines, boolean stacked) {}
    private interface Job { void run() throws Exception; }

    private static final int HDR = 22, ROW_H = 34;

    private Mode mode = Mode.BROWSE, backMode = Mode.BROWSE;
    private String query = "", sample = "";
    private int page, styleIdx, settingsPage;
    private String previewName = "";
    private List<String> previewStyles = List.of();
    private volatile String status = "";
    private volatile boolean busy, closed;

    private EditBox searchBox, sampleBox;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private List<Row> pageRows = List.of();
    private int totalRows, rowsTop, perPage, settingsPages = 1;
    private List<Placed> placed = List.of();

    private float gs = 1;
    private int px, py, pw, ph, sbW, cx, cy, cw, ins, bottom;

    public BetterFontsScreen() { super(Component.literal("BetterFonts")); }

    @Override
    public void removed() {
        closed = true;
        Shapes.clear();
        Icons.clear();
    }

    private static int[] presetPx(String p) {
        return switch (p) {
            case "1600x900" -> new int[]{1600, 900};
            case "1280x720" -> new int[]{1280, 720};
            case "640x360" -> new int[]{640, 360};
            default -> new int[]{0, 0};          // AUTO
        };
    }

    // ================================================================ layout
    @Override
    protected void init() {
        Config c = BetterFontsMod.config;
        gs = Ui.gs();
        int[] pr = presetPx(c.windowPreset);
        pw = pr[0] <= 0 ? Math.round(width * 0.9f) : Math.round(pr[0] / gs);
        ph = pr[1] <= 0 ? Math.round(height * 0.9f) : Math.round(pr[1] / gs);
        pw = Math.min(pw, width - 8);
        ph = Math.min(ph, height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        ins = (int) Math.ceil(c.borderPx / gs);
        sbW = Math.max(64, Math.min(96, pw / 4));
        cx = px + ins + sbW + 8;
        cy = py + ins + HDR + 8;
        cw = px + pw - ins - 8 - cx;
        bottom = py + ph - ins - 6;
        rowWidgets.clear();
        pageRows = List.of();
        placed = List.of();

        nav("Browse", "search", 0, mode == Mode.BROWSE || (mode == Mode.PREVIEW && backMode == Mode.BROWSE), Mode.BROWSE);
        nav("Library", "format_list_bulleted", 1, mode == Mode.LIBRARY || (mode == Mode.PREVIEW && backMode == Mode.LIBRARY), Mode.LIBRARY);
        nav("Settings", "settings", 2, mode == Mode.SETTINGS, Mode.SETTINGS);

        switch (mode) {
            case BROWSE, LIBRARY -> initList();
            case SETTINGS -> initSettings();
            case PREVIEW -> initPreview();
        }
    }

    private void nav(String label, String icon, int i, boolean selected, Mode target) {
        addRenderableWidget(new ModButton(px + ins + 4, cy + i * 24, sbW - 8, 20, label, icon,
                selected ? Style.NAV_SELECTED : Style.NAV, () -> { mode = target; page = 0; query = ""; rebuildWidgets(); }));
    }

    private EditBox editBox(int x, int y, int w, String hint, String value, java.util.function.Consumer<String> onChange) {
        ModEditBox b = new ModEditBox(font, x, y, w, 14, hint);
        b.setValue(value);
        b.setResponder(onChange);
        return addRenderableWidget(b);
    }

    // ================================================================ list (Browse / Library)
    private void initList() {
        boolean hasIcon = Icons.has("search");
        searchBox = editBox(cx + ins + (hasIcon ? 20 : 6), cy + 26, cw - ins * 2 - (hasIcon ? 26 : 12), "Search...", query,
                s -> { query = s; page = 0; refreshRows(); });

        rowsTop = cy + 46;
        perPage = Math.max(1, (bottom - 22 - rowsTop) / ROW_H);
        addRenderableWidget(new ModButton(cx, bottom - 18, 22, 18, "<", Style.NORMAL, () -> { if (page > 0) { page--; refreshRows(); } }));
        addRenderableWidget(new ModButton(cx + 26, bottom - 18, 22, 18, ">", Style.NORMAL, () -> { page++; refreshRows(); }));
        refreshRows();
    }

    private List<Row> allRows() {
        String q = query.toLowerCase();
        String active = BetterFontsMod.config.activeFamily;
        List<Row> out = new ArrayList<>();
        if (mode == Mode.BROWSE) {
            for (Family f : GoogleFontsApi.catalog()) {
                if (!q.isEmpty() && !f.name().toLowerCase().contains(q)) continue;
                out.add(new Row(f.name(), f.category() + "  ·  " + f.styles().size() + " Styles",
                        FontLibrary.isInstalled(f.name()), f.name().equals(active)));
            }
        } else {
            for (String n : FontLibrary.installedFamilies()) {
                if (!q.isEmpty() && !n.toLowerCase().contains(q)) continue;
                int styles = Math.max(1, FontLibrary.styles(false, n).size());
                out.add(new Row(n, styles + " Styles", true, n.equals(active)));
            }
        }
        return out;
    }

    private void refreshRows() {
        rowWidgets.forEach(this::removeWidget);
        rowWidgets.clear();
        List<Row> all = allRows();
        totalRows = all.size();
        int maxPage = Math.max(0, (all.size() - 1) / perPage);
        page = Math.min(page, maxPage);
        pageRows = all.subList(Math.min(all.size(), page * perPage), Math.min(all.size(), (page + 1) * perPage));

        int right = cx + cw - ins - 4;
        for (int i = 0; i < pageRows.size(); i++) {
            Row r = pageRows.get(i);
            int y = rowsTop + i * ROW_H + 6;
            int x = right - 50;
            rowWidgets.add(addRenderableWidget(new ModButton(x, y, 50, 18, "Preview", Style.NORMAL, () -> openPreview(r.name()))));
            if (mode == Mode.LIBRARY && !r.inUse()) {
                x -= 38;
                rowWidgets.add(addRenderableWidget(new ModButton(x, y, 34, 18, "Use", Style.NORMAL, () -> apply(r.name(), FontLibrary.defaultStyle(r.name())))));
            }
            if (mode == Mode.LIBRARY && !r.name().equals(FontLibrary.BUILTIN)) {
                x -= 22;
                rowWidgets.add(addRenderableWidget(new ModButton(x, y, 18, 18, "X", Style.NORMAL, () -> {
                    FontLibrary.delete(r.name()); BetterFontsMod.refreshFonts(); refreshRows();
                })));
            }
        }
    }

    // ================================================================ preview
    private void openPreview(String family) {
        backMode = mode;
        previewName = family;
        if (FontLibrary.isInstalled(family)) previewStyles = FontLibrary.styles(false, family);
        else previewStyles = GoogleFontsApi.catalog().stream().filter(f -> f.name().equals(family))
                .findFirst().map(Family::styles).orElse(List.of("400"));
        styleIdx = Math.max(0, previewStyles.indexOf("400"));
        mode = Mode.PREVIEW;
        rebuildWidgets();
    }

    private final java.util.Set<String> failed = new java.util.HashSet<>();

    /** 表示中のスタイルだけをキャッシュへダウンロードする (日本語フォントは分割ファイルが多く重いので全スタイルは取らない)。 */
    private void ensureStyle() {
        if (FontLibrary.isInstalled(previewName) || busy) return;
        if (previewName.equals(FontLibrary.BUILTIN) && FontLibrary.builtin() != null) return;
        String key = styleKey();
        String fk = previewName + "|" + key;
        if (failed.contains(fk) || FontLibrary.styles(true, previewName).contains(key)) return;
        Family fam = GoogleFontsApi.family(previewName);
        if (fam == null) { status = GoogleFontsApi.catalog().isEmpty() ? "Loading catalog..." : "Not found in catalog"; return; }
        String name = previewName;
        runAsync("Downloading " + name + "...", () -> {
            try {
                GoogleFontsApi.download(fam, FontLibrary.familyDir(true, name), List.of(key), msg -> status = msg);
            } catch (Exception e) { failed.add(fk); throw e; }
        });
    }

    private String styleKey() { return previewStyles.isEmpty() ? "400" : previewStyles.get(styleIdx); }

    private List<FontFace> previewFaces() {
        if (FontLibrary.isInstalled(previewName)) return FontLibrary.loadStyle(false, previewName, styleKey());
        List<FontFace> l = FontLibrary.loadStyle(true, previewName, styleKey());
        if (l.isEmpty() && previewName.equals(FontLibrary.BUILTIN) && FontLibrary.builtin() != null) return List.of(FontLibrary.builtin());
        return l;
    }

    private void initPreview() {
        ensureStyle();
        addRenderableWidget(new ModButton(cx + cw - 44, cy, 44, 16, "Back", Style.NORMAL, () -> { mode = backMode; page = 0; rebuildWidgets(); }));
        boolean hasIcon = Icons.has("edit");
        sampleBox = editBox(cx + ins + (hasIcon ? 20 : 6), cy + 26, cw - ins * 2 - (hasIcon ? 26 : 12),
                "Please try entering something...", sample, s -> sample = s);

        int by = bottom - 20;
        addRenderableWidget(new ModButton(cx, by, 20, 20, "<", Style.NORMAL, () -> {
            if (!previewStyles.isEmpty()) { styleIdx = (styleIdx + previewStyles.size() - 1) % previewStyles.size(); rebuildWidgets(); }
        }));
        addRenderableWidget(new ModButton(cx + 110, by, 20, 20, ">", Style.NORMAL, () -> {
            if (!previewStyles.isEmpty()) { styleIdx = (styleIdx + 1) % previewStyles.size(); rebuildWidgets(); }
        }));

        boolean current = previewName.equals(BetterFontsMod.config.activeFamily)
                && styleKey().equals(BetterFontsMod.config.activeStyle);
        boolean installed = FontLibrary.isInstalled(previewName) || previewName.equals(FontLibrary.BUILTIN);
        String label; Runnable act; Style st = Style.PRIMARY; boolean enabled = true;
        if (current) { label = "Current"; act = () -> {}; enabled = false; }
        else if (installed) { label = "Use"; act = () -> apply(previewName, styleKey()); }
        else if (!busy && GoogleFontsApi.family(previewName) != null) {
            label = "Install"; st = Style.NORMAL;
            Family fam = GoogleFontsApi.family(previewName);
            String name = previewName;
            act = () -> runAsync("Installing...", () -> {
                GoogleFontsApi.download(fam, FontLibrary.familyDir(false, name), fam.styles(), msg -> status = msg);
                Minecraft.getInstance().execute(() -> FontLibrary.finishInstall(name));
            });
        }
        else { label = busy ? "Downloading..." : "Unavailable"; act = () -> {}; enabled = false; st = Style.NORMAL; }
        ModButton b = new ModButton(cx + cw - 120, by, 120, 20, label, st, act);
        b.active = enabled;
        addRenderableWidget(b);
    }

    private void apply(String family, String style) {
        BetterFontsMod.config.activeFamily = family;
        BetterFontsMod.config.activeStyle = style;
        BetterFontsMod.config.save();
        BetterFontsMod.refreshFonts();
        rebuildWidgets();
    }

    // ================================================================ settings
    private Ctl ctl(String label, boolean selected, Runnable act) {
        return new Ctl(selected ? "[" + label + "]" : label, selected, Math.max(40, (int) Ui.width(font, label) + 18), act);
    }

    private Item pick(String title, String desc, String[] labels, int selected, java.util.function.IntConsumer onPick) {
        List<Ctl> cs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            cs.add(ctl(labels[i], i == selected, () -> {
                onPick.accept(idx);
                BetterFontsMod.config.save();
                BetterFontsMod.refreshFonts();
                rebuildWidgets();
            }));
        }
        return new Item(title, desc, cs);
    }

    private static int nearest(float[] vals, float cur) {
        int b = 0;
        for (int i = 1; i < vals.length; i++) if (Math.abs(vals[i] - cur) < Math.abs(vals[b] - cur)) b = i;
        return b;
    }

    private static int nearestInt(int[] vals, int cur) {
        int b = 0;
        for (int i = 1; i < vals.length; i++) if (Math.abs(vals[i] - cur) < Math.abs(vals[b] - cur)) b = i;
        return b;
    }

    private List<Item> items() {
        Config c = BetterFontsMod.config;
        List<Item> l = new ArrayList<>();
        l.add(new Item("Enable Mod", "is use this mod..?", List.of(
                new Ctl(c.enabled ? "ON" : "OFF", c.enabled, 60, () -> { c.enabled = !c.enabled; c.save(); BetterFontsMod.refreshFonts(); rebuildWidgets(); }))));
        l.add(new Item("Rendering Method", "Vulkan is experimental feature. | Restart required!", List.of(
                ctl("OpenGL", "OPENGL".equals(c.renderer), () -> { c.renderer = "OPENGL"; c.save(); rebuildWidgets(); }),
                ctl("Vulkan", "VULKAN".equals(c.renderer), () -> { c.renderer = "VULKAN"; c.save(); rebuildWidgets(); }))));
        List<Ctl> sm = new ArrayList<>();
        for (String[] s : new String[][]{{"Low", "LOW"}, {"Normal", "NORMAL"}, {"High", "HIGH"}})
            sm.add(ctl(s[0], s[1].equals(c.smoothness), () -> { c.smoothness = s[1]; c.save(); BetterFontsMod.refreshFonts(); rebuildWidgets(); }));
        l.add(new Item("Smoothness", "Higher smoothness increases the load.", sm));

        final int[] sizes = {80, 90, 100, 110, 125, 150};
        l.add(pick("Font Size", "Text size. 100% matches vanilla.", new String[]{"80%", "90%", "100%", "110%", "125%", "150%"},
                nearestInt(sizes, c.fontSizePercent), i -> c.fontSizePercent = sizes[i]));
        l.add(pick("Texture Filter", "Smooth blends pixels when text is scaled (works well with High smoothness). Sharp keeps hard pixel edges.",
                new String[]{"Sharp", "Smooth"}, c.smoothFilter ? 1 : 0, i -> c.smoothFilter = i == 1));
        final float[] ys = {-1f, -0.5f, 0f, 0.5f, 1f};
        l.add(pick("Vertical Offset", "Moves text up (-) or down (+).", new String[]{"-1", "-0.5", "0", "+0.5", "+1"},
                nearest(ys, c.yOffset), i -> c.yOffset = ys[i]));
        final float[] sp = {-0.5f, 0f, 0.5f, 1f};
        l.add(pick("Letter Spacing", "Extra space between characters.", new String[]{"-0.5", "0", "+0.5", "+1"},
                nearest(sp, c.letterSpacing), i -> c.letterSpacing = sp[i]));
        l.add(pick("Shadow", "Drop shadow behind text.", new String[]{"Off", "On"},
                c.shadowEnabled ? 1 : 0, i -> c.shadowEnabled = i == 1));
        final float[] so = {0.5f, 1f, 1.5f, 2f};
        l.add(pick("Shadow Offset", "Distance of the shadow in GUI pixels.", new String[]{"0.5", "1", "1.5", "2"},
                nearest(so, c.shadowOffset), i -> c.shadowOffset = so[i]));
        final int[] op = {25, 50, 75, 100};
        l.add(pick("Shadow Opacity", "How dark the shadow is.", new String[]{"25%", "50%", "75%", "100%"},
                nearestInt(op, c.shadowOpacity), i -> c.shadowOpacity = op[i]));

        List<Ctl> ws = new ArrayList<>();
        for (String[] s : new String[][]{{"1600x900", "1600x900"}, {"1280x720", "1280x720"}, {"640x360", "640x360"}, {"Auto", "AUTO"}})
            ws.add(ctl(s[0], s[1].equals(c.windowPreset), () -> { c.windowPreset = s[1]; c.save(); rebuildWidgets(); }));
        l.add(new Item("Window Size", "Size of this window in real pixels. Auto fits the screen.", ws));

        List<Ctl> cr = new ArrayList<>();
        for (Object[] s : new Object[][]{{"Off", 0}, {"Small", 12}, {"Medium", 24}, {"Large", 40}}) {
            int v = (Integer) s[1];
            cr.add(ctl((String) s[0], c.cornerRadius == v, () -> { c.cornerRadius = v; c.save(); rebuildWidgets(); }));
        }
        l.add(new Item("Corner Radius", "Roundness of the window and blocks.", cr));

        List<String> opts = new ArrayList<>();
        opts.add("");
        for (String n : FontLibrary.installedFamilies()) if (!n.equals(FontLibrary.BUILTIN)) opts.add(n);
        String cur = c.fallbackFamily == null ? "" : c.fallbackFamily;
        int idx = Math.max(0, opts.indexOf(cur));
        String label = "Fallback: " + (cur.isEmpty() ? "None" : cur);
        l.add(new Item("Fallback Font", "Fills in characters the selected font lacks (e.g. Japanese). Pick any installed font.", List.of(
                new Ctl(label, false, Math.min(150, Math.max(90, (int) Ui.width(font, label) + 18)), () -> {
                    c.fallbackFamily = opts.get((idx + 1) % opts.size()); c.save(); BetterFontsMod.refreshFonts(); rebuildWidgets();
                }))));
        l.add(new Item("Diagnostics", "Provider injected: " + BetterFontsMod.providerActive
                + " | FontManager: " + (BetterFontsMod.fontManager != null)
                + " | Supported glyphs: " + dev.betterfonts.font.BetterGlyphProvider.supportedCount
                + " | Faces: " + dev.betterfonts.font.BetterGlyphProvider.facesCount
                + " | Queried: " + dev.betterfonts.font.BetterGlyphProvider.queriedCount
                + " | Baked: " + dev.betterfonts.font.BetterGlyphProvider.bakedCount
                + " | Linear hook: " + BetterFontsMod.linearHook, List.of()));
        return l;
    }

    private void initSettings() {
        List<Item> all = items();
        int avail = bottom - 22 - (cy + 14);
        List<List<Placed>> pages = new ArrayList<>();
        List<Placed> curPage = new ArrayList<>();
        int used = 0;
        for (Item it : all) {
            int ctlW = it.ctls().stream().mapToInt(c -> c.w() + 4).sum();
            float descW = cw - ins * 2 - 12 - ctlW - 8;
            boolean stacked = descW < 90;
            if (stacked) descW = cw - ins * 2 - 12;
            List<String> lines = Ui.wrap(font, it.desc(), descW);
            int h = Math.max(30, 8 + 11 + lines.size() * 10 + (stacked ? 22 : 0) + 4);
            if (!curPage.isEmpty() && used + h > avail) { pages.add(curPage); curPage = new ArrayList<>(); used = 0; }
            curPage.add(new Placed(it, 0, h, lines, stacked));
            used += h + 4;
        }
        if (!curPage.isEmpty()) pages.add(curPage);
        settingsPages = Math.max(1, pages.size());
        settingsPage = Math.min(settingsPage, settingsPages - 1);

        List<Placed> out = new ArrayList<>();
        int y = cy + 14;
        for (Placed p : pages.get(settingsPage)) {
            Placed q = new Placed(p.item(), y, p.h(), p.lines(), p.stacked());
            out.add(q);
            int right = cx + cw - ins - 6;
            int ctlY = q.stacked() ? y + q.h() - 24 : y + (q.h() - 18) / 2;
            int x = q.stacked() ? cx + ins + 6 : right - q.item().ctls().stream().mapToInt(c -> c.w() + 4).sum() + 4;
            for (Ctl c : q.item().ctls()) {
                addRenderableWidget(new ModButton(x, ctlY, c.w(), 18, c.label(), c.selected() ? Style.SELECTED : Style.NORMAL, c.act()));
                x += c.w() + 4;
            }
            y += q.h() + 4;
        }
        placed = out;

        if (settingsPages > 1) {
            addRenderableWidget(new ModButton(cx, bottom - 18, 22, 18, "<", Style.NORMAL, () -> { if (settingsPage > 0) { settingsPage--; rebuildWidgets(); } }));
            addRenderableWidget(new ModButton(cx + 26, bottom - 18, 22, 18, ">", Style.NORMAL, () -> { if (settingsPage < settingsPages - 1) { settingsPage++; rebuildWidgets(); } }));
        }
    }

    // ================================================================ async helper
    private void runAsync(String msg, Job job) {
        status = msg;
        busy = true;
        CompletableFuture.runAsync(() -> {
            try { job.run(); status = ""; }
            catch (Exception e) { status = "Error: " + e.getMessage(); BetterFontsMod.LOG.warn("Job failed", e); }
            busy = false;
            Minecraft.getInstance().execute(() -> { if (!closed) rebuildWidgets(); });
        });
    }

    // ================================================================ render
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Config c = BetterFontsMod.config;
        float R = c.cornerRadius, ri = Math.max(0, R - c.borderPx);
        // ウィンドウ本体・ヘッダ・サイドバー (角丸は実ピクセル解像度)
        Shapes.round(g, px, py, pw, ph, R, R, R, R, c.borderPx, Ui.BG, 0xFFE0E0D6);
        Shapes.round(g, px + ins, py + ins, pw - 2 * ins, HDR, ri, ri, 0, 0, 0, Ui.WHITE, 0);
        Shapes.round(g, px + ins, py + ins + HDR, sbW, ph - 2 * ins - HDR, 0, 0, 0, ri, 0, Ui.WHITE, 0);
        g.fill(px + ins, py + ins + HDR, px + pw - ins, py + ins + HDR + 1, 0xFF000000);
        g.fill(px + ins + sbW, py + ins + HDR + 1, px + ins + sbW + 1, py + ph - ins, 0xFF000000);
        g.text(font, "BetterFonts", px + ins + 10, py + ins + 7, Ui.DARK, false);

        // 左下 "In use" カード
        int cardY = bottom - 30, cardX = px + ins + 4, cardW = sbW - 8;
        float rc = c.cornerRadius / 2f;
        Shapes.round(g, cardX, cardY, cardW, 30, rc, rc, rc, rc, 0, Ui.BG, 0);
        float rp = 6 * gs;
        Shapes.round(g, cardX + 4, cardY + 5, 20, 20, rp, rp, rp, rp, 0, Ui.PILL, 0);
        g.text(font, "Aa", cardX + 8, cardY + 11, Ui.GREEN, false);
        g.text(font, font.plainSubstrByWidth(c.activeFamily, cardW - 34), cardX + 28, cardY + 6, Ui.DARK, false);
        g.text(font, "In use", cardX + 28, cardY + 17, Ui.GREEN, false);

        // 入力欄の枠 (EditBox は枠なしで上に重ねる)
        if (mode == Mode.BROWSE || mode == Mode.LIBRARY) inputCard(g, cy + 24, "search");
        if (mode == Mode.PREVIEW) inputCard(g, cy + 24, "edit");

        switch (mode) {
            case BROWSE, LIBRARY -> renderList(g);
            case SETTINGS -> renderSettings(g);
            case PREVIEW -> renderPreview(g);
        }
        super.extractRenderState(g, mx, my, dt);
    }

    private void inputCard(GuiGraphicsExtractor g, int y, String icon) {
        Ui.card(g, cx, y, cw, 18);
        Icons.draw(g, icon, cx + ins + 3, y + 3, 12, Ui.DARK);
    }

    private void renderList(GuiGraphicsExtractor g) {
        boolean browse = mode == Mode.BROWSE;
        g.text(font, browse ? "Browse" : "Library", cx, cy, Ui.DARK, false);
        String sub = browse
                ? (GoogleFontsApi.catalog().isEmpty() ? "Loading catalog..." : GoogleFontsApi.catalog().size() + " families from Google Fonts")
                : FontLibrary.installedFamilies().size() + " families Downloaded - " + totalRows + " Search results";
        g.text(font, sub, cx, cy + 11, Ui.GRAY, false);
        int right = cx + cw - ins - 4;
        for (int i = 0; i < pageRows.size(); i++) {
            Row r = pageRows.get(i);
            int y = rowsTop + i * ROW_H;
            Ui.card(g, cx, y, cw, ROW_H - 4);
            int tx = cx + ins + 8;
            List<FontFace> nf = NameFonts.get(r.name());          // そのフォント自身の Regular で名前を描く
            if (!nf.isEmpty()) TextRasterizer.draw(g, nf, r.name(), tx, y + ins + 3, Ui.DARK, false, 11f);
            else g.text(font, r.name(), tx, y + ins + 3, Ui.DARK, false);   // 取得中は現在のフォントで代替
            g.text(font, r.sub(), tx, y + ins + 17, Ui.GRAY, false);
            int left = right - 50 - (mode == Mode.LIBRARY ? (r.inUse() ? 0 : 38) + (r.name().equals(FontLibrary.BUILTIN) ? 0 : 22) : 0);
            if (r.inUse()) Ui.pill(g, font, "In use", left - 6, y + 9);
            else if (browse && r.downloaded()) Ui.pill(g, font, "Downloaded", left - 6, y + 9);
        }
        g.text(font, "Page " + (page + 1), cx + 56, bottom - 13, Ui.GRAY, false);
        if (!status.isEmpty()) g.text(font, status, cx + 110, bottom - 13, Ui.GRAY, false);
    }

    private void renderSettings(GuiGraphicsExtractor g) {
        g.text(font, "Settings", cx, cy, Ui.DARK, false);
        for (Placed p : placed) {
            Ui.card(g, cx, p.y(), cw, p.h());
            g.text(font, p.item().title(), cx + ins + 6, p.y() + 5, Ui.DARK, false);
            for (int i = 0; i < p.lines().size(); i++)
                g.text(font, p.lines().get(i), cx + ins + 6, p.y() + 16 + i * 10, Ui.GRAY, false);
        }
        if (settingsPages > 1)
            g.text(font, "Page " + (settingsPage + 1) + " / " + settingsPages, cx + 56, bottom - 13, Ui.GRAY, false);
    }

    private void renderPreview(GuiGraphicsExtractor g) {
        g.text(font, "Browse - Preview", cx, cy, Ui.DARK, false);
        g.text(font, "Preview: " + previewName, cx, cy + 11, Ui.GRAY, false);

        int ax1 = cx, ay1 = cy + 46, ax2 = cx + cw, ay2 = bottom - 26;
        Ui.card(g, ax1, ay1, ax2 - ax1, ay2 - ay1);
        int m = ins + 4;
        List<FontFace> faces = previewFaces();
        if (faces.isEmpty()) {
            g.text(font, status.isEmpty() ? "Font not available" : status, ax1 + m, ay1 + m, Ui.GRAY, false);
        } else {
            FontStack stack = new FontStack(faces);
            g.enableScissor(ax1 + ins, ay1 + ins, ax2 - ins, ay2 - ins);
            String[] texts; float[] ems;
            if (sample.isBlank()) {
                texts = new String[]{"Whereas recognition of the inherent dignity",
                        "No one shall be subjected to arbitrary arrest, detention or exile.",
                        "Everyone is entitled to a fair and public hearing by an impartial tribunal.",
                        "Everyone has the right to freedom of thought, conscience and religion."};
                ems = new float[]{13, 9, 9, 6};
            } else {
                texts = new String[]{sample, sample, sample};
                ems = new float[]{16, 10, 7};
            }
            float y = ay1 + m;
            for (int i = 0; i < texts.length; i++) {
                TextRasterizer.draw(g, stack, texts[i], ax1 + m, y, Ui.DARK, false, ems[i]);
                y += ems[i] * 1.5f;
            }
            g.disableScissor();
        }
        String label = previewStyles.isEmpty() ? "Regular" : GoogleFontsApi.styleLabel(styleKey());
        g.text(font, label, (int) (cx + 65 - Ui.width(font, label) / 2), bottom - 14, Ui.DARK, false);
        if (!status.isEmpty() && !faces.isEmpty()) g.text(font, status, cx + 138, bottom - 14, Ui.GRAY, false);
    }
}
