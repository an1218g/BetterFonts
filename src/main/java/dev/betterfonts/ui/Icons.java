package dev.betterfonts.ui;

import dev.betterfonts.font.TextRasterizer;
import dev.betterfonts.font.TextRasterizer.Tex;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Material Symbols の SVG (単色 <path d="...">) を実行時に任意サイズでラスタライズして描画する。
 * Minecraft は SVG を扱えないので、パス解析 + スキャンライン塗りつぶしを自前で持つ。
 * 色は描画時のティントで決まる。
 */
public final class Icons {
    private static final Map<String, String> SVG = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();
    private static final Map<String, Tex> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Tex> e) {
            if (size() > 64) { TextRasterizer.release(e.getValue().id()); return true; }
            return false;
        }
    };

    public static void clear() {
        CACHE.values().forEach(t -> TextRasterizer.release(t.id()));
        CACHE.clear();
    }

    private static String svg(String name) {
        if (MISSING.contains(name)) return null;
        String s = SVG.get(name);
        if (s != null) return s;
        try (InputStream in = Icons.class.getResourceAsStream("/assets/betterfonts/icons/" + name + ".svg")) {
            if (in == null) { MISSING.add(name); return null; }
            s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SVG.put(name, s);
            return s;
        } catch (Exception e) { MISSING.add(name); return null; }
    }

    public static boolean has(String name) { return name != null && svg(name) != null; }

    /** sizeGui 四方で描く。アイコンが無ければ何もしない。 */
    public static void draw(GuiGraphicsExtractor g, String name, float x, float y, float sizeGui, int color) {
        String svg = svg(name);
        if (svg == null) return;
        float gs = Ui.gs();
        int px = Math.max(8, Math.round(sizeGui * gs));
        String key = name + "@" + px;
        Tex t = CACHE.get(key);
        if (t == null) {
            byte[] a = rasterize(svg, px);
            if (a == null) { MISSING.add(name); return; }
            t = new Tex(TextRasterizer.uploadAlpha(a, px, px), px, px, 0, 0, gs);
            CACHE.put(key, t);
        }
        TextRasterizer.blit(g, t, x, y, 1f / gs, color);
    }

    // ------------------------------------------------------------------ SVG → alpha
    private static final Pattern VIEWBOX = Pattern.compile("viewBox\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATH = Pattern.compile("<path[^>]*?\\sd\\s*=\\s*\"([^\"]+)\"");

    static byte[] rasterize(String svg, int size) {
        float minX = 0, minY = 0, vw = 24, vh = 24;
        Matcher vm = VIEWBOX.matcher(svg);
        if (vm.find()) {
            String[] v = vm.group(1).trim().split("[\\s,]+");
            if (v.length == 4) { minX = Float.parseFloat(v[0]); minY = Float.parseFloat(v[1]); vw = Float.parseFloat(v[2]); vh = Float.parseFloat(v[3]); }
        }
        float scale = size / Math.max(vw, vh);
        float ox = (size - vw * scale) / 2f, oy = (size - vh * scale) / 2f;

        List<float[]> edges = new ArrayList<>();
        Matcher pm = PATH.matcher(svg);
        boolean any = false;
        while (pm.find()) {
            any = true;
            for (List<Float> poly : parsePath(pm.group(1))) {
                int n = poly.size() / 2;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    edges.add(new float[]{
                            (poly.get(i * 2) - minX) * scale + ox, (poly.get(i * 2 + 1) - minY) * scale + oy,
                            (poly.get(j * 2) - minX) * scale + ox, (poly.get(j * 2 + 1) - minY) * scale + oy});
                }
            }
        }
        if (!any) return null;
        return fill(edges, size);
    }

    /** 非ゼロ巻き数規則・縦4サブスキャンのアンチエイリアス塗りつぶし。 */
    private static byte[] fill(List<float[]> edges, int size) {
        final int SS = 4;
        float[] cov = new float[size];
        byte[] out = new byte[size * size];
        for (int y = 0; y < size; y++) {
            Arrays.fill(cov, 0);
            for (int s = 0; s < SS; s++) {
                float yy = y + (s + 0.5f) / SS;
                List<float[]> xs = new ArrayList<>();
                for (float[] e : edges) {
                    float y0 = e[1], y1 = e[3];
                    if (y0 == y1) continue;
                    if (yy < Math.min(y0, y1) || yy >= Math.max(y0, y1)) continue;
                    xs.add(new float[]{e[0] + (yy - y0) * (e[2] - e[0]) / (y1 - y0), y1 > y0 ? 1 : -1});
                }
                xs.sort((a, b) -> Float.compare(a[0], b[0]));
                int wind = 0;
                for (int i = 0; i < xs.size() - 1; i++) {
                    wind += (int) xs.get(i)[1];
                    if (wind == 0) continue;
                    span(cov, xs.get(i)[0], xs.get(i + 1)[0], size, 1f / SS);
                }
            }
            for (int x = 0; x < size; x++) out[y * size + x] = (byte) Math.round(Math.min(1f, cov[x]) * 255);
        }
        return out;
    }

    private static void span(float[] cov, float xa, float xb, int size, float weight) {
        xa = Math.max(0, xa); xb = Math.min(size, xb);
        if (xb <= xa) return;
        int ia = (int) xa, ib = (int) Math.min(size - 1, xb);
        if (ia == ib) { cov[ia] += (xb - xa) * weight; return; }
        cov[ia] += (ia + 1 - xa) * weight;
        for (int i = ia + 1; i < ib; i++) cov[i] += weight;
        cov[ib] += (xb - ib) * weight;
    }

    // ------------------------------------------------------------------ path parser
    private static final class Sc {
        final String s; int i;
        Sc(String s) { this.s = s; }
        void ws() { while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++; }
        boolean more() { ws(); return i < s.length(); }
        boolean isNum() {
            ws(); if (i >= s.length()) return false;
            char c = s.charAt(i); return c == '-' || c == '+' || c == '.' || Character.isDigit(c);
        }
        float num() {
            ws(); int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') { i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            return Float.parseFloat(s.substring(st, i));
        }
        int flag() { ws(); return s.charAt(i++) == '1' ? 1 : 0; }
    }

    private static List<List<Float>> parsePath(String d) {
        List<List<Float>> polys = new ArrayList<>();
        List<Float> cur = null;
        Sc sc = new Sc(d);
        float cx = 0, cy = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 0, prev = 0;
        while (sc.more()) {
            char c = sc.s.charAt(sc.i);
            if (Character.isLetter(c)) { cmd = c; sc.i++; if (cmd == 'Z' || cmd == 'z') {
                if (cur != null) { polys.add(cur); cur = null; }
                cx = sx; cy = sy; prev = 'Z'; continue; } }
            else if (cmd == 0 || cmd == 'Z' || cmd == 'z') break;
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (up) {
                case 'M' -> {
                    float x = sc.num() + ox, y = sc.num() + oy;
                    if (cur != null) polys.add(cur);
                    cur = new ArrayList<>(); cur.add(x); cur.add(y);
                    cx = sx = x; cy = sy = y; cmd = rel ? 'l' : 'L';
                }
                case 'L' -> { float x = sc.num() + ox, y = sc.num() + oy; cur = pt(cur, cx, cy, x, y); cx = x; cy = y; }
                case 'H' -> { float x = sc.num() + ox; cur = pt(cur, cx, cy, x, cy); cx = x; }
                case 'V' -> { float y = sc.num() + oy; cur = pt(cur, cx, cy, cx, y); cy = y; }
                case 'C', 'S' -> {
                    float x1, y1;
                    if (up == 'C') { x1 = sc.num() + ox; y1 = sc.num() + oy; }
                    else if (prev == 'C') { x1 = 2 * cx - lcx; y1 = 2 * cy - lcy; }
                    else { x1 = cx; y1 = cy; }
                    float x2 = sc.num() + ox, y2 = sc.num() + oy, x = sc.num() + ox, y = sc.num() + oy;
                    cur = begin(cur, cx, cy);
                    for (int k = 1; k <= 14; k++) {
                        float t = k / 14f, u = 1 - t;
                        cur.add(u * u * u * cx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x);
                        cur.add(u * u * u * cy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y);
                    }
                    lcx = x2; lcy = y2; cx = x; cy = y;
                }
                case 'Q', 'T' -> {
                    float x1, y1;
                    if (up == 'Q') { x1 = sc.num() + ox; y1 = sc.num() + oy; }
                    else if (prev == 'Q') { x1 = 2 * cx - lcx; y1 = 2 * cy - lcy; }
                    else { x1 = cx; y1 = cy; }
                    float x = sc.num() + ox, y = sc.num() + oy;
                    cur = begin(cur, cx, cy);
                    for (int k = 1; k <= 10; k++) {
                        float t = k / 10f, u = 1 - t;
                        cur.add(u * u * cx + 2 * u * t * x1 + t * t * x);
                        cur.add(u * u * cy + 2 * u * t * y1 + t * t * y);
                    }
                    lcx = x1; lcy = y1; cx = x; cy = y;
                }
                case 'A' -> {
                    float rx = sc.num(), ry = sc.num(), rot = sc.num();
                    int fa = sc.flag(), fs = sc.flag();
                    float x = sc.num() + ox, y = sc.num() + oy;
                    cur = begin(cur, cx, cy);
                    arc(cur, cx, cy, rx, ry, rot, fa, fs, x, y);
                    cx = x; cy = y;
                }
                default -> { return polys; }
            }
            prev = up;
        }
        if (cur != null) polys.add(cur);
        return polys;
    }

    private static List<Float> begin(List<Float> cur, float x, float y) {
        if (cur == null) { cur = new ArrayList<>(); cur.add(x); cur.add(y); }
        return cur;
    }

    private static List<Float> pt(List<Float> cur, float fx, float fy, float x, float y) {
        cur = begin(cur, fx, fy);
        cur.add(x); cur.add(y);
        return cur;
    }

    /** SVG 仕様 F.6.5 の端点表現 → 中心表現変換。 */
    private static void arc(List<Float> out, double x0, double y0, double rx, double ry, double rotDeg,
                            int fa, int fs, double x, double y) {
        if (rx == 0 || ry == 0) { out.add((float) x); out.add((float) y); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / den));
        if (fa == fs) co = -co;
        double cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x0 + x) / 2, cy = sin * cxp + cos * cyp + (y0 + y) / 2;
        double th1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double th2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double dth = th2 - th1;
        if (fs == 0 && dth > 0) dth -= 2 * Math.PI;
        else if (fs == 1 && dth < 0) dth += 2 * Math.PI;
        int n = Math.max(4, (int) Math.ceil(Math.abs(dth) / (Math.PI / 12)));
        for (int k = 1; k <= n; k++) {
            double t = th1 + dth * k / n, px = rx * Math.cos(t), py = ry * Math.sin(t);
            out.add((float) (cos * px - sin * py + cx));
            out.add((float) (sin * px + cos * py + cy));
        }
    }
}
