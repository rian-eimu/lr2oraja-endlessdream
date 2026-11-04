# LR2oraja ポーションノーツ（Potion Note）仕様書

## 1. 概要

**ポーションノーツ（Potion Note）**は、地雷ノーツ（[`MineNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/MineNote.java)）の対極となるノーツ種別です。  
ノーツが判定ラインを通過する瞬間に該当レーンのキーが押下されていれば取得でき、指定された割合（%）だけゲージが回復します。取り損ねてもペナルティはなく、コンボ・スコア・判定カウント・空POOR・総ノーツ数には一切影響しません。

---

## 2. BMSフォーマット・チャンネル仕様

### 2.1 チャンネル割り当て
[`Section`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java) および [`BMSDecoder`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSDecoder.java) にて以下のチャンネルをポーションノーツとして解釈します。

| プレイヤー | チャンネル範囲 | 36進数ベース定数 | レーン割り当て |
| :--- | :--- | :--- | :--- |
| **1P** | `F1` – `F9` | `P1_POTION_KEY_BASE = 15 * 36 + 1` (`541`–`549`) | 地雷（`D1`–`D9`）と同一のレーンマッピング |
| **2P** | `G1` – `G9` | `P2_POTION_KEY_BASE = 16 * 36 + 1` (`577`–`585`) | 地雷（`E1`–`E9`）と同一のレーンマッピング |

- **モード自動昇格**:
  - 通常ノーツや地雷ノーツと同様、`F7`/`F8`/`G7`/`G8` の使用時は 5KEYS → 7KEYS（または 10KEYS → 14KEYS）、`G1`–`G9` の使用時は 1P → 2P（DP）へのモード昇格対象に含めます（[`Section.NOTE_CHANNELS`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java#L48-L49) に追加）。

### 2.2 オブジェクト値（回復量%）の解釈
- `00`：配置なし（無視）
- `00` 以外：回復量（%）を表す値として解釈します。
  - 通常の36進数定義（`#BASE 62` 時は62進→36進変換を含む現行 [`MineNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java#L614-L617) と同一のデコード規則）に従い、オブジェクト値をそのまま回復量 `%`（`double`）として保持します（例：`01` = 1.0%、`0A` = 10.0%、`1E` = 50.0%、`ZZ` = 1295.0%）。
- **WAV（効果音）**:
  - ポーションノーツは効果音を持たず、`wav` 値には常に `-1`（無音）を設定します。

### 2.3 同一座標でのノーツ衝突時の優先順位
[`Section.processChannelLines`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Section.java#L540-L621) における同一タイムライン・同一レーンの配置優先順位は以下の通りとします。
1. 専用ロングノーツ（`5x` / `6x`）
2. 通常ノーツ・LNマーカー（`1x` / `2x`）
3. 地雷ノーツ（`Dx` / `Ex`）
4. **ポーションノーツ（`Fx` / `Gx`）**

既に同一位置に他のノーツが存在する場合、または既存のロングノーツ区間内である場合は、地雷ノーツと同様に `DecodeLog` に警告を記録してポーションノーツの配置を無視します。

---

## 3. データモデル・ノーツグラフ仕様

### 3.1 `PotionNote` クラスの新設
`bms.model` パッケージに [`Note`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Note.java) を継承する [`PotionNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/PotionNote.java) クラスを新設します。

### 3.2 総ノーツ数・楽曲密度からの除外とノーツ分布グラフでの描画
- **総ノーツ数（`#TOTAL` 計算等）**:
  - [`TimeLine.getTotalNotes`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/TimeLine.java#L194-L214) および [`BMSModelUtils.getTotalNotes`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSModelUtils.java#L170-L221) では `NormalNote` / `LongNote` / `FreeZoneNote` のみを対象としているため、`PotionNote` は総ノーツ数に数えられません。
- **ノーツ分布グラフ（[`SkinNoteDistributionGraph`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/SkinNoteDistributionGraph.java) / [`SongInformation`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/song/SongInformation.java)）**:
  - `TYPE_NORMAL`（選曲画面・プレイ画面・リザルト画面等のノーツ分布グラフ）において、地雷ノーツの暗い赤（`#880000`、インデックス `6`）の対極として **暗い水色（`#008888`、インデックス `7`）** で描画します。
  - 平均密度（`density`）・最大密度（`peakdensity`）・終盤最大密度（`enddensity`）の数値計算（インデックス `0〜5` のみ合算）や、`TYPE_JUDGE` / `TYPE_EARLYLATE` の判定分布グラフからは除外します。

---

## 4. プレイ・判定・ゲージ回復仕様

### 4.1 通過時取得判定（[`JudgeManager.update`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/JudgeManager.java#L573-L595)）
- ノーツ通過時刻（`note.getMicroTime() <= mtime && note.getMicroTime() > prevmtime`）に、該当レーンに対応するいずれかの物理キーが押下状態（`pressed == true`）であれば取得成立とします。
- **AUTOPLAY時の挙動**:
  - 地雷ノーツと同様、AUTOPLAY時（`autoplay == true`）は自動取得を行わずスルーします。
- **取得時の処理**:
  - `main.getGauge().addValue((float) pnote.getRecovery())` を呼び出し、現在のゲージ（および裏ゲージ等の全 [`Gauge`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/GrooveGauge.java#L83-L92) 要素）に回復量 `%` を加算します（ゲージ種別を問わず、残量が `0` より大きい場合は上限 100% まで回復します）。
  - 効果音は再生しません（無音）。
  - 取り損ねた場合（`pressed == false`）は何も発生しません。

### 4.2 スコア・コンボ・判定・空POORへの非干渉
- **コンボ・スコア・判定カウント**: 一切変更しません。
- **キー押下時の判定対象除外（[`JudgeManager`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/JudgeManager.java#L818)）**:
  - キー押下時のノーツ探索ループおよび空押し音再生探索において、`MineNote` と同様に `PotionNote` を除外します。

---

## 5. 譜面オプション（PatternModifier）仕様

- **地雷オプション（[`MineNoteModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/MineNoteModifier.java)）**:
  - `mineMode REMOVE` を選択しても `PotionNote` は消去せず、常に有効とします。
- **自動演奏オプション（[`AutoplayModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/AutoplayModifier.java) / [`PatternModifier.moveToBackground`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/PatternModifier.java#L177)）**:
  - AUTO SCRATCH 等の対象レーンであっても `PotionNote` は消去およびBGMレーンへの移動を行いません。
- **レーン・ノーツシャッフル系オプション（[`Randomizer`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/Randomizer.java)）**:
  - S-RANDOM / H-RANDOM 等のタイムライン単位シャッフルでは、`MineNote` と同様に空レーン扱い・縦連防止時間（`lastNoteTime`）計算の対象外としてシャッフルします。

---

## 6. スキン・描画仕様

### 6.1 LR2スキン拡張（[`LR2PlaySkinLoader`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/lr2/LR2PlaySkinLoader.java)）
- `#SRC_POTION` コマンドを追加し、`#SRC_MINE` と同じパラメータ形式で各レーンのポーションノーツ画像を読み込みます。
- `#DST_POTION` コマンドを受け付けます（描画座標自体は地雷ノーツと同様に `#DST_NOTE` のレーン座標を共有するため no-op とします）。

### 6.2 JSON / Luaスキン（[`JsonSkin`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/json/JsonSkin.java#L347) / [`JsonPlaySkinObjectLoader`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/json/JsonPlaySkinObjectLoader.java)）
- [`JsonSkin.NoteSet`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/json/JsonSkin.java#L347) に `public String[] potion = new String[0];` を追加します。

### 6.3 未定義時の代替描画（地雷画像の色反転）
[`SkinNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/SkinNote.java) において、対象レーンの `potion` スキンソースが未定義（`null`）の場合、同レーンの地雷ノーツ画像（`minenote`）の **RGB色反転テクスチャ** を動的生成して代替描画します。

### 6.4 レーン描画（[`LaneRenderer`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/LaneRenderer.java#L735-L742)）
- `note instanceof PotionNote` の場合、`MineNote` と同様に通過時刻（`tl.getMicroTime() >= microtime`）まで `lanes[lane].potionImage` を用いてノーツを描画します。
