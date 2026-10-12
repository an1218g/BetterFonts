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
