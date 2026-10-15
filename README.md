# Better Fonts (Fabric, Minecraft 26.3)

Google Fonts を Minecraft の文字として使う client-only Mod。F9 で設定画面。

## ビルド
JDK 25 / `./gradlew runClient` / `./gradlew build`
`assets/betterfonts/fonts/NotoSans-Regular.ttf` を置く (SIL OFL)。
アイコンは `assets/betterfonts/icons/` に Material Symbols の SVG を置く (README.txt 参照)。

## 構成
- font/FontFace         stb_truetype。複数フォントのフォールバックスタックで描画
- font/TextRasterizer   ビットマップ → テクスチャ → バニラ GUI パイプライン (GL/Vulkan 共通)
- font/GoogleFontsApi   カタログ + TTF ダウンロード (保存前に形式検証)
- font/FontLibrary      DL 済み管理 / 描画スタック解決
- mixin/                バニラの text() (String / Component / FormattedCharSequence) に被せるフック
- ui/Shapes             角丸 + 枠線を実ピクセル解像度の AA テクスチャで描画
- ui/Icons              SVG (Material Symbols) を実行時ラスタライズ
- ui/ModButton          Mod 独自デザインのボタン
- ui/BetterFontsScreen  Browse / Library / Settings / Preview

## Building (multi-version)

One source tree builds for both Minecraft versions. **Always edit `src/main`** (written against 26.3).

```
gradle build -Pmc=26.3       # -> build/26.3/libs/betterfonts-0.1.0+26.3.jar
gradle build -Pmc=1.21.11    # -> build/1.21.11/libs/betterfonts-0.1.0+1.21.11.jar
gradle runClient -Pmc=1.21.11
```

For 1.21.11 the sources are translated at build time (`gradle/port-1.21.11.gradle`, output in `build/1.21.11/port/java`).
Simple renames live in the rule table at the top of that file; if a file needs a real rewrite,
put a 1.21.11 version at the same path under `src/1.21.11/java/` and it replaces the translated one.
Per-version dependency versions are in `build-26.3.gradle` / `build-1.21.11.gradle`; the default target for IDE import is `mc` in `gradle.properties`.
