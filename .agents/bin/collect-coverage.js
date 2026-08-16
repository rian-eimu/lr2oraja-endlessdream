#!/usr/bin/env node
const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const {
  checkHelp,
  resolveSessionId,
  ensureDirSync,
} = require("./common");

// ============================================================================
// CLI & Help Options Parsing
// ============================================================================

const HELP_TEXT = `
collect-coverage.js - 自動テスト実行＆カバレッジ収集ツール

【概要】
  ワークスペース内の主要テストフレームワーク（.NET, Node.js/TypeScript, Python, Go, Rust等）を自動検出し、
  テストを実行してカバレッジレポート（Cobertura XML, LCOV, JSON等）をパースし、
  コードレビュー用の統一フォーマット JSON (./tmp/code-reviews/{SSS}-coverage.json) を出力します。

【使用法】
  node .agents/bin/collect-coverage.js [オプション]

【オプション】
  -s, --session-id <ID>   セッションID（例: 001）。未指定時は ./tmp/code-reviews/ から自動推定
  -o, --output <PATH>     出力先JSONファイルパス（デフォルト: ./tmp/code-reviews/{SSS}-coverage.json）
  --dry-run               テストを実行せず、検出されたフレームワークと実行予定コマンドのみを表示
  --skip-run              テストを実行せず、すでに生成済みのカバレッジレポートファイルのパースのみ実行
  -f, --force             既存のカバレッジJSONが存在する場合も強制的に再計測・上書き
  -h, --help, /?, /help   このヘルプメッセージを表示

【対応フレームワーク・言語】
  - .NET / C#         : xUnit, NUnit, MSTest + Coverlet (dotnet test --collect:"XPlat Code Coverage")
  - JavaScript / TS   : Vitest, Jest, NYC/c8, Mocha (npm test -- --coverage)
  - Python            : pytest (pytest --cov=. --cov-report=json)
  - Go                : go test (go test -coverprofile=...)
  - Rust              : cargo-tarpaulin / cargo test

【出力フォーマット例】
  {
    "status": "success",
    "session_id": "001",
    "framework": ".NET (xUnit / Coverlet)",
    "overall_coverage": "85.4%",
    "summary": { "lines_covered": 1250, "lines_total": 1463 },
    "files": {
      "src/PrepareData.cs": "92.3%",
      "src/Models/Builder.cs": "78.0%"
    }
  }
`;

function parseCliArgs() {
  checkHelp(HELP_TEXT);

  const rawArgs = process.argv.slice(2);
  const result = {
    sessionId: "",
    outputPath: "",
    dryRun: false,
    skipRun: false,
    force: false,
  };

  for (let i = 0; i < rawArgs.length; i++) {
    const arg = rawArgs[i];
    if (arg === "-s" || arg === "--session-id") {
      result.sessionId = rawArgs[++i] || "";
    } else if (arg.startsWith("--session-id=")) {
      result.sessionId = arg.split("=")[1];
    } else if (arg === "-o" || arg === "--output") {
      result.outputPath = rawArgs[++i] || "";
    } else if (arg.startsWith("--output=")) {
      result.outputPath = arg.split("=")[1];
    } else if (arg === "--dry-run") {
      result.dryRun = true;
    } else if (arg === "--skip-run") {
      result.skipRun = true;
    } else if (arg === "-f" || arg === "--force") {
      result.force = true;
    }
  }

  return result;
}

// ============================================================================
// Framework Detectors
// ============================================================================

function detectDotnet(rootDir) {
  const hasSln = fs.existsSync(rootDir) && fs.readdirSync(rootDir).some((f) => f.endsWith(".sln"));
  const findCsproj = (dir, depth = 0) => {
    if (depth > 3 || !fs.existsSync(dir)) return [];
    let list = [];
    try {
      const items = fs.readdirSync(dir);
      for (const item of items) {
        if (item === "bin" || item === "obj" || item === "node_modules" || item === ".git") continue;
        const full = path.join(dir, item);
        const stat = fs.statSync(full);
        if (stat.isDirectory()) {
          list = list.concat(findCsproj(full, depth + 1));
        } else if (item.endsWith(".csproj")) {
          list.push(full);
        }
      }
    } catch (_) {}
    return list;
  };

  const csprojs = findCsproj(rootDir);
  if (hasSln || csprojs.length > 0) {
    let frameworkName = ".NET";
    let isCoverlet = false;
    for (const cp of csprojs) {
      try {
        const content = fs.readFileSync(cp, "utf-8");
        if (/coverlet/i.test(content)) isCoverlet = true;
        if (/xunit/i.test(content)) frameworkName = ".NET (xUnit)";
        else if (/nunit/i.test(content)) frameworkName = ".NET (NUnit)";
        else if (/mstest/i.test(content)) frameworkName = ".NET (MSTest)";
      } catch (_) {}
    }
    if (isCoverlet) frameworkName += " + Coverlet";

    let testCommand = 'dotnet test --collect:"Code Coverage;Format=Cobertura" --results-directory ./TestResults';
    if (isCoverlet) {
      testCommand = 'dotnet test --collect:"XPlat Code Coverage" --results-directory ./TestResults';
    }

    return {
      name: frameworkName,
      type: "dotnet",
      testCommand,
      findCoverageFiles: () => {
        const results = [];
        const searchDir = path.join(rootDir, "TestResults");
        if (!fs.existsSync(searchDir)) return results;
        const findXml = (d, depth = 0) => {
          if (depth > 4) return;
          try {
            for (const f of fs.readdirSync(d)) {
              const full = path.join(d, f);
              if (fs.statSync(full).isDirectory()) findXml(full, depth + 1);
              else if (
                f.endsWith(".cobertura.xml") ||
                f.endsWith(".coveragexml") ||
                f.endsWith(".xml")
              ) {
                results.push(full);
              }
            }
          } catch (_) {}
        };
        findXml(searchDir);
        return results;
      },
    };
  }
  return null;
}

function detectNode(rootDir) {
  const pkgPath = path.join(rootDir, "package.json");
  if (!fs.existsSync(pkgPath)) return null;

  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf-8"));
    const allDeps = {
      ...(pkg.dependencies || {}),
      ...(pkg.devDependencies || {}),
    };
    const scripts = pkg.scripts || {};

    let name = "Node.js (Generic)";
    let testCommand = "npm test -- --coverage";

    if (allDeps.vitest || (scripts.test && scripts.test.includes("vitest"))) {
      name = "Node.js (Vitest)";
      testCommand = "npx vitest run --coverage";
    } else if (allDeps.jest || (scripts.test && scripts.test.includes("jest"))) {
      name = "Node.js (Jest)";
      testCommand = "npx jest --coverage --watchAll=false";
    } else if (allDeps.c8 || allDeps.nyc) {
      name = "Node.js (NYC/c8)";
      testCommand = "npm test";
    }

    return {
      name,
      type: "node",
      testCommand,
      findCoverageFiles: () => {
        const files = [];
        const candidates = [
          path.join(rootDir, "coverage", "coverage-summary.json"),
          path.join(rootDir, "coverage", "coverage-final.json"),
          path.join(rootDir, "coverage", "clover.xml"),
          path.join(rootDir, "coverage", "cobertura-coverage.xml"),
          path.join(rootDir, "coverage", "lcov.info"),
        ];
        for (const c of candidates) {
          if (fs.existsSync(c)) files.push(c);
        }
        return files;
      },
    };
  } catch (_) {
    return null;
  }
}

function detectPython(rootDir) {
  const hasPy =
    fs.existsSync(path.join(rootDir, "pytest.ini")) ||
    fs.existsSync(path.join(rootDir, "pyproject.toml")) ||
    fs.existsSync(path.join(rootDir, "setup.py")) ||
    fs.existsSync(path.join(rootDir, "tox.ini"));

  if (hasPy) {
    return {
      name: "Python (pytest / coverage)",
      type: "python",
      testCommand: "pytest --cov=. --cov-report=json:coverage.json --cov-report=term",
      findCoverageFiles: () => {
        const files = [];
        const candidates = [
          path.join(rootDir, "coverage.json"),
          path.join(rootDir, "coverage.xml"),
          path.join(rootDir, ".coverage"),
        ];
        for (const c of candidates) {
          if (fs.existsSync(c)) files.push(c);
        }
        return files;
      },
    };
  }
  return null;
}

function detectGo(rootDir) {
  const hasGo = fs.existsSync(path.join(rootDir, "go.mod"));
  if (hasGo) {
    return {
      name: "Go (go test)",
      type: "go",
      testCommand: "go test -coverprofile=coverage.out ./...",
      findCoverageFiles: () => {
        const covPath = path.join(rootDir, "coverage.out");
        return fs.existsSync(covPath) ? [covPath] : [];
      },
    };
  }
  return null;
}

function detectRust(rootDir) {
  const hasCargo = fs.existsSync(path.join(rootDir, "Cargo.toml"));
  if (hasCargo) {
    return {
      name: "Rust (Cargo)",
      type: "rust",
      testCommand: "cargo test",
      findCoverageFiles: () => {
        const tarpaulin = path.join(rootDir, "tarpaulin-report.json");
        return fs.existsSync(tarpaulin) ? [tarpaulin] : [];
      },
    };
  }
  return null;
}

function detectFramework(rootDir) {
  return (
    detectDotnet(rootDir) ||
    detectNode(rootDir) ||
    detectPython(rootDir) ||
    detectGo(rootDir) ||
    detectRust(rootDir)
  );
}

// ============================================================================
// Coverage Parsers
// ============================================================================

function parseCoberturaXml(xmlContent) {
  const files = {};
  let totalLines = 0;
  let coveredLines = 0;

  const classMatches = xmlContent.matchAll(/<class\s+([^>]+)>/g);
  for (const match of classMatches) {
    const attrs = match[1];
    const filenameMatch = attrs.match(/filename="([^"]+)"/);
    const lineRateMatch = attrs.match(/line-rate="([^"]+)"/);

    if (filenameMatch && lineRateMatch) {
      const filename = filenameMatch[1].replace(/\\/g, "/");
      const rate = parseFloat(lineRateMatch[1]) * 100;
      files[filename] = `${rate.toFixed(1)}%`;
    }
  }

  const overallMatch = xmlContent.match(/<coverage[^>]+line-rate="([^"]+)"/);
  const overallRate = overallMatch ? `${(parseFloat(overallMatch[1]) * 100).toFixed(1)}%` : "N/A";

  const linesValidMatch = xmlContent.match(/lines-valid="(\d+)"/);
  const linesCoveredMatch = xmlContent.match(/lines-covered="(\d+)"/);
  if (linesValidMatch && linesCoveredMatch) {
    totalLines = parseInt(linesValidMatch[1], 10);
    coveredLines = parseInt(linesCoveredMatch[1], 10);
  }

  return {
    overallRate,
    summary: { lines_covered: coveredLines, lines_total: totalLines },
    files,
  };
}

function parseJsonSummary(jsonContent) {
  try {
    const data = JSON.parse(jsonContent);
    const files = {};

    let totalLines = 0;
    let coveredLines = 0;
    let overallRate = "N/A";

    if (data.total && data.total.lines) {
      overallRate = `${data.total.lines.pct.toFixed(1)}%`;
      totalLines = data.total.lines.total;
      coveredLines = data.total.lines.covered;
    }

    for (const [key, val] of Object.entries(data)) {
      if (key === "total") continue;
      if (val && val.lines && typeof val.lines.pct === "number") {
        const normKey = key.replace(/\\/g, "/");
        files[normKey] = `${val.lines.pct.toFixed(1)}%`;
      }
    }

    return {
      overallRate,
      summary: { lines_covered: coveredLines, lines_total: totalLines },
      files,
    };
  } catch (_) {
    return null;
  }
}

function parseCoverage(coverageFiles) {
  for (const file of coverageFiles) {
    try {
      const content = fs.readFileSync(file, "utf-8");
      if (file.endsWith(".json")) {
        const res = parseJsonSummary(content);
        if (res) return res;
      } else if (file.endsWith(".xml") || content.includes("<coverage")) {
        const res = parseCoberturaXml(content);
        if (res) return res;
      }
    } catch (_) {}
  }

  return {
    overallRate: "N/A",
    summary: { lines_covered: 0, lines_total: 0 },
    files: {},
  };
}

// ============================================================================
// Main Execution
// ============================================================================

function main() {
  const args = parseCliArgs();
  const rootDir = process.cwd();
  const sessionId = resolveSessionId(args.sessionId, rootDir);

  const defaultOutPath = path.join(
    rootDir,
    "tmp",
    "code-reviews",
    `${sessionId}-coverage.json`,
  );
  const outPath = args.outputPath || defaultOutPath;

  console.log(`[collect-coverage] Session ID : ${sessionId}`);
  console.log(`[collect-coverage] Output Path: ${outPath}`);

  const detected = detectFramework(rootDir);

  if (!detected) {
    console.log(`[collect-coverage] ⚠️ テストフレームワークが自動検出されませんでした。`);
    const emptyResult = {
      status: "unavailable",
      session_id: sessionId,
      framework: "None",
      reason: "No recognized test framework found in workspace.",
      execution_date: new Date().toISOString(),
      overall_coverage: "N/A",
      summary: { lines_covered: 0, lines_total: 0 },
      files: {},
    };

    if (!args.dryRun) {
      ensureDirSync(path.dirname(outPath));
      fs.writeFileSync(outPath, JSON.stringify(emptyResult, null, 2), "utf-8");
      console.log(`[collect-coverage] 空のカバレッジ情報を保存しました: ${outPath}`);
    }
    return;
  }

  console.log(`[collect-coverage] 🎯 検出フレームワーク: ${detected.name}`);
  console.log(`[collect-coverage] ⚡ 実行予定コマンド : ${detected.testCommand}`);

  if (args.dryRun) {
    console.log(`[collect-coverage] [DRY RUN] テスト実行をスキップしました。`);
    return;
  }

  if (!args.skipRun) {
    console.log(`[collect-coverage] 🚀 テストを実行してカバレッジを計測中...`);
    try {
      execSync(detected.testCommand, {
        cwd: rootDir,
        stdio: "inherit",
        timeout: 180000,
      });
      console.log(`[collect-coverage] ✅ テスト実行が完了しました。`);
    } catch (err) {
      console.warn(`[collect-coverage] ⚠️ テストコマンドの実行中に警告またはエラーが発生しました（一部失敗の可能性）: ${err.message}`);
    }
  } else {
    console.log(`[collect-coverage] ⏭️ --skip-run が指定されたため、既存レポートのパースのみ行います。`);
  }

  const coverageFiles = detected.findCoverageFiles();
  console.log(`[collect-coverage] 🔍 カバレッジファイルを検索: ${coverageFiles.length} 件検出`);
  for (const cf of coverageFiles) {
    console.log(`   - ${path.relative(rootDir, cf)}`);
  }

  const parsed = parseCoverage(coverageFiles);

  const finalResult = {
    status: parsed.overallRate !== "N/A" ? "success" : "partial",
    session_id: sessionId,
    framework: detected.name,
    execution_date: new Date().toISOString(),
    overall_coverage: parsed.overallRate,
    summary: parsed.summary,
    files: parsed.files,
  };

  ensureDirSync(path.dirname(outPath));
  fs.writeFileSync(outPath, JSON.stringify(finalResult, null, 2), "utf-8");

  console.log(`[collect-coverage] 📊 全体カバレッジ実測値: ${finalResult.overall_coverage}`);
  console.log(`[collect-coverage] 💾 カバレッジ結果を保存しました -> ${outPath}`);
}

if (require.main === module) {
  main();
}

module.exports = {
  detectFramework,
  parseCoberturaXml,
  parseJsonSummary,
  parseCoverage,
};
