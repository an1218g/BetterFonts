package dev.betterfonts.font;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * フォールバック用のフォント列。日本語フォントは Google 側で文字範囲ごとに分割されて
 * 100 個以上のファイルになるため、「どのフォントがこの文字を持つか」を文字単位でキャッシュする。
 */
public final class FontStack extends AbstractList<FontFace> {
    private final List<FontFace> faces;
    private final String id;
    private final Map<Integer, FontFace> pickCache = new HashMap<>();

    public FontStack(List<FontFace> faces) {
        this.faces = new ArrayList<>(faces);
        this.id = this.faces.stream().map(FontFace::id).collect(Collectors.joining("|"));
    }

    public String id() { return id; }
    @Override public FontFace get(int i) { return faces.get(i); }
    @Override public int size() { return faces.size(); }

    /** この文字を持つ最初のフォント。どれも持たなければ null。 */
    /** キャッシュを使わずに判定 (大量スキャン用)。 */
    public boolean covers(int cp) {
        for (FontFace f : faces) if (f.hasGlyph(cp)) return true;
        return false;
    }

    public synchronized FontFace pickOrNull(int cp) {
        if (pickCache.containsKey(cp)) return pickCache.get(cp);
        FontFace r = null;
        for (FontFace f : faces) if (f.hasGlyph(cp)) { r = f; break; }
        pickCache.put(cp, r);
        return r;
    }
}
