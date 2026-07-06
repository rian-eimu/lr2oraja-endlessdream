param(
    [Parameter(Mandatory = $false)]
    [string]$SessionId = "001",

    [Parameter(Mandatory = $false)]
    [string]$Date = "",

    [Parameter(Mandatory = $false)]
    [string]$ReviewDir = "tmp/code-reviews"
)

# 該当するレビューファイルを検索
$searchPath = Join-Path $ReviewDir "$SessionId-phase*-review_$Date*.md"
$reviewFiles = Get-ChildItem -Path $searchPath | Sort-Object Name

if ($reviewFiles.Count -eq 0) {
    Write-Error "指定された条件に一致するレビューファイルが見つかりませんでした: $searchPath"
    exit 1
}

$reportPath = Join-Path $ReviewDir "$SessionId-integrated-review-report_$Date.md"

# 各種日本語ラベルの定義
$txtReportTitle = "統合コードレビューレポート (Session $SessionId)"

# 日付フォーマットの調整（パラメータのYYYYMMDDからYYYY-MM-DDへ）
$formattedDate = $Date
if ($Date -match '^(\d{4})(\d{2})(\d{2})') {
    $formattedDate = "$($Matches[1])-$($Matches[2])-$($Matches[3])"
}

$tableRows = @()
$detailedReviews = @()
$totalActualScore = 0.0
$totalMaxScore = 0.0

foreach ($file in $reviewFiles) {
    # .NETのAPIを使用して、PowerShell 5.1での文字コード誤認識を回避
    $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    
    # 最初の行からタイトルを抽出
    $lines = $content.Split([char]10)
    $titleVal = ""
    if ($lines.Count -gt 0) {
        $titleVal = $lines[0].Trim().TrimStart("#").Trim()
        # " - 統合コードレビュー" などのサフィックスを除去
        $dashIndex = $titleVal.IndexOf(" - ")
        if ($dashIndex -ge 0) {
            $titleVal = $titleVal.Substring(0, $dashIndex).Trim()
        }
    }
    
    # 総合スコアを抽出
    $scoreVal = "N/A"
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("## 総合スコア:")) {
            $scoreVal = $trimmed.Substring(("## 総合スコア:").Length).Trim()
            break
        }
    }
    
    if ($scoreVal -match '(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)') {
        $totalActualScore += [double]$Matches[1]
        $totalMaxScore += [double]$Matches[2]
    }
    
    # 対象ファイルを抽出
    $filesList = @()
    $inFilesSection = $false
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("## 対象ファイル")) {
            $inFilesSection = $true
            continue
        }
        if ($inFilesSection) {
            if ($trimmed.StartsWith("##")) {
                $inFilesSection = $false
                break
            }
            if ($trimmed.StartsWith("-")) {
                # マークダウン表記のクリーンアップ（バッククォートなどの除去）
                $cleaned = $trimmed.Substring(1).Trim().Replace('"', '').Replace("'", '').Replace('`', '')
                $fileName = [System.IO.Path]::GetFileName($cleaned)
                if ($fileName) {
                    $filesList += $fileName
                }
            }
        }
    }
    $filesStr = $filesList -join ", "
    
    # タイトルからフェーズ番号をパース (例: 【Phase 1】 -> Phase 01)
    $phaseName = "Phase"
    $titleClean = $titleVal
    if ($titleVal.Contains("【Phase") -and $titleVal.Contains("】")) {
        $startIndex = $titleVal.IndexOf("【Phase") + 7
        $endIndex = $titleVal.IndexOf("】")
        $phaseNum = $titleVal.Substring($startIndex, $endIndex - $startIndex).Trim()
        
        if ($phaseNum.Contains("-")) {
            $parts = $phaseNum.Split("-")
            $p1 = 0
            $p2 = 0
            if ([int]::TryParse($parts[0], [ref]$p1) -and [int]::TryParse($parts[1], [ref]$p2)) {
                $phaseName = "Phase " + $p1.ToString("00") + "-" + $p2.ToString("00")
            }
            else {
                $phaseName = "Phase " + $phaseNum
            }
        }
        else {
            $p1 = 0
            if ([int]::TryParse($phaseNum, [ref]$p1)) {
                $phaseName = "Phase " + $p1.ToString("00")
            }
            else {
                $phaseName = "Phase " + $phaseNum
            }
        }
        $titleClean = $titleVal.Substring($endIndex + 1).Trim()
    }
    
    $tableRows += "| $phaseName | $titleClean | $scoreVal | $filesStr |"
    
    # 詳細セクションの結合
    $detailedReviews += "---"
    $detailedReviews += ""
    $detailedReviews += $content
    $detailedReviews += ""
}

# 総合スコアを計算
$integratedScoreStr = "N/A"
if ($totalMaxScore -gt 0) {
    $integratedScore = ($totalActualScore / $totalMaxScore) * 100
    $integratedScoreStr = "{0:F2}%" -f $integratedScore
}

$summaryLines = @()
$summaryLines += "# $txtReportTitle"
$summaryLines += ""
$summaryLines += "作成日: $formattedDate"
$summaryLines += ""
$summaryLines += "## 1. 総合サマリー"
$summaryLines += ""
if ($integratedScoreStr -ne "N/A") {
    $summaryLines += "### **全体総合スコア: $integratedScoreStr** ($totalActualScore / $totalMaxScore)"
    $summaryLines += ""
}
$summaryLines += "| フェーズ | タイトル | 総合スコア | 対象ファイル |"
$summaryLines += "| --- | --- | --- | --- |"
$summaryLines += $tableRows
$summaryLines += ""
$summaryLines += "## 2. フェーズ別詳細レビュー"
$summaryLines += ""

$finalContent = ($summaryLines + $detailedReviews) -join "`r`n"

# .NETのAPIでUTF-8出力 (BOM付きで出力される)
[System.IO.File]::WriteAllText($reportPath, $finalContent, [System.Text.Encoding]::UTF8)

Write-Output "Merged $($reviewFiles.Count) files into $reportPath"
