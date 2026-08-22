#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { parseArgs } = require("node:util");
const {
  checkHelp,
  ensureDirSync,
  getFormattedDate,
  cleanJsonString,
  extractFilenamesFromString,
} = require("./common");

// ============================================================================
// 1. CLI Arguments & Help
// ============================================================================

const HELP_TEXT = `
merge-code-reviews.js - 分割レビュー結果マージツール

【概要】
  ./tmp/code-reviews/ 内のフェーズ別レビュー結果（{SSS}-phase*-review_*.md）を集約し、
  品質スコアの乗算算定および総合品質レポート（{SSS}-integrated-review-report_*.md）を出力します。

【使用法】
  node .agents/bin/merge-code-reviews.js [オプション]

【オプション】
  -s, --session-id <ID>   セッションID（例: 001）。デフォルト: 001
  -d, --date <YYYYMMDD>   レポート日付（例: 20260815）。未指定時は当日日付
  --dir <PATH>            レビュー結果格納ディレクトリ。デフォルト: tmp/code-reviews
  -g, --gate <1|2|3>      適用品質ゲート（1: 60点, 2: 80点, 3: 90点）。デフォルト: 1
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

function parseArguments() {
  checkHelp(HELP_TEXT);

  const options = {
    "session-id": { type: "string", short: "s", default: "001" },
    date: { type: "string", short: "d", default: "" },
    dir: { type: "string", default: "tmp/code-reviews" },
    gate: { type: "string", short: "g", default: "1" },
  };

  const { values } = parseArgs({ options, strict: false });
  const gateLevel = parseInt(values.gate, 10) || 1;

  let dateStr = values.date;
  if (!dateStr) {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    dateStr = `${yyyy}${mm}${dd}`;
  }

  return {
    sessionId: values["session-id"].padStart(3, "0"),
    date: dateStr,
    reviewDir: values.dir,
    gateLevel,
  };
}

// ============================================================================
// 2. Review Metrics & Quality Evaluation
// ============================================================================

class ReviewMetrics {
  constructor(meta) {
    this.isExcluded =
      meta.na === true ||
      meta.na === "true" ||
      meta.exclude === true ||
      meta.exclude === "true";
    if (this.isExcluded) return;

    this.robustFatal = ((meta.robustness && meta.robustness.fatal) || []).length;
    this.robustMajor = ((meta.robustness && meta.robustness.major) || []).length;
    this.respFatal = ((meta.responsibility && meta.responsibility.fatal) || []).length;
    this.respMajor = ((meta.responsibility && meta.responsibility.major) || []).length;
    this.respMinor = ((meta.responsibility && meta.responsibility.minor) || []).length;
    this.cogMajor = ((meta.cognitive && meta.cognitive.major) || []).length;
    this.cogMinor = ((meta.cognitive && meta.cognitive.minor) || []).length;
    this.riskFatal = ((meta.risk && meta.risk.fatal) || []).length;
    this.riskMajor = ((meta.risk && meta.risk.major) || []).length;
    this.roiMajor = ((meta.roi && meta.roi.major) || []).length;

    this.archPenalties = meta.architecture_penalty || [];
    this.archPenaltyCount = this.archPenalties.length;

    this.bonusPatterns = !!(meta.bonus && meta.bonus.patterns);
    this.bonusEdgeCases = !!(meta.bonus && meta.bonus.edge_cases);
  }

  getTotalFatal() {
    return this.robustFatal + this.respFatal + this.riskFatal;
  }

  getTotalMajor() {
    return (
      this.robustMajor +
      this.respMajor +
      this.cogMajor +
      this.riskMajor +
      this.roiMajor
    );
  }

  getTotalMinor() {
    return this.cogMinor + this.respMinor;
  }
}

const PENALTY_WEIGHTS = { Major: 5, Minor: 2 };
const ARCH_PENALTY_WEIGHT = 15;
const STRICT_MULTIPLIER = false;

function calculateCategorySubScores(metrics) {
  let robust = Math.max(0, 20 - metrics.robustMajor * PENALTY_WEIGHTS.Major);
  let resp = Math.max(
    0,
    20 -
      metrics.respMajor * PENALTY_WEIGHTS.Major -
      metrics.respMinor * PENALTY_WEIGHTS.Minor,
  );
  let cog = Math.max(
    0,
    20 -
      metrics.cogMajor * PENALTY_WEIGHTS.Major -
      metrics.cogMinor * PENALTY_WEIGHTS.Minor,
  );
  let risk = Math.max(0, 20 - metrics.riskMajor * PENALTY_WEIGHTS.Major);
  let roi = Math.max(0, 20 - metrics.roiMajor * PENALTY_WEIGHTS.Major);

  if (metrics.robustFatal > 0) robust = 0;
  if (metrics.respFatal > 0) resp = 0;
  if (metrics.riskFatal > 0) risk = 0;

  return { robust, resp, cog, risk, roi };
}

function getCategoryRatio(score, hasFatal) {
  if (hasFatal) return 0.0;
  const rawRatio = score / 20.0;
  return STRICT_MULTIPLIER ? rawRatio : 0.5 + 0.5 * rawRatio;
}

function calculateMultipliedScore(subScores, metrics) {
  const rRobust = getCategoryRatio(subScores.robust, metrics.robustFatal > 0);
  const rResp = getCategoryRatio(subScores.resp, metrics.respFatal > 0);
  const rCog = getCategoryRatio(subScores.cog, false);
  const rRisk = getCategoryRatio(subScores.risk, metrics.riskFatal > 0);
  const rRoi = getCategoryRatio(subScores.roi, false);

  let bonus = 0;
  if (metrics.bonusPatterns) bonus += 5;
  if (metrics.bonusEdgeCases) bonus += 5;

  const baseScore = 100.0 * rRobust * rResp * rCog * rRisk * rRoi;
  const totalScore = Math.round(Math.min(100, Math.max(0, baseScore + bonus)));

  return { totalScore, bonus };
}

function formatIssueText(metrics) {
  const f = metrics.getTotalFatal();
  const m = metrics.getTotalMajor();
  const mi = metrics.getTotalMinor();

  let text = `**F**: ${f} <br> **M**: ${m} <br> **m**: ${mi}`;
  if (f > 0) {
    text += " <br> <span style='color:red; font-weight:bold;'>[RED CARD]</span>";
  }
  return text;
}

function determineGateStatus(scoreValue, fatalCount, gateLevel) {
  if (fatalCount > 0) return "💀 FAIL";
  const passLine = gateLevel === 3 ? 90 : gateLevel === 2 ? 80 : 60;
  return scoreValue < passLine ? "🔴 REJECT" : "🟢 PASS";
}

function evaluateScore(metrics, gateLevel) {
  if (!metrics || metrics.isExcluded) {
    return {
      scoreValue: 0,
      scoreText: "N/A",
      issueText: "N/A (Excluded)",
      status: "⚪ N/A",
      isValid: false,
    };
  }

  const subScores = calculateCategorySubScores(metrics);
  const scoreResult = calculateMultipliedScore(subScores, metrics);
  const scoreValue = scoreResult.totalScore;

  return {
    isValid: true,
    robustScore: subScores.robust,
    respScore: subScores.resp,
    cogScore: subScores.cog,
    riskScore: subScores.risk,
    roiScore: subScores.roi,
    scoreValue,
    bonusScore: scoreResult.bonus,
    scoreText: `${scoreValue} / 100`,
    issueText: formatIssueText(metrics),
    status: determineGateStatus(scoreValue, metrics.getTotalFatal(), gateLevel),
  };
}

// ============================================================================
// 3. Markdown Parser Functions
// ============================================================================

const EXCLUDE_HEADER_PATTERNS = [
  "アーキテクチャ総評",
  "検証サマリー",
  "メタデータ",
  "評価プロセス",
  "検出された欠陥",
  "欠陥",
  "総合スコア",
  "脆弱性と構造 of 改善",
  "脆弱性と構造の改善",
  "テスト戦略の改善",
  "品質評価サマリー",
];

function getReviewTitleAndPhase(lines, filepath) {
  let titleLine = null;
  for (const line of lines) {
    if (/^#{1,3}\s+(.+)/.test(line)) {
      const header = line.replace(/^#{1,3}\s*/, "");
      if (!EXCLUDE_HEADER_PATTERNS.some((p) => header.includes(p))) {
        titleLine = line;
        break;
      }
    }
  }

  const filename = path.basename(filepath);
  const phaseMatch = filename.match(/phase(\d+(?:[\-_.]\d+)?)/i);
  let phaseName = phaseMatch
    ? `Phase ${phaseMatch[1].replace(/_/g, "-")}`
    : "Unknown";
  let titleClean = "No Title";

  if (titleLine) {
    const rawTitle = titleLine
      .replace(/^#{1,3}\s*/, "")
      .replace(/\s*-\s*レビュー結果\s*$/, "")
      .replace(/\s*コードレビュー結果\s*$/, "");
    const phaseHeaderMatch =
      rawTitle.match(/【(Phase\s*[\d\-_.]+)】(.*)/i) ||
      rawTitle.match(/^\[?(Phase\s*[\d\-_.]+)\]?[\s:—\-]*(.*)/i);
    if (phaseHeaderMatch) {
      phaseName = phaseHeaderMatch[1].trim();
      titleClean = phaseHeaderMatch[2].trim().replace(/^\[(.*)\]$/, "$1");
    } else {
      titleClean = rawTitle.trim();
    }
  } else {
    const planFile = path.join(
      path.dirname(filepath),
      filename.replace("-review_", "-plan_"),
    );
    if (fs.existsSync(planFile)) {
      try {
        const planLines = fs.readFileSync(planFile, "utf8").split(/\r?\n/);
        const planTitleLine = planLines.find((l) => /^#\s+(.+)/.test(l));
        const planTitleMatch =
          planTitleLine &&
          planTitleLine.match(/【Phase\s*[\d\-_.]+】\s*\[?(.*?)\]?$/i);
        if (planTitleMatch) titleClean = planTitleMatch[1].trim();
      } catch (_) {}
    }
    if (titleClean === "No Title") {
      titleClean = path.basename(filepath, ".md");
    }
  }

  titleClean = titleClean
    .replace(/^【Phase\s*[\d\-_.]+】\s*/i, "")
    .replace(/^\[(.*)\]$/, "$1");
  return { phase: phaseName, title: titleClean };
}

function getReviewTargetFiles(lines, fallbackFilename) {
  const filesList = [];
  let inFilesSection = false;

  for (const line of lines) {
    const trimmed = line.trim();

    const riskMatch = trimmed.match(
      /(?:Source of Risk|リスク箇所|対象ソース):\s*(.*)/i,
    );
    if (riskMatch) {
      filesList.push(...extractFilenamesFromString(riskMatch[1]));
      continue;
    }

    const targetFileHeaderMatch = trimmed.match(
      /^[\-\*]?\s*\**対象ファイル(?:\s*\(.*?\))?\**[:：]\s*(.*)$/i,
    );
    if (targetFileHeaderMatch) {
      const remainder = targetFileHeaderMatch[1].trim();
      const extracted = extractFilenamesFromString(remainder);
      if (extracted.length > 0) {
        filesList.push(...extracted);
        inFilesSection = false;
      } else {
        inFilesSection = true;
      }
      continue;
    }

    if (inFilesSection) {
      if (
        trimmed === "" ||
        trimmed.startsWith("#") ||
        trimmed.startsWith("---") ||
        /^[\-\*]\s*\**(?!(?:[a-zA-Z0-9_\-\.\/\\`]+\.(?:cs|ts|js|py|json|md|xaml|cpp|h)))(?:箇所|問題|評価|改善|Pro固有|総評|検証|スコア)/i.test(
          trimmed,
        )
      ) {
        if (filesList.length > 0) {
          inFilesSection = false;
          continue;
        }
      }

      const itemMatch = trimmed.match(/^[\-\*]\s*(.*?)$/);
      if (itemMatch) {
        const extracted = extractFilenamesFromString(itemMatch[1]);
        if (extracted.length > 0) {
          filesList.push(...extracted);
        }
      }
    }
  }

  const uniqueFiles = [...new Set(filesList)];
  return uniqueFiles.length > 0 ? uniqueFiles : [fallbackFilename];
}

function estimateMetricsFromContent(content) {
  const fatalMatches = content.match(/優先度[:\s]*(?:致命的|fatal)|致命的欠陥/gi) || [];
  const spotMatches = content.match(/\b(complex_layout|conditional_bloat|silent_error|io_interactions|cross_cutting)\b/gi) || [];
  const majorHeadingMatches = content.match(/###\s*\d+\.\d+/g) || [];
  const majorPriorityMatches = content.match(/優先度[:\s]*(?:高|重大|major)/gi) || [];
  const minorPriorityMatches = content.match(/優先度[:\s]*(?:低|軽微|minor)/gi) || [];

  const totalMajors = Math.max(
    majorHeadingMatches.length,
    majorPriorityMatches.length,
    spotMatches.length,
  );

  const meta = {
    robustness: {
      fatal: fatalMatches.map((_, i) => `Fatal issue ${i + 1}`),
      major: [],
    },
    responsibility: { fatal: [], major: [], minor: [] },
    cognitive: {
      major: [],
      minor: minorPriorityMatches.map((_, i) => `Minor issue ${i + 1}`),
    },
    risk: { fatal: [], major: [] },
    roi: { major: [] },
  };

  for (let i = 0; i < totalMajors; i++) {
    if (i % 3 === 0) meta.cognitive.major.push(`Cognitive issue ${i + 1}`);
    else if (i % 3 === 1) meta.responsibility.major.push(`Design issue ${i + 1}`);
    else meta.risk.major.push(`Risk issue ${i + 1}`);
  }

  return meta;
}

function fillScorePlaceholders(content, ev) {
  if (!ev.isValid) return content;
  return content
    .replace(/{{TOTAL_SCORE}}/g, ev.scoreValue)
    .replace(/{{SCORE_1}}/g, ev.robustScore)
    .replace(/{{SCORE_2}}/g, ev.respScore)
    .replace(/{{SCORE_3}}/g, ev.cogScore)
    .replace(/{{SCORE_4}}/g, ev.riskScore)
    .replace(/{{SCORE_5}}/g, ev.roiScore)
    .replace(/{{SCORE_BONUS}}/g, ev.bonusScore);
}

function extractMetadataAndEval(content, lines, gateLevel) {
  let jsonStr = null;
  let matchedValue = null;

  const jsonBlockMatch = content.match(/```json\s*([\s\S]*?)\s*```/);
  if (jsonBlockMatch) {
    jsonStr = jsonBlockMatch[1].trim();
    matchedValue = jsonBlockMatch[0];
  } else {
    for (let i = lines.length - 1; i >= 0; i--) {
      const trimmed = lines[i].trim();
      if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
          JSON.parse(cleanJsonString(trimmed));
          jsonStr = trimmed;
          matchedValue = trimmed;
          break;
        } catch (_) {}
      }
    }
  }

  let meta = null;
  let hasValidJson = false;

  if (jsonStr) {
    try {
      meta = JSON.parse(cleanJsonString(jsonStr));
      hasValidJson = true;
    } catch (err) {
      console.warn("Warning: Failed to parse JSON metadata. Estimating from content...", err.message);
    }
  }

  if (!meta) {
    meta = estimateMetricsFromContent(content);
  }

  const metrics = new ReviewMetrics(meta);
  const evaluation = evaluateScore(metrics, gateLevel);

  let contentCleaned = content;
  if (hasValidJson && matchedValue) {
    contentCleaned = content
      .replace(/##\s+メタデータ（集計システム用）\s*[\r\n]*/g, "")
      .replace(matchedValue, "");
  }

  contentCleaned = fillScorePlaceholders(contentCleaned, evaluation);

  return {
    metrics,
    evaluation,
    content: contentCleaned.trim(),
  };
}

function parseReviewReport(filepath, gateLevel) {
  const content = fs.readFileSync(filepath, "utf8");
  const lines = content.split(/\r?\n/);

  const titleInfo = getReviewTitleAndPhase(lines, filepath);
  const filesList = getReviewTargetFiles(lines, path.basename(filepath));
  const metaInfo = extractMetadataAndEval(content, lines, gateLevel);

  return {
    phase: titleInfo.phase,
    title: titleInfo.title,
    files: filesList.join("<br>"),
    content: metaInfo.content,
    metrics: metaInfo.metrics,
    evaluation: metaInfo.evaluation,
  };
}

// ============================================================================
// 4. Report Markdown Builder
// ============================================================================

function generateSummarySection(validReports) {
  let systemScoreStr = "N/A";
  let totalArchPenaltyCount = 0;
  const allArchPenalties = [];

  if (validReports.length > 0) {
    const totalScore = validReports.reduce(
      (sum, r) => sum + r.evaluation.scoreValue,
      0,
    );
    const averageScore = totalScore / validReports.length;

    for (const report of validReports) {
      totalArchPenaltyCount += report.metrics.archPenaltyCount;
      if (report.metrics.archPenalties.length > 0) {
        allArchPenalties.push(...report.metrics.archPenalties);
      }
    }

    const finalSystemScore = Math.max(
      0,
      Math.round(averageScore - totalArchPenaltyCount * ARCH_PENALTY_WEIGHT),
    );
    systemScoreStr = `${finalSystemScore} / 100`;
  }

  let systemScoreSection = "";
  if (systemScoreStr !== "N/A") {
    systemScoreSection += `### **システム全体品質スコア: ${systemScoreStr}**\n\n`;
    if (totalArchPenaltyCount > 0) {
      systemScoreSection += `#### ⚠️ アーキテクチャ大局減点 (-${
        totalArchPenaltyCount * ARCH_PENALTY_WEIGHT
      }点)\n`;
      for (const penalty of allArchPenalties) {
        systemScoreSection += `- ${penalty}\n`;
      }
      systemScoreSection += `\n`;
    }
  }

  return systemScoreSection;
}

function generateReportMarkdown(
  sessionId,
  formattedDate,
  gateLevel,
  gateLineText,
  systemScoreSection,
  reportsData,
) {
  const tableRows = reportsData.map((report) => {
    const ev = report.evaluation;
    if (ev.isValid === false && ev.scoreText === "N/A") {
      return `| **${report.phase}** | ${report.title} | *N/A* | ${ev.status} | *除外* | ${report.files} |`;
    }
    const scoreDisp =
      ev.scoreValue === 0
        ? "**<span style='color:red;'>0 / 100</span>**"
        : `**${ev.scoreText}**`;
    return `| **${report.phase}** | ${report.title} | ${scoreDisp} | **${ev.status}** | ${ev.issueText} | ${report.files} |`;
  });

  const detailsBlock = reportsData.map((report) => `---\n\n${report.content}\n`);

  return `# 統合コードレビュー・オーケストレーションレポート (Session ${sessionId})

作成日: ${formattedDate}

## 1. 品質評価サマリー (Quality Assurance Summary)

> **適用中の品質ゲート**: Level ${gateLevel} (合格ライン: **${gateLineText}** 以上)
> **自動算定アルゴリズム**: JSONメタデータに基づき、レッドカード（致命的欠陥による即時失格）、各カテゴリの乗算評価、およびシステム全体のアーキテクチャペナルティを厳格に算定しています。

${systemScoreSection}
| フェーズ | コンポーネント層 / タイトル | スコア | 判定 (Gate ${gateLevel}) | 検出欠陥件数 | 対象ファイル |
| :--- | :--- | :---: | :---: | :--- | :--- |
${tableRows.join("\n")}

* ※ **F**: Fatal(致命的), **M**: Major(重大), **m**: minor(軽微)

<br>

## 2. フェーズ別 詳細インスペクション

${detailsBlock.join("\n")}
`;
}

// ============================================================================
// 5. Main Execution Flow
// ============================================================================

function main() {
  const { sessionId, date, reviewDir, gateLevel } = parseArguments();
  const formattedDate = getFormattedDate(date);

  const resolvedReviewDir = path.resolve(process.cwd(), reviewDir);
  ensureDirSync(resolvedReviewDir);

  const files = fs.readdirSync(resolvedReviewDir);
  let pattern = new RegExp(`^${sessionId}-phase.*-review_${date}.*\\.md$`);
  let matchedFiles = files.filter((f) => pattern.test(f)).sort();

  if (matchedFiles.length === 0) {
    const anyDatePattern = new RegExp(`^${sessionId}-phase.*-review_(\\d{8}).*\\.md$`);
    const anyMatched = files.filter((f) => anyDatePattern.test(f)).sort();
    if (anyMatched.length > 0) {
      const dateMatch = anyMatched[0].match(/review_(\d{8})/);
      if (dateMatch) {
        const detectedDate = dateMatch[1];
        console.warn(
          `Notice: No files matched exact date '${date}'. Auto-detected date '${detectedDate}' from review files.`,
        );
        pattern = new RegExp(`^${sessionId}-phase.*-review_${detectedDate}.*\\.md$`);
        matchedFiles = files.filter((f) => pattern.test(f)).sort();
      }
    }
  }

  if (matchedFiles.length === 0) {
    console.error(
      `Error: No review files found matching pattern: ${sessionId}-phase*-review_*.md in ${resolvedReviewDir}`,
    );
    process.exit(1);
  }

  console.log(`Found ${matchedFiles.length} review files.`);

  const reportsData = matchedFiles
    .map((file) => {
      const filepath = path.join(resolvedReviewDir, file);
      return parseReviewReport(filepath, gateLevel);
    })
    .filter(Boolean);

  const validReports = reportsData.filter((r) => r.evaluation.isValid);
  const systemScoreSection = generateSummarySection(validReports);

  const gateLineText =
    gateLevel === 3 ? "90点" : gateLevel === 2 ? "80点" : "60点";
  const finalContent = generateReportMarkdown(
    sessionId,
    formattedDate,
    gateLevel,
    gateLineText,
    systemScoreSection,
    reportsData,
  );

  const reportPath = path.join(
    resolvedReviewDir,
    `${sessionId}-integrated-review-report_${date}.md`,
  );

  fs.writeFileSync(reportPath, finalContent, "utf8");
  console.log(
    `✅ 統合完了: ${matchedFiles.length}件の子レポートをマージし、スコアを自動計算しました。 (Gate Level: ${gateLevel})`,
  );
  console.log(`出力先: ${reportPath}`);
}

if (require.main === module) {
  main();
}

module.exports = {
  ReviewMetrics,
  calculateCategorySubScores,
  calculateMultipliedScore,
  evaluateScore,
  getReviewTitleAndPhase,
  parseReviewReport,
  generateSummarySection,
  generateReportMarkdown,
};
