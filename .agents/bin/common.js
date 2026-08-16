const fs = require("fs");
const path = require("path");

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

  const reviewDir = path.join(rootDir, "tmp", "code-reviews");
  if (!fs.existsSync(reviewDir)) {
    return "001";
  }

  let maxId = 0;
  try {
    const files = fs.readdirSync(reviewDir);
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
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
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
  const fileRegex = /[\w\-./\\]+\.(?:cs|ts|js|jsx|tsx|py|json|md|xaml|xml|cpp|h|hpp|java|go|rs|html|css|scss|vue|svelte)/gi;
  const matches = text.match(fileRegex) || [];
  const results = [];

  for (const m of matches) {
    const base = path.basename(m.replace(/[`\*]/g, "").trim());
    if (base && !results.includes(base)) {
      results.push(base);
    }
  }
  return results;
}

module.exports = {
  HELP_FLAGS,
  checkHelp,
  resolveSessionId,
  ensureDirSync,
  getFormattedDate,
  getTimestampString,
  cleanJsonString,
  extractFilenamesFromString,
};
