# 小節線オブジェクト（チャンネル `ST`）実装仕様書（LR2oraja向け）

## 1. 概要
チャンネル `ST`（**小節線オブジェクト**）は、小節内の任意の位置にスキンの小節線画像（[`skin.getLine()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java#L582)）を1本追加描画するためのチャンネル定義である。  
各小節先頭（`pos = 0.0`）に自動生成される通常の小節線はそのまま維持され、ハイスピード・`STOP`・`#SCROLL`・`#SPEED`・CONSTANT 等への追従は通常の小節線と完全に同一とする。

---

## 2. BMSチャンネル仕様とデコード規則

### 2.1 チャンネル割り当て
- **チャンネル表記**: `#xxxST:...`
- **36進数内部値**: [`Section.SECTION_LINE`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java) = `28 * 36 + 29`（`1037`）

### 2.2 オブジェクト値の解釈
- **`00`**：配置なし（無視）
- **`00` 以外**（`01` ～ `ZZ`、`#BASE 62` 時は `01` ～ `zz`）：
  - 指定された小節内相対位置 `pos`（$0.0 \le \text{pos} < 1.0$）に対応する [`TimeLine`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/TimeLine.java) を取得（未生成の場合は生成）し、`tl.setSectionLine(true)` を設定する。
  - オブジェクト値の数値そのものによる違いはなく、`00` 以外のすべての値で同一の小節線が1本描画される（音声再生やBGA等の副作用は一切持たない）。
- **既存の自動小節線・重複定義との扱い**:
  - 各小節先頭（`sectionnum`）には従来通り `basetl.setSectionLine(true)` によって自動小節線が設定される。
  - 小節先頭（`pos = 0.0`）に `ST` の非 `00` 定義が置かれた場合や、同一小節・同一位置に複数の `#xxxST` 行で非 `00` が定義された場合も、`TimeLine.sectionLine` フラグ（`boolean`）として合流するため、同一座標に多重描画されることなく1本の小節線として描画される。
- **小節倍率（`#xxx02`）および条件分岐（`#RANDOM` / `#CONDITIONAL`）への対応**:
  - 小節倍率 `rate` が指定されている場合は `sectionnum + rate * pos` の位置に配置される。
  - 通常小節定義および `#CONDITIONAL` 分岐ブロック内の双方で [`Section`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java) を通じて同様に解釈される。

---

## 3. 描画・スクロール追従仕様（[`LaneRenderer`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java)）

- **描画対象タイムラインの抽出**:
  - [`LaneRenderer.init`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java#L110) および [`LaneRenderer.updateModel`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java#L164) は既に `tl.getSectionLine()` が `true` の [`TimeLine`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/TimeLine.java) を描画対象配列 `this.timelines` に含めているため、ノーツやBPM変化が存在しない座標に単独で配置された `ST` の [`TimeLine`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/TimeLine.java) も自動的に抽出される。
- **速度変化・ギミックへの追従**:
  - [`Section.makeTimeLines`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java#L403-L424) において、小節内の BPM変化・`STOP`・`#SCROLL`・`#SPEED` のタイムラインが構築された後にチャンネル行（`processChannelLines`）が処理されるため、`getTimeLine(sectionnum + rate * pos)` により生成される `TimeLine` は直前の BPM・`STOP`・`#SCROLL`・`#SPEED` を正確に引き継ぐ。
  - [`LaneRenderer.drawLane`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java#L580-L585) にて、通常の小節線と全く同じ座標計算（hi-speed / `STOP` / `#SCROLL` / `#SPEED` / CONSTANT フェードイン）を経て `skin.getLine()` が1本描画される。

---

## 4. 他システムへの非干渉

- **鍵盤モード判別**:
  - `Section.NOTE_CHANNELS` には含めず、5K/7K/10K/14K/9K などのモード自動昇格には一切影響しない。
- **ノーツ数・密度・判定・スコア**:
  - `Note` オブジェクトを生成しないため、総ノーツ数（`#TOTAL` 計算）、楽曲情報の密度計算・ノーツ分布グラフ、判定処理（[`JudgeManager`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/JudgeManager.java)）、譜面オプション（`ExtraNoteModifier` や `LaneShuffleModifier` 等）には一切影響しない。

---

## 5. 変更対象ファイル一覧

1. **[`Section.java`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java)**（`core/dependencies/jbms-parser`）
   - 定数 `public static final int SECTION_LINE = 28 * 36 + 29;`（`1037`）を追加。
   - コンストラクタの `switch (channel)` にて `case SECTION_LINE:` を `channellines` に追加。
   - `processChannelLines`（およびフォールバック箇所）の `switch (channel)` に `case SECTION_LINE:` を追加し、`this.processData(line, (pos, data) -> getTimeLine(sectionnum + rate * pos).setSectionLine(true));` を実行。
2. **[`BMSDecoderTest.java`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/test/bms/model/BMSDecoderTest.java)**（`core/test`）
   - `#xxxST` による任意位置への小節線追加、各小節頭の自動小節線の維持、`#SCROLL` / `STOP` / BPM変化との併用、および総ノーツ数への非干渉を検証する単体テストを追加。
