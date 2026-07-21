#!/usr/bin/env node
const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

// 判定閾値
const THRESHOLD_FILES = 10;
const THRESHOLD_LOC = 2000;
const THRESHOLD_SIZE = 100 * 1024; // 100KB
const MAX_READ_SIZE = 10 * 1024 * 1024; // 10MB (読み込み制限)

// スキャンから除外するディレクトリ・ファイル名のリスト
const EXCLUDE_DIRS = new Set([
  ".git",
  "node_modules",
  "bin",
  "obj",
  "dist",
  "tmp",
  ".vscode",
  ".agents",
  ".git-credentials",
  ".cache",
]);

// 再帰的にファイルを収集する関数
function getFilesRecursively(dir, fileList = []) {
  if (!fs.existsSync(dir)) return fileList;
  try {
    const stat = fs.statSync(dir);
    if (stat.isFile()) {
      fileList.push(dir);
      return fileList;
    }

    const files = fs.readdirSync(dir);
    for (const file of files) {
      if (EXCLUDE_DIRS.has(file)) continue;

      const filePath = path.join(dir, file);
      const fileStat = fs.statSync(filePath);

      if (fileStat.isDirectory()) {
        getFilesRecursively(filePath, fileList);
      } else {
        fileList.push(filePath);
      }
    }
  } catch (e) {
    // Permission denied or locked files
  }
  return fileList;
}

// ファイルのメトリクス（サイズとLOC）を取得する関数
function getFileMetrics(filePath) {
  let size = 0;
  let loc = 0;
  try {
    const stat = fs.statSync(filePath);
    size = stat.size;

    // 巨大すぎるファイルやバイナリの読み込みを防止
    if (stat.isFile() && size < MAX_READ_SIZE) {
      const content = fs.readFileSync(filePath, "utf8");
      // 簡易バイナリチェック（ヌル文字が含まれている場合は行数カウントから除外）
      if (!content.includes("\u0000")) {
        loc = content.split(/\r?\n/).length;
      }
    }
  } catch (e) {
    // ignore read errors
  }
  return { size, loc };
}

// スキャン対象ファイルを決定するメイン処理
function getFilesToScan() {
  const args = process.argv.slice(2);
  const targets = args.filter((arg) => !arg.startsWith("-"));

  // 1. 引数にディレクトリやファイルのパスが指定されている場合
  if (targets.length > 0) {
    let allFiles = [];
    targets.forEach((t) => {
      const resolved = path.resolve(t);
      allFiles = allFiles.concat(getFilesRecursively(resolved));
    });
    return { mode: "TARGET_PATH", files: allFiles, targetName: targets.join(", ") };
  }

  // 2. 引数がなく、かつGitの差分がある場合
  try {
    const filesStr = execSync("git diff --name-only", {
      encoding: "utf8",
      stdio: ["pipe", "pipe", "ignore"],
    }).trim();
    if (filesStr) {
      const files = filesStr
        .split(/\r?\n/)
        .map((f) => path.resolve(f))
        .filter((f) => fs.existsSync(f));
      if (files.length > 0) {
        return { mode: "GIT_DIFF", files, targetName: "Git Diff 差分" };
      }
    }
  } catch (err) {
    // Git環境でない場合はWorkspaceモードにフォールバック
  }

  // 3. 引数がなく、かつGitの差分もない場合（ソリューション全体）
  const rootDir = process.cwd();
  const files = getFilesRecursively(rootDir);
  return { mode: "WORKSPACE", files, targetName: "ソリューション全体" };
}

function analyzeFiles() {
  const { mode, files, targetName } = getFilesToScan();

  const result = {
    mode,
    targetName,
    filesCount: files.length,
    totalLoc: 0,
    totalSize: 0,
    hasSubdirs: false,
    yesCount: 0,
    reasons: [],
  };

  if (files.length === 0) {
    result.reasons.push("対象ファイルが見つかりません。");
    return result;
  }

  files.forEach((f) => {
    // 物理ディレクトリ階層が2層以上か（相対パスで検証）
    const relPath = path.relative(process.cwd(), f);
    const normalizedPath = relPath.replace(/\\/g, "/");
    const parts = normalizedPath.split("/");
    if (parts.length > 2) {
      result.hasSubdirs = true;
    }

    const { size, loc } = getFileMetrics(f);
    result.totalSize += size;
    result.totalLoc += loc;
  });

  // 判定ルール定義
  const rules = [
    {
      check: (res) => res.filesCount >= THRESHOLD_FILES,
      message: (res) => `対象ファイル数が ${res.filesCount} (閾値: ${THRESHOLD_FILES})`,
    },
    {
      check: (res) => res.totalLoc >= THRESHOLD_LOC,
      message: (res) => `総行数が ${res.totalLoc} 行 (閾値: ${THRESHOLD_LOC})`,
    },
    {
      check: (res) => res.totalSize >= THRESHOLD_SIZE,
      message: (res) => `総ファイルサイズが ${(res.totalSize / 1024).toFixed(1)} KB (閾値: 100 KB)`,
    },
    {
      check: (res) => res.hasSubdirs,
      message: () => "物理ディレクトリ階層が2層以上に分かれています (複雑な依存関係の可能性)",
    },
  ];

  // ルールの評価
  rules.forEach((rule) => {
    if (rule.check(result)) {
      result.yesCount++;
      result.reasons.push(rule.message(result));
    }
  });

  return result;
}

function main() {
  const result = analyzeFiles();

  console.log(`MODE: ${result.mode} (${result.targetName})`);

  if (result.filesCount === 0) {
    console.log("STATUS: SKIP");
    console.log("変更またはスキャン対象ファイルが存在しないため、判定をスキップします。");
    return;
  }

  if (result.yesCount > 0) {
    console.log("STATUS: RECOMMENDED");
    console.log(`メタスキャン推奨理由 (検出数: ${result.yesCount}):`);
    result.reasons.forEach((r) => console.log(`- ${r}`));
  } else {
    console.log("STATUS: NOT_RECOMMENDED");
    console.log(
      `スキャン対象 (${result.targetName}) は小規模（Yes判定が0個）であるため、メタスキャン（分割レビュー）は不要と考えられます。`,
    );
    console.log(
      "通常の一括レビュープロンプト（workflows/code-review.prompt.md）の利用を推奨します。",
    );
    console.log("---");
    console.log(
      "このままメタスキャン（分割レビュー）を続行しますか？続行する場合は「はい」または「y」と入力してください。",
    );
  }
}

main();
