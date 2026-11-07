import { existsSync, readdirSync, mkdirSync, readFileSync, openSync, readSync, closeSync } from "fs";
import { join, basename, relative } from "path";

// ============================================================================
// CLI & Help Utilities
// ============================================================================

const HELP_FLAGS = new Set([
  "--help",
  "-h",
  "/help",
  "/?",
  "-?",
  "help",
  "--h",
]);

/**
 * Checks command line arguments for any help flag and prints help text if present.
 * @param {string} helpText - The help message to display.
 */
function checkHelp(helpText) {
  for (const arg of process.argv.slice(2)) {
    if (HELP_FLAGS.has(arg.toLowerCase())) {
      console.log(helpText.trim() + "\n");
      process.exit(0);
    }
  }
}

// ============================================================================
// Session & Path Utilities
// ============================================================================

/**
 * Resolves the 3-digit session ID from CLI input or by scanning the reviews directory.
 * @param {string} specifiedId - Optional CLI session ID.
 * @param {string} [rootDir=process.cwd()] - Project root directory.
 * @returns {string} 3-digit zero-padded session ID (e.g. "001").
 */
function resolveSessionId(specifiedId, rootDir = process.cwd()) {
  if (specifiedId) {
    const num = parseInt(specifiedId, 10);
    return isNaN(num) ? specifiedId : String(num).padStart(3, "0");
  }

  const reviewDir = join(rootDir, "tmp", "code-reviews");
  if (!existsSync(reviewDir)) {
    return "001";
  }

  let maxId = 0;
  try {
    const files = readdirSync(reviewDir);
    for (const f of files) {
      const match = f.match(/^(\d{3})-/);
      if (match) {
        const id = parseInt(match[1], 10);
        if (id > maxId) maxId = id;
      }
    }
  } catch (_) {}

  return maxId > 0 ? String(maxId).padStart(3, "0") : "001";
}

/**
 * Ensures that a directory exists, creating it recursively if needed.
 * @param {string} dirPath - Absolute or relative directory path.
 */
function ensureDirSync(dirPath) {
  if (!existsSync(dirPath)) {
    mkdirSync(dirPath, { recursive: true });
  }
}

/**
 * Formats a Date object or YYYYMMDD string to YYYY-MM-DD.
 * @param {string|Date} [inputDate]
 * @returns {string} Formatted date string (YYYY-MM-DD).
 */
function getFormattedDate(inputDate) {
  if (typeof inputDate === "string" && /^\d{8}$/.test(inputDate)) {
    return inputDate.replace(/^(\d{4})(\d{2})(\d{2})$/, "$1-$2-$3");
  }
  const d = inputDate instanceof Date ? inputDate : new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

/**
 * Returns current timestamp in YYYYMMDD_HHMMSS format.
 * @param {Date} [date]
 * @returns {string}
 */
function getTimestampString(date = new Date()) {
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  const hh = String(date.getHours()).padStart(2, "0");
  const min = String(date.getMinutes()).padStart(2, "0");
  const ss = String(date.getSeconds()).padStart(2, "0");
  return `${yyyy}${mm}${dd}_${hh}${min}${ss}`;
}

// ============================================================================
// Text & JSON Sanitization Utilities
// ============================================================================

/**
 * Cleans a JSON string by removing single/multi-line comments and trailing commas.
 * @param {string} str - Raw JSON string.
 * @returns {string} Sanitized JSON string.
 */
function cleanJsonString(str) {
  if (!str) return "";
  return str
    .replace(/\/\/.*$/gm, "")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/,\s*([}\]])/g, "$1")
    .trim();
}

/**
 * Extracts unique filenames with standard source extensions from a block of text.
 * @param {string} text
 * @returns {string[]} Array of unique base filenames.
 */
function extractFilenamesFromString(text) {
  if (!text) return [];
  const fileRegex =
    /[\w\-./\\]+\.(?:cs|ts|js|jsx|tsx|py|json|md|xaml|xml|cpp|h|hpp|java|go|rs|html|css|scss|vue|svelte)/gi;
  const matches = text.match(fileRegex) || [];
  const results = [];

  for (const m of matches) {
    const base = basename(m.replace(/[`\*]/g, "").trim());
    if (base && !results.includes(base)) {
      results.push(base);
    }
  }
  return results;
}

// ============================================================================
// File Filtering Utilities
// ============================================================================

/**
 * Checks if a file is likely binary by looking for null bytes in the first 512 bytes.
 * @param {string} filePath 
 * @returns {boolean}
 */
function isBinaryFileSync(filePath) {
  try {
    const fd = openSync(filePath, "r");
    const buffer = Buffer.alloc(512);
    const bytesRead = readSync(fd, buffer, 0, 512, 0);
    closeSync(fd);
    
    for (let i = 0; i < bytesRead; i++) {
      if (buffer[i] === 0) {
        return true;
      }
    }
    return false;
  } catch (e) {
    return false; // If we can't read it, we assume it's not a valid binary file or we'll let other logic handle it
  }
}

/**
 * Parses a .gitignore file and returns a function that checks if a given path is ignored.
 * @param {string} gitignorePath 
 * @returns {Function} (filePath: string) => boolean
 */
function createGitignoreFilter(gitignorePath) {
  if (!existsSync(gitignorePath)) return () => false;
  
  const content = readFileSync(gitignorePath, "utf8");
  const lines = content.split(/\r?\n/);
  
  const rules = [];
  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith("#")) continue;
    
    let isNegation = false;
    if (line.startsWith("!")) {
      isNegation = true;
      line = line.substring(1);
    }
    
    let regexStr = line
      .replace(/\./g, "\\.")
      .replace(/\*/g, ".*")
      .replace(/\?/g, ".")
      .replace(/\/$/, "(?:/|$)");
      
    if (!line.includes("/")) {
      regexStr = `(?:^|/)${regexStr}(?:/|$)`;
    } else if (line.startsWith("/")) {
      regexStr = `^${regexStr.substring(1)}`;
    } else {
      regexStr = `(?:^|/)${regexStr}`;
    }
    
    try {
      rules.push({
        regex: new RegExp(regexStr),
        isNegation
      });
    } catch (e) {
      // Ignore invalid regex
    }
  }
  
  return (filePath) => {
    const normalizedPath = filePath.replace(/\\/g, "/");
    let ignored = false;
    for (const rule of rules) {
      if (rule.regex.test(normalizedPath)) {
        ignored = !rule.isNegation;
      }
    }
    return ignored;
  };
}

/**
 * 自動生成ファイル・スナップショット・中間成果物判定用のカテゴリ別正規表現一覧
 */
const AUTO_GENERATED_PATTERNS = [
  // 1. スナップショットテスト / 承認テスト (Verify / Jest / ApprovalTests)
  /(?:\.(?:verified|received|approved)\.[^/]+|\.snap)$|(?:^|\/)__snapshots__(?:\/|$)/i,

  // 2. C# / .NET / Roslyn 系の自動生成コード & ビルド中間生成物
  /(?:\.(?:g|g\.i|designer|generated|razor\.g)\.cs|^TemporaryGeneratedFile_[^/]+\.cs)$|(?:^|\/)(?:obj|bin\/(?:Debug|Release|net\d+|netstandard|x64|x86))(?:\/|$)/i,

  // 3. Web / フロントエンド / スキーマ生成物
  /(?:\.min\.(?:js|css)|\.(?:js|css|d\.ts)\.map|\.graphql\.ts|\.generated\.ts|bundle\.js|vendor\.js|chunk-[^/]+\.js|routeTree\.gen\.ts)$|(?:^|\/)(?:dist|build|\.next|\.nuxt)(?:\/|$)/i,

  // 4. Protocol Buffers / gRPC / API 自動生成クライアント
  /(?:\.pb\.(?:cs|go)|_pb\.js|Grpc\.cs|\.openapi\.(?:cs|ts)|\.swagger\.(?:cs|ts))$|(?:^|\/)prisma\/(?:client|generated)(?:\/|$)/i,

  // 5. カバレッジ / テスト結果 / レビュー中間生成物
  /(?:lcov\.info|cobertura\.xml|[^/]+-(?:flash-context|meta-index-.*)\.json)$|(?:^|\/)(?:TestResults|coverage|tmp\/code-reviews)(?:\/|$)/i,
];

/**
 * 指定されたファイルパスが自動生成コード、スナップショット、または中間成果物であるかを判定する
 * @param {string} filePath - 対象ファイルパス（相対パスまたはファイル名）
 * @returns {boolean}
 */
function isAutoGeneratedFile(filePath) {
  const normalizedPath = filePath.replace(/\\/g, "/");
  return AUTO_GENERATED_PATTERNS.some((pattern) => pattern.test(normalizedPath));
}

export {
  HELP_FLAGS,
  checkHelp,
  resolveSessionId,
  ensureDirSync,
  getFormattedDate,
  getTimestampString,
  cleanJsonString,
  extractFilenamesFromString,
  isBinaryFileSync,
  createGitignoreFilter,
  AUTO_GENERATED_PATTERNS,
  isAutoGeneratedFile,
};

export default {
  HELP_FLAGS,
  checkHelp,
  resolveSessionId,
  ensureDirSync,
  getFormattedDate,
  getTimestampString,
  cleanJsonString,
  extractFilenamesFromString,
  isBinaryFileSync,
  createGitignoreFilter,
  AUTO_GENERATED_PATTERNS,
  isAutoGeneratedFile,
};

