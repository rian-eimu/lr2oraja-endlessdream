# ダミーノーツ（Dummy Note）仕様書（LR2oraja向け）

## 1. 概要
ダミーノーツは、演奏中のレーン上に本物のノーツ（通常ノーツ・ロングノート・地雷・ポーション）と同じ見た目で流れてくる**演出専用の視覚オブジェ**である。  
判定そのものを行わず、ゲージ増減・コンボ・スコア・空POOR・キー音再生・総ノーツ数・密度グラフのすべてに一切影響しない。判定ラインに到達（通過）すると即座に消滅し、判定ライン上でのノート滞留も行わない。

---

## 2. チャンネル定義・鍵盤モード判別

### 2.1 チャンネル割り当て（36進数）
| 種別 | 1P側チャンネル | 1P内部値 (`base`) | 2P側チャンネル | 2P内部値 (`base`) |
| :--- | :--- | :--- | :--- | :--- |
| **ダミー通常ノーツ** | `H1` ～ `H9` | `17 * 36 + 1` (`613` ～ `621`) | `I1` ～ `I9` | `18 * 36 + 1` (`649` ～ `657`) |
| **ダミーロングノート** | `J1` ～ `J9` | `19 * 36 + 1` (`685` ～ `693`) | `K1` ～ `K9` | `20 * 36 + 1` (`721` ～ `729`) |
| **ダミー地雷** | `L1` ～ `L9` | `21 * 36 + 1` (`757` ～ `765`) | `M1` ～ `M9` | `22 * 36 + 1` (`793` ～ `801`) |
| **ダミーポーション** | `N1` ～ `N9` | `23 * 36 + 1` (`829` ～ `837`) | `O1` ～ `O9` | `24 * 36 + 1` (`865` ～ `873`) |

- チャンネル末尾 `1` ～ `9` と各鍵盤モード（5K/7K/10K/14K/9K）のレーン番号の対応は、既存の通常ノーツ（`CHANNELASSIGN_BEAT5` / `CHANNELASSIGN_BEAT7` / `CHANNELASSIGN_POPN`）と同一とする。
- データ値（`01` ～ `ZZ`、`#BASE 62` 時は `01` ～ `zz`）が `00` 以外の箇所にダミーノーツを生成する。
- 指定された非 `00` の値は音源再生やゲージ増減を一切行わないため、すべて**無音（`wav = -1` / LN終端は `-2`）・ダメージ `0`・回復量 `0`** として扱う。

### 2.2 鍵盤モードの自動昇格
ダミーノーツも本物のノーツと同様に、レーン使用として鍵盤モードの判別に参加する（通常小節および `#CONDITIONAL` 分岐内の双方で適用）：
1. **7鍵 / 14鍵への昇格**: オフセット `+7` または `+8`（例：`H8`/`H9`、`I8`/`I9` 等）に非 `00` 定義が存在する場合、`BEAT_5K → BEAT_7K`、`BEAT_10K → BEAT_14K` へ昇格する。
2. **2P（DP）モードへの昇格**: 2P側チャンネル（`I1–I9`、`K1–K9`、`M1–M9`、`O1–O9`）に非 `00` 定義が存在する場合、`BEAT_5K → BEAT_10K`、`BEAT_7K → BEAT_14K` へ昇格する。

---

## 3. データモデル設計（`jbms-parser`）

### 3.1 `Note` 基底クラスのダミーフラグ
既存の `NormalNote` / `LongNote` / `MineNote` / `PotionNote` の型判定（レーンシャッフルでのLNペア維持、描画分岐、`MineNoteModifier` 等）をそのまま活かせるよう、[`Note`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/Note.java) 基底クラスに `dummy` フラグを持たせる。

```java
private boolean dummy;

public boolean isDummy() {
    return dummy;
}

public void setDummy(boolean dummy) {
    this.dummy = dummy;
}
```

| ダミー種別 | インスタンスクラス | 設定値 |
| :--- | :--- | :--- |
| ダミー通常ノーツ (`Hx`/`Ix`) | [`NormalNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/NormalNote.java) | `wav = -1`, `dummy = true` |
| ダミーロングノート (`Jx`/`Kx`) | [`LongNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/LongNote.java) | `wav = -2`, `type = lnmode`, `dummy = true`（始点・終点のペア方式） |
| ダミー地雷 (`Lx`/`Mx`) | [`MineNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/MineNote.java) | `wav = -1`, `damage = 0`, `dummy = true` |
| ダミーポーション (`Nx`/`Ox`) | [`PotionNote`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/PotionNote.java) | `wav = -1`, `recovery = 0`, `dummy = true` |

### 3.2 ダミーロングノート (`J1–J9` / `K1–K9`) の構築仕様
- 専用LNチャンネル（`51–59` / `61–69`）と同様に、同一レーン上の1つ目の非 `00` 定義を始点、2つ目の非 `00` 定義を終点とするペア方式で構築する。
- 始点・終点ともに `setType(lnmode)` および `setDummy(true)` を設定し、見た目のLN種別（LN / CN / HCN / HLN）は譜面の `#LNMODE` およびプレイヤーのLNオプション（`lntype`）に従う。
- 譜面終端までに終点が定義されなかった未閉鎖のダミーLNは、本物のLNと同様に警告を記録して始点を破棄する。

### 3.3 同一タイムライン・同一レーンでの優先順位
同一タイムライン・同一レーンに複数の定義が重なった場合、**本物のノーツをすべて処理した後にダミーノーツを処理**する：
1. 専用LN（`5x` / `6x`）
2. 通常ノーツ・`#xxxOBJ` マーカー（`1x` / `2x`）
3. 地雷ノーツ（`Dx` / `Ex`）
4. ポーションノーツ（`Fx` / `Gx`）
5. **ダミーロングノート（`Jx` / `Kx`）**
6. **ダミー通常ノーツ（`Hx` / `Ix`）**
7. **ダミー地雷（`Lx` / `Mx`）**
8. **ダミーポーション（`Nx` / `Ox`）**

- すでに当該レーン・タイムラインにノーツが存在する場合、または既存のLN（本物・ダミー問わず）の区間内部にある場合は、DecodeLog（`WARNING`）を記録して後続のダミーノーツを破棄する（BGMレーンへの移動も行わない）。
- フリーゾーン区間内にダミーノーツが存在しても、フリーゾーンの `innerNotes`（代表判定対象）には含めない。

---

## 4. プレイ中の挙動・判定・音声（`JudgeManager` / `PlayerResource` / `AbstractAudioDriver`）

- **判定・ゲージ・スコア・コンボすべて非干渉**:
  - [`JudgeManager`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/play/JudgeManager.java) の通過判定ループ（HCN/HLN精算・地雷・ポーション・AUTOPLAYを含む）、キー入力判定ループ、および空POORキー音探索ループにおいて、`note.isDummy()` が `true` のノーツはすべてスキップする。
  - AUTOPLAY時も自動打鍵・キービーム点灯・効果音再生を行わずそのまま通過させる。
  - ダミーロングノート通過中もLN/CN/HCN/HLNの保持状態（`processing` / `passing` / `hlnStart`）に一切入らない。
- **デフォルト地雷音・音声マップからの除外**:
  - [`PlayerResource`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/PlayerResource.java) および [`AbstractAudioDriver`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/audio/AbstractAudioDriver.java) にて、ダミーノーツ（ダミー地雷を含む）にはデフォルト地雷爆発音（`wavcount`）を割り当てず、音声ロード対象からも除外する。

---

## 5. スキン・描画仕様（`LaneRenderer`）

- **使用スキン画像**:
  - ダミー通常ノーツ：`lanes[lane].noteImage`（本物の通常ノーツと同じ）
  - ダミーロングノート：`lanes[lane].longImage`（本物のLNと同じ、非アクティブ状態のボディ＋始点＋終点。`#LNMODE` および `lntype` に従う）
  - ダミー地雷：`lanes[lane].mineImage`（本物の地雷と同じ）
  - ダミーポーション：`lanes[lane].potionImage`（本物のポーションと同じ、未定義時は地雷の色反転）
- **判定ライン到達時の即時消滅（ノート滞留なし）**:
  - `noteretention`（判定ラインへのノーツ滞留）や `showpastnote`（過去ノート表示）、およびPMSスキンでの見逃しPOOR落下表示（`dstnote2`）が有効であっても、`note.isDummy()` が `true` の単発ノーツは **`tl.getMicroTime() >= microtime` の間のみ描画し、判定ライン到達後は即座に消滅**させる。
  - ダミーロングノートは本物のLNと同様に、判定ラインを通過した部分から順次クリッピングされ、終点が判定ラインを通過（`ln.getPair().getMicroTime() < microtime`）した時点で消滅する。

---

## 6. ノーツ数・密度・グラフ・譜面特徴フラグ

以下のすべてにおいて、`note.isDummy()` が `true` のノーツは**非計上（除外）**とする：
- [`TimeLine.getTotalNotes()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/TimeLine.java#L152-L174) および [`BMSModelUtils.getTotalNotes()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSModelUtils.java#L66-L153)
- [`BMSModel.containsMineNote()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSModel.java#L476-L485) / [`containsLongNote()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSModel.java#L487-L496) / [`containsUndefinedLongNote()`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/dependencies/jbms-parser/src/bms/model/BMSModel.java#L498-L509)
- [`SongData`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/song/SongData.java) のノーツ数・特徴フラグ（`FEATURE_LONGNOTE`, `FEATURE_MINENOTE` 等）
- [`SongInformation`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/song/SongInformation.java) のレーン別ノーツ数・密度（平均/最大/終盤）・分布配列
- [`SkinNoteDistributionGraph`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/skin/SkinNoteDistributionGraph.java)（選曲画面・プレイ画面のノーツ分布グラフ）

---

## 7. 譜面オプション（`PatternModifier`）との連携

- **レーンシャッフル（RANDOM / MIRROR / R-RANDOM / CROSS / FLIP / BATTLE）**:
  - [`LaneShuffleModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/LaneShuffleModifier.java) により、本物のノーツと一緒に同一のレーン置換が適用される。ダミーLNも始点・終点のペアを維持したまま移動する。
- **ノーツ単位シャッフル（S-RANDOM / H-RANDOM / SPIRAL / ALL-SCR 等）**:
  - [`Randomizer`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/Randomizer.java) により、タイムラインごとのレーン置換が適用される。ダミーLNの区間中は `LNactive` により始点と終点が同じレーンに束縛される。
  - 縦連回避計算（`TimeBasedRandomizer` 等）においては、地雷・ポーションと同様に `note.isDummy()` を「叩く必要のないノーツ（`emptyLane` 扱い・`lastNoteTime` 更新なし）」として扱う。
- **EXTRA NOTE オプション（[`ExtraNoteModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/ExtraNoteModifier.java)）**:
  - `ExtraNoteModifier` の適用時、追加ノーツの配置処理を行う前に**全タイムライン・全レーンのダミーノーツ（`note.isDummy()`）ならびに通常の地雷ノーツ（`MineNote`）・ポーションノーツ（`PotionNote`）をすべて除去**する。
- **ロングノートオプション（[`LongNoteModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/LongNoteModifier.java)）**:
  - `Mode.REMOVE` の場合、ダミーLNも対象とし、始点を**ダミー通常ノーツ（`NormalNote(-1)` with `dummy = true`）**に変換し、終点を `null` にする（ダミーLNのみの変換では `AssistLevel.ASSIST` にしない）。
  - `Mode.ADD_*`（通常ノーツをLN化するモード）では、ダミー通常ノーツは対象外とする。
- **地雷オプション（[`MineNoteModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/MineNoteModifier.java)）**:
  - `Mode.REMOVE` の場合、本物の `MineNote` と一緒にダミー地雷（`MineNote` かつ `isDummy() == true`）も除去する（ダミー地雷のみの除去では `AssistLevel.LIGHT_ASSIST` にしない）。
- **オートプレイオプション（[`AutoplayModifier`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/AutoplayModifier.java) / [`PatternModifier.moveToBackground`](file:///d:/Dropbox/GitHub/lr2oraja-endlessdream-rian/core/src/bms/player/beatoraja/pattern/PatternModifier.java#L150-L182)）**:
  - AUTO SCRATCH 等で対象レーンが自動演奏化されても、ダミーノーツは無音かつ非判定のためBGMレーンへの移動や削除、およびアシスト判定（`AssistLevel.ASSIST`）の発火対象から除外する。
