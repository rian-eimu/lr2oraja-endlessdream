<div align="center" style="line-height: 1;">

# LR2oraja \~Endless Dream\~ (rian版)



</div>

Endless Dream は、[beatoraja](https://github.com/exch-bms2/beatoraja) のコミュニティフォークであり、アップストリーム版にはない QoL（快適性向上）パッチや新機能を統合したドロップイン互換クライアントです。

LR2 の判定やゲージ仕様を導入した [LR2oraja](https://github.com/wcko87/lr2oraja) をベースとしており、アップストリームに残る課題の解決を目指すとともに、本家へ統合されない可能性のある拡張や改変のハブとなることを目的としています。

本リポジトリ（**rian版**）は、Endless Dream をベースに、LR2風の操作性・難易度別フィルターの追加、62進数BMS再生の不具合修正、Extra Noteの改善、DX MODE、oraja_helper 連携機能などを独自に追加・統合したカスタムビルドです。

---

## Endless Dream-rian版の主な変更点・新機能

### 1. 選曲画面：LR2風難易度別フィルターの追加
本カスタムビルド一番の目玉機能です。LR2 に近い挙動の難易度別フィルター機能を用意しました。
- ボタンを押すことで **BEGINNER → NORMAL → HYPER → ANOTHER → INSANE** の順に素早く絞り込みが可能です。
- キーコンフィグに対応していますので、お好みのキーに割り当ててご使用いただけます。
- beatoraja には難易度別フィルターが存在せず、インストールされた BMS が一覧で並ぶ仕様でしたが、本機能により BOF などの数千曲に及ぶ大型イベントフォルダでも快適に選曲できます。

### 2. プレイ画面：矢印キーによるオプション操作 (LR2仕様) & フローティングハイスピード
プレイ中のキーボード操作を LR2 準拠に変更しました。
- <kbd>↑</kbd> / <kbd>↓</kbd> : ハイスピードの変更
- <kbd>←</kbd> / <kbd>→</kbd> : レーンカバーの調整

さらに、「レーンカバー等がOFF」かつ「ハイスピード自動調整（皿チョン）が無効」の場合に限り、
<kbd>START</kbd> + <disk>Analog Scratch</disk> 操作で、ハイスピードを **0.01 単位で精密に調整** できる機能（フローティングハイスピード）を追加しました。

### 3. プレイ画面：62進数BMSが正常に再生されない不具合を修正
LR2oraja 0.8.8 において `jbms-parser` が最新版でなかったために生じていた「62進数BMSが正常に再生されない不具合」について、本カスタムビルドでは `jbms-parser` を更新してビルドしているため、正常に再生されます。

### 4. リザルト画面：5鍵/10鍵モードでのリトライバグ修正
beatoraja に存在した「5鍵モードプレイ時に、リザルト画面で7鍵側のキー（同ランダムリトライ等）が効かない」不具合を修正しました。
- 5鍵/10鍵楽曲でも当たり譜面のリトライが可能です。
- 5鍵モードでも6鍵でリザルトのグラフ表示を切り替えられます。

### 5. 練習機能：Extra Note の「無理な縦連打」を抑制
ノート数を水増しする「Extra Note」オプションを使用した際、完全ランダム生成による物理的に不可能な縦連打が発生していました。
- ノート間の最小間隔制限を導入することで無理な縦連打を抑制し、より自然な高密度練習が可能になりました（LR2の EXTRA MODE を目指した調整です）。

### 6. システム・その他
- **スクリーンショットの軽量化**: 保存形式を PNG から JPG に変更し、ファイルサイズを大幅に削減しました（ランチャー設定から PNG / JPG の選択も可能です）。
- **スコア削除コマンド**: 選曲画面の検索バーに `/deletescore` と入力して Enter を押すことで、選択中の譜面のスコアデータをデータベースから削除できます。
- **DX MODE**: 某DXなゲームに判定とゲージを極力寄せたおまけモードです。すべての BMS / BMSON ファイルの `JUDGERANK` と `TOTAL` を無視して強制的に適用されるため、使用時は別セーブデータの作成を強く推奨します。[専用IR（rianIR）](https://bms-atelier-kyokufu.blogspot.com/2026/03/rian-ir.html) にも対応しています。

### 7. oraja_helper との連携機能強化
beatoraja 向けヘルパーツール「oraja_helper」開発者である、かた（[@cold_planet_](https://x.com/cold_planet_)）様のご協力により、本カスタムビルド版と [oraja_helper](https://x.com/cold_planet_) を併用した際の便利な連携機能を実装しています。

---

## Endless Dream 本体の主要機能

* **ゲーム内楽曲ダウンローダー**
* **LR2 GBATTLE 対応**
* **osu! ファイル対応**
* **プレイ中のリアルタイム レート変更・周波数変更 (Rate Mod / Freq)**
* **libgdx の最新グラフィックバックエンド採用による描画パフォーマンスの向上**
* **難易度表（Table）の高速処理**
* **beatoraja 0.8.8 環境との高い互換性**
* **内蔵 Mod メニュー**（**`F5` または `Insert`** キーでアクセス可能）

> [!CAUTION]
> 許諾されていない著作権コンテンツのプレイに本アプリケーションを使用しないでください。

---

## ダウンロード

> [!NOTE]
> 必要な Java バージョンが Java 8 から **Java 17** に変更されました。環境に合わせて Java バージョンを更新してください。

### ダウンロードリンク
- [**Windows 版ダウンロード**](https://github.com/rian-eimu/lr2oraja-endlessdream/releases/download/1.3.0/EndlessDream-rian-windows.zip)
- [**Linux 版ダウンロード**](https://github.com/rian-eimu/lr2oraja-endlessdream/releases/download/1.3.0/EndlessDream-rian-linux.zip)
- [**macOS 版ダウンロード (Apple Silicon)**](https://github.com/rian-eimu/lr2oraja-endlessdream/releases/download/1.3.0/EndlessDream-rian-macos.zip)
- [**macOS 版ダウンロード (Intel)**](https://github.com/rian-eimu/lr2oraja-endlessdream/releases/download/1.3.0/EndlessDream-rian-macos.zip)

コミットごとの開発ビルドは [Releases](https://github.com/rian-eimu/lr2oraja-endlessdream/releases) にて公開されています。

---

## インストール手順

### ゼロから新規導入する場合

1. 最新の [`beatoraja-0.8.8 JRE 同梱版`](https://mocha-repository.info/download/beatoraja0.8.8-jre-win64.zip) をダウンロードします。
2. ダウンロードした zip ファイルを展開します。
3. 展開したフォルダを、PC 内の任意のアプリケーション配置ディレクトリに移動します。
4. [リリース一覧](https://github.com/rian-eimu/lr2oraja-endlessdream/releases) から、お使いの OS に対応した最新の Endless Dream-rian をダウンロードします。
5. beatoraja フォルダ内の既存の `beatoraja.jar` を削除します。
6. 手順 4 でダウンロードした `lr2oraja.*.jar` を beatoraja フォルダ内にコピーします。


### 既存の beatoraja 環境から導入する場合

1. 既存の beatoraja フォルダをコピーします。
2. コピーしたフォルダ名を `endless-dream` などに変更します。
3. [リリース一覧](https://github.com/rian-eimu/lr2oraja-endlessdream/releases) から、お使いの OS に対応した最新の Endless Dream-rian をダウンロードします。
4. 作成した `endless-dream` フォルダ内の既存の `beatoraja.jar` を削除します。
5. 手順 3 でダウンロードした `lr2oraja.*.jar` をフォルダ内にコピーします。

### インストール後の参考情報
LR2oraja Endless Dream の環境構築が完了したら、スキン・楽曲の導入方法や難易度表の使い方などが詳しく解説されている [Beatoraja English Guide](https://github.com/wcko87/beatoraja-english-guide/wiki) を参照することをお勧めします。

---

## BMS-IR / rianIR 互換性

[BMS-IR](https://www.bms-ir.org/) に接続する際は、LR2oraja Endless Dream 向けの BMS-IR プラグインビルドを使用してください。本プラグインはホスト jar のハッシュおよび `client_kind=lr2oraja-ed` を送信し、BMS-IR 側で LR2oraja ED バケットとして識別されます。

BMS-IR プラグイン `0.0.33` 以降では、開催中の BMS-IR スコアアタック（1曲コース）向けの primary-IR テーブル配信に対応しています。これらのコースを選曲画面に表示させたい場合は、BMS-IR を primary IR に設定してください。

また、バージョン `0.0.33` では BMS-IR の ID/パスワード不一致時に読み取り専用モードへフォールバックせずログイン失敗として正しく通知されるほか、BMS-IR ランキング XML から EX HARD などの拡張クリアタイプを保持できるため、`0.0.33` 以降の使用を推奨します。

公開 BMS-IR の許可リスト（Allowlist）登録には、バージョン指定されたリリース版 jar の MD5 / SHA-256 ハッシュを共有してください。ローカルでビルドした jar はハッシュ値が異なる場合があるため、安定した許可リスト用としては適していません。

> [!NOTE]
> DX MODE のスコアは、BMS-IR 互換性を保つため非 rianIR（BMS-IR等）への送信から自動的に除外されます（`IRUtil.shouldSkipIR` 仕様準拠）。DX MODE のスコア送信には [rianIR](https://bms-atelier-kyokufu.blogspot.com/2026/03/rian-ir.html) をご利用ください。

---

## ソースコードからのビルド

ビルドおよび実行には **JavaFX 同梱の JDK 17** が必要です。[Liberica JDK](https://bell-sw.com/pages/downloads/#jdk-17-lts) などの利用を検討し、ダウンロード時は必ず `Package: Full JDK`（JavaFX同梱版）を選択してください。

サブモジュールを含めて本リポジトリをクローンします：
```sh
git clone --recurse-submodules https://github.com/rian-eimu/lr2oraja-endlessdream.git
```

お使いの OS に合わせて Gradle Wrapper を実行し、[Gradle システムプロパティ](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_system_properties) で対象プラットフォームを指定します：

**Windows:**
```powershell
.\gradlew.bat core:shadowJar -Dplatform=windows
```
**Linux:**
```sh
./gradlew core:shadowJar -Dplatform=linux
```
**macOS:**
```sh
./gradlew core:shadowJar -Dplatform=macos
```

> [!NOTE]
> `ARM` 環境（Apple Silicon 等）の場合は `-Darch=aarch64` を追加してください。

ビルドが完了すると、`dist/` ディレクトリ内にゲーム環境で使用可能な jar ファイルが生成されます。

---

## Endless Dream の開発とコントリビューション

開発には [IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/download/other.html) などの IDE の利用を推奨します。

### プロジェクトへのコントリビューション
開発を始める際は、まず [リポジトリをフォーク](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/fork-a-repo) し、新しいブランチを作成してコードを記述してください。

作業が完了したらメインプロジェクトに対して [プルリクエスト (PR) を作成](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/creating-a-pull-request-from-a-fork) してください。メンテナーがコードをレビューします。

作業着手前に、Issue を作成しアイデアを相談・共有することをお勧めします。

### 実行とデバッグ
Gradle の `core:runShadow` タスクを使用すると、変更内容を素早くテスト・デバッグできます。`core:shadowJar` タスクは各 OS 向けの配布用 jar をビルドします。**デフォルトの Gradle run タスクは動作しないため使用しないでください。**

<img width="358" height="281" alt="Gradle タスク一覧" src="https://github.com/user-attachments/assets/0adcd7e7-724f-4653-a1b0-e1a637f623f0" />

### 既存の beatoraja 環境から実行する場合
システムプロパティ `runDir` に既存の beatoraja インストール先パスを設定します。これを設定しない場合、Git プロジェクト内の `assets/` フォルダが実行ディレクトリになります。

ウィンドウバーの実行構成パネル横の「3つの点」をクリックし、`runShadow` の構成を編集して `-DrunDir="[beatorajaの絶対パス]"` を追加します。

<img width="1389" height="321" alt="runDir 設定例" src="https://github.com/user-attachments/assets/3dd096b7-6995-4ab7-b7e9-8a18e038dc83" />

IR 依存の変更をテストしたい場合は、実行構成に `-DuseIR=true` を追加してください（※プロパティに関する [既知のIssue](https://github.com/seraxis/lr2oraja-endlessdream/issues/189) にご留意ください）。

### サブモジュールのシンボルが解決できない場合
IntelliJ がプロジェクトのサブモジュールを認識できず、クラス（`bms.model.*` など）が見つからないエラーが表示されることがあります（ビルドや実行自体には影響しませんが、エディタ上で警告が出ます）。

1. まず `./core/dependencies` に `jbms-parser` および `jbmstable-parser` フォルダが存在することを確認します。見つからない場合は `git submodule update --init --recursive` を実行してサブモジュールを取得してください。
2. サブモジュールが存在する場合は、`File --> Project Structure --> Modules --> core --> Dependencies --> Add --> JARs or Directories...` を開き、`./core/dependencies/jbms-parser` と `./core/dependencies/jbmstable-parser` の両方を追加して Apply をクリックします。

<img width="1687" height="321" alt="Project Structure 設定例" src="https://github.com/user-attachments/assets/dd1b9d41-d1e6-42db-9139-8adbcada1014" />

設定完了後、インポート関連のエラーが解消されます。解決しない場合は [Discord サーバー](https://discord.gg/HutCHCZHns) でご質問ください。

### コマンドラインからの実行
ソースコードのビルドと同様に、コマンドラインから直接実行することも可能です：

**Windows:**
```powershell
.\gradlew.bat core:runShadow -Dplatform=windows -DrunDir="C:\beatoraja0.8.8" -DuseIR=true
```
