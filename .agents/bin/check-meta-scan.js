#!/usr/bin/env node
const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const { checkHelp } = require("./common");

// ============================================================================
// Constants & Configuration
// ============================================================================

const THRESHOLD_FILES = 10;
const THRESHOLD_LOC = 2000;
const THRESHOLD_SIZE = 100 * 1024; // 100KB
const MAX_READ_SIZE = 10 * 1024 * 1024; // 10MB

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

const HELP_TEXT = `
check-meta-scan.js - メタスキャン要否判定ツール

【概要】
  レビュー対象のファイル数・コード行数（LOC）・変更差分サイズ等を高速解析し、
  Flashモデルによるメタスキャン（分割レビュー）が推奨される規模であるかを判定します。

【使用法】
  node .agents/bin/check-meta-scan.js [対象パス] [オプション]

【オプション】
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

// ============================================================================
// File Discovery & Metrics
// ============================================================================

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
  } catch (_) {}
  return fileList;
}

function getFileMetrics(filePath) {
  let size = 0;
  let loc = 0;
  try {
    const stat = fs.statSync(filePath);
    size = stat.size;

    if (stat.isFile() && size < MAX_READ_SIZE) {
      const content = fs.readFileSync(filePath, "utf8");
      if (!content.includes("\u0000")) {
        loc = content.split(/\r?\n/).length;
      }
    }
  } catch (_) {}
  return { size, loc };
}

function getFilesToScan() {
  const args = process.argv.slice(2);
  const targets = args.filter((arg) => !arg.startsWith("-") && !arg.startsWith("/"));

  if (targets.length > 0) {
    let allFiles = [];
    targets.forEach((t) => {
      const resolved = path.resolve(t);
      allFiles = allFiles.concat(getFilesRecursively(resolved));
    });
    return { mode: "TARGET_PATH", files: allFiles, targetName: targets.join(", ") };
  }

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
  } catch (_) {}

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

  rules.forEach((rule) => {
    if (rule.check(result)) {
      result.yesCount++;
      result.reasons.push(rule.message(result));
    }
  });

  return result;
}

// ============================================================================
// Main Execution
// ============================================================================

function main() {
  checkHelp(HELP_TEXT);

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

if (require.main === module) {
  main();
}

module.exports = {
  getFilesRecursively,
  getFileMetrics,
  getFilesToScan,
  analyzeFiles,
};
