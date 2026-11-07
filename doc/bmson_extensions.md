# BMSON拡張定義（ポーション・ダミー・フリーゾーン・動的条件分岐）実装仕様書（LR2oraja向け）

本ドキュメントは、BMS 向けに実装した拡張要素（ポーションノーツ、ダミーノーツ4種、スクラッチ・フリーゾーン、小節線オブジェクト、動的条件分岐 `#CONDITIONAL`）に対応する **BMSON フォーマット定義**の仕様書です。

---

## 1. 対応要素一覧と概要

| BMS要素 | BMSONフィールド・構造 | 概要・備考 |
| :--- | :--- | :--- |
| **F1–F9 / G1–G9**<br>（ポーション） | `potion_notes: [{ "x", "y", "recovery" }]` | `recovery` = 回復量（%）。通過時にキー押下中でゲージ回復（無音）。 |
| **H～K**<br>（ダミー通常 / ダミーLN） | `dummy_notes: [{ "x", "y", "l", "t" }]` | `l > 0` でダミーLN、`l <= 0`（または省略）でダミー通常ノーツ。<br>`t` はLN種別（`1`=LN / `2`=CN / `3`=HCN / `4`=HLN、省略時は `info.ln_type`）。 |
| **L1–L9 / M1–M9**<br>（ダミー地雷） | `dummy_mine_notes: [{ "x", "y" }]` | 演出専用のダミー地雷（無音・ダメージ0・非判定）。 |
| **N1–N9 / O1–O9**<br>（ダミーポーション） | `dummy_potion_notes: [{ "x", "y" }]` | 演出専用のダミーポーション（無音・回復量0・非判定）。 |
| **ST**<br>（小節線オブジェクト） | `lines: [{ "y" }]` | bmson標準の `lines` と同一。 |
| **SP**<br>（ハイスピード変化） | `speed_events: [{ "y", "rate" }]` | `rate` = ハイスピード倍率（線形補間）。 |
| **SF / SG / SH / SI**<br>（フリーゾーン） | `free_zone_channels: [{ "name", "notes": [{ "x", "y", "l" }] }]` | `x` は皿レーン（5K/7Kの1P皿は `8`、10K/14Kの2P皿は `16`）。<br>`l` は区間長（pulse）、省略時（`<= 0`）は四分音符1個分。<br>`name` は区間開始位置 `y` でBGMノートとして自動再生する音（任意）。 |
| **#CONDITIONAL**<br>（動的条件分岐） | `conditional_blocks: [{ "y", "branches": [...] }]` | `y` は評価タイミング（pulse）、`branches` は分岐の配列。<br>各分岐は `"when": "<条件式>"` または `"default": true`（必須）を持つ。 |

プレイ中の判定・ゲージ増減・スキン描画・総ノーツ数計上・密度グラフ・譜面オプション（`PatternModifier`）・リプレイ記録等のランタイム挙動は、BMS 側の各仕様（`free_zone.md`, `potion_notes.md`, `dummy_notes.md`, `section_line.md`）と完全に共通です。

---

## 2. 座標系・レーンマッピング共通規則

### 2.1 パルス座標（`y` / `l`）と小節位置（`section`）の変換
- 1小節（4分音符4個分）の基準パルス数 `resolution` は `bmson.info.resolution > 0 ? bmson.info.resolution * 4 : 960`（四分音符1個分は `quarterPulse = resolution / 4.0`、デフォルト `240`）とします。
- 任意のパルス位置 `y` に対する小節座標 `section` は `y / resolution` として算出します。

### 2.2 レーン番号（`x`）のマッピング
[`BMSONDecoder`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSONDecoder.java) の `keyassign` テーブルに従い、1-indexed の `x` を内部レーン番号 `key` に変換します：
- `beat-5k`: `x = 1..5` → 鍵盤 `0..4`, `x = 8` → 1P皿 (`5`), `x = 6, 7` → 無効 (`-1`)
- `beat-7k`: `x = 1..7` → 鍵盤 `0..6`, `x = 8` → 1P皿 (`7`)
- `beat-10k`: `x = 1..5` → 1P鍵盤 `0..4`, `x = 8` → 1P皿 (`5`), `x = 9..13` → 2P鍵盤 `6..10`, `x = 16` → 2P皿 (`11`), `x = 6, 7, 14, 15` → 無効 (`-1`)
- `beat-14k`: `x = 1..7` → 1P鍵盤 `0..6`, `x = 8` → 1P皿 (`7`), `x = 9..15` → 2P鍵盤 `8..14`, `x = 16` → 2P皿 (`15`)
- その他のモード（`popn-9k`, `keyboard-24k` 等）: `x = 1..N` → `0..(N - 1)`
- ※BMSONでは `info.mode_hint` によって鍵盤モードが決定されるため、ノーツの `x` に応じたモード自動昇格（5K→7K、SP→DP）は行いません。

---

## 3. 各拡張ノーツ・チャネルのデコード仕様

### 3.1 ポーションノーツ（`potion_notes`）
- **JSON書式**:
  ```json
  "potion_notes": [{ "x": 2, "y": 480, "recovery": 10 }]
  ```
- **デコード処理**:
  - `key >= 0`（有効なプレイレーン）かつ `recovery > 0` の場合、対象タイムラインに [`new bms.model.PotionNote(-1, n.recovery)`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/PotionNote.java) を配置します。
  - 同一レーンの既存LN区間内にある場合、または同一タイムライン・同一レーンに既にノーツ（通常ノーツ・LN・地雷・先行するポーション）が存在する場合は、`DecodeLog(WARNING, ...)` を記録して無視します。

### 3.2 ダミー通常ノーツ / ダミーLN（`dummy_notes`）
- **JSON書式**:
  ```json
  "dummy_notes": [
    { "x": 4, "y": 480, "l": 0 },
    { "x": 5, "y": 480, "l": 240, "t": 3 }
  ]
  ```
- **デコード処理**:
  - **`l > 0`（ダミーLN）の場合**:
    - 始点 `y` と終点 `y + l` の `TimeLine` を取得し、閉区間 `[y, y + l]` が既存のLN（本物・ダミー問わず）または既存ノーツと重なる場合は `DecodeLog(WARNING, ...)` を記録して破棄します。
    - 競合がない場合、始点・終点の `LongNote(-2)` に `setDummy(true)` を設定し、始点のLN種別に `n.t >= 1 && n.t <= 4 ? n.t : model.getLnmode()` を設定してペアリングします。
  - **`l <= 0`（ダミー通常ノーツ）の場合**:
    - 同一位置に既にノーツが存在する場合、または既存LN（本物・ダミー問わず）の区間内にある場合は `DecodeLog(WARNING, ...)` を記録して無視します。
    - 競合がない場合、`NormalNote(-1)` に `setDummy(true)` を設定して配置します。

### 3.3 ダミー地雷（`dummy_mine_notes`）・ダミーポーション（`dummy_potion_notes`）
- **JSON書式**:
  ```json
  "dummy_mine_notes":   [{ "x": 6, "y": 720 }],
  "dummy_potion_notes": [{ "x": 7, "y": 720 }]
  ```
- **デコード処理**:
  - 同一位置に既にノーツが存在する場合、または既存LN（本物・ダミー問わず）の区間内にある場合は `DecodeLog(WARNING, ...)` を記録して無視します。
  - 競合がない場合、それぞれ `new bms.model.MineNote(-1, 0)` / `new bms.model.PotionNote(-1, 0)` に `setDummy(true)` を設定して配置します。

### 3.4 同一座標での配置優先順位
同一タイムライン・同一レーンに複数の定義が存在する場合、以下の順序で処理します（先勝ち）：
1. `sound_channels`（本物のLN・通常ノーツ）
2. `mine_channels`（本物の地雷ノーツ）
3. `potion_notes`（本物のポーションノーツ）
4. `dummy_notes` のうち `l > 0`（ダミーLN）
5. `dummy_notes` のうち `l <= 0`（ダミー通常ノーツ）
6. `dummy_mine_notes`（ダミー地雷）
7. `dummy_potion_notes`（ダミーポーション）

### 3.5 小節線オブジェクト（`lines`）
- BMSのチャンネル `ST` に相当する任意位置の小節線追加は、bmson標準の `"lines": [{ "y": ... }]` と同一です。

### 3.6 フリーゾーン（`free_zone_channels`）
- **JSON書式**:
  ```json
  "free_zone_channels": [
    { "name": "fz.wav", "notes": [{ "x": 8, "y": 960, "l": 960 }] }
  ]
  ```
- **デコード処理**:
  - **対象レーン検証**:
    - `key = keyassign[n.x - 1]` が現在の `Mode` のスクラッチレーン（`model.getMode().isScratchKey(key)`）である場合のみ有効とします（それ以外の `x` は `WARNING` ログを記録して無視）。
  - **区間開始音（`name`）のBGM自動再生**:
    - `name` が非空文字列の場合、`wavmap` に音源として登録し、有効な各フリーゾーンノーツの開始位置 `n.y` の `TimeLine` に `tl.addBackGroundNote(new NormalNote(id))` を追加して自動再生します。
  - **区間長と結合（マージ）・内包ノーツ紐付け**:
    - 区間長パルスは `n.l > 0 ? n.l : quarterPulse`（省略または `0` 以下の場合は四分音符1個分）とします。
    - 各スクラッチレーンごとに重なり・隣接区間（`nextStart <= curEnd`）を1つの連続したフリーゾーンに結合し、閉区間内の本物の `NormalNote` / `LongNote` に `setFreeZoneInner(true)` を設定して [`bms.model.FreeZoneNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/FreeZoneNote.java) ペアを登録します。

---

## 4. 動的条件分岐（`conditional_blocks`）仕様

### 4.1 JSON書式と分岐内対応要素
```json
"conditional_blocks": [{
  "y": 3840,
  "branches": [
    { "when": "MISS>=100",
      "sound_channels": [{ "name": "hard.wav", "notes": [{ "x": 1, "y": 3840, "l": 0, "c": false }] }] },
    { "when": "GAUGE<30",
      "sound_channels": [{ "name": "easy.wav", "notes": [{ "x": 2, "y": 3840, "l": 0, "c": false }] }] },
    { "default": true,
      "sound_channels": [{ "name": "base.wav", "notes": [{ "x": 1, "y": 3840, "l": 0, "c": false }] }],
      "text_events": [{ "y": 3840, "text": "DEFAULT" }] }
  ]
}]
```

- **分岐内に記述可能な要素**:
  - チャネル・ノーツ系：`sound_channels`, `key_channels`, `mine_channels`, `free_zone_channels`, `potion_notes`, `dummy_notes`, `dummy_mine_notes`, `dummy_potion_notes`
  - タイミング・小節線系：`bpm_events`, `stop_events`, `scroll_events`, `speed_events`, `lines`
  - BGAイベント系：`bga`（`bga_events`, `layer_events`, `poor_events`）および分岐直下の `bga_events`, `layer_events`, `poor_events`
  - ※`text_events` 等の未実装フィールドは `@JsonIgnoreProperties(ignoreUnknown = true)` により読み飛ばします。
- **条件評価・スライス計算・サニタイズ**:
  - 各分岐は上から順に評価して最初に `TRUE` になった分岐を採用し、`"default": true` は必須とします。
  - 分岐内の `sound_channels` における `c: true`（続きから再生）のスライス計算は、各分岐（`branch`）内の `SoundChannel` 単体で完結して行います。
  - 分岐内の定義のうち、評価タイミングより前（`event.y < block.y`）にある定義は `DecodeLog(WARNING, ...)` を記録して無視（サニタイズ）します。
- **DEFAULT分岐での算出・事前ロード・リプレイ記録**:
  - `info.total`・総ノーツ数・スコア理論値・密度グラフ・楽曲長はすべて DEFAULT 分岐で算出します。
  - 全分岐で参照されるすべての音源（スライス音 `SliceWav` を含む）はプレイ開始前に [`AbstractAudioDriver`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/audio/AbstractAudioDriver.java) で一括事前ロードされます。
  - 採用された分岐インデックス配列は [`ReplayData.conditionalBranches`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/ReplayData.java) に記録されます。

