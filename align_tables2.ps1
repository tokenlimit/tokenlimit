# 按 IDEA ALIGNED 表格样式重新对齐 README.md 的 8 个表格
# 规则（源码 TableModificationUtils.isColumnCorrectlyFormatted ALIGNED 分支）：
#   1. 每列所有单元格（表头+数据）显示宽度一致 = W（max 内容显示宽度 + 2）
#   2. 内容行 = '|' + ' 内容+填充 ' + '|'（前缀 1 空格；左对齐；CENTER 列居中）
#   3. 分隔行 = '|' + '-'*W + '|'（无空格；方向冒号保留：CENTER 用 ':'+'-'*(W-2)+':'）
# 显示宽度：全角/emoji=2、ASCII=1、控制=0（照抄 TableCharacterWidthUtils）
$ErrorActionPreference = 'Stop'
$path = 'd:\IdeaProjects\token-limit-project\README.md'
$outPath = 'd:\IdeaProjects\token-limit-project\tables_aligned_out.txt'
$text = [System.IO.File]::ReadAllText($path)
$lines = $text -split "`n"

function IsFullWidth([int]$cp) {
    if ($cp -ge 0x1100 -and ($cp -le 0x115F -or $cp -eq 0x2329 -or $cp -eq 0x232A -or
        ($cp -ge 0x231A -and $cp -le 0x231B) -or ($cp -ge 0x23E9 -and $cp -le 0x23EC) -or
        $cp -eq 0x23F0 -or $cp -eq 0x23F3 -or ($cp -ge 0x25FD -and $cp -le 0x25FE) -or
        ($cp -ge 0x2600 -and $cp -le 0x27BF) -or ($cp -ge 0x2B05 -and $cp -le 0x2B07) -or
        ($cp -ge 0x2B1B -and $cp -le 0x2B1C) -or $cp -eq 0x2B50 -or $cp -eq 0x2B55 -or
        ($cp -ge 0x2E80 -and $cp -le 0x3247 -and $cp -ne 0x303F) -or
        ($cp -ge 0x3250 -and $cp -le 0x4DBF) -or ($cp -ge 0x4E00 -and $cp -le 0xA4C6) -or
        ($cp -ge 0xA960 -and $cp -le 0xA97C) -or ($cp -ge 0xAC00 -and $cp -le 0xD7A3) -or
        ($cp -ge 0xF900 -and $cp -le 0xFAFF) -or ($cp -ge 0xFE10 -and $cp -le 0xFE19) -or
        ($cp -ge 0xFE30 -and $cp -le 0xFE6B) -or ($cp -ge 0xFF01 -and $cp -le 0xFF60) -or
        ($cp -ge 0xFFE0 -and $cp -le 0xFFE6) -or ($cp -ge 0x1B000 -and $cp -le 0x1B122) -or
        $cp -eq 0x1F0CF -or $cp -eq 0x1F18E -or ($cp -ge 0x1F191 -and $cp -le 0x1F19A) -or
        ($cp -ge 0x1F1E6 -and $cp -le 0x1F1FF) -or ($cp -ge 0x1F200 -and $cp -le 0x1F251) -or
        ($cp -ge 0x1F300 -and $cp -le 0x1F5FF) -or ($cp -ge 0x1F600 -and $cp -le 0x1F64F) -or
        ($cp -ge 0x1F680 -and $cp -le 0x1F6FF) -or ($cp -ge 0x1F7E0 -and $cp -le 0x1F7EB) -or
        $cp -eq 0x1F7F0 -or ($cp -ge 0x1F900 -and $cp -le 0x1F9FF) -or
        ($cp -ge 0x1FA70 -and $cp -le 0x1FAFF) -or ($cp -ge 0x20000 -and $cp -le 0x3FFFD))) {
        return $true
    }
    return $false
}

function DispWidth([string]$s) {
    $w = 0
    foreach ($ch in $s.ToCharArray()) {
        $cp = [int][char]$ch
        if ($cp -ge 0x20 -and $cp -le 0x7E) { $w += 1 }
        elseif ($cp -lt 0x20) { $w += 0 }
        elseif (IsFullWidth $cp) { $w += 2 }
        else { $w += 1 }
    }
    return $w
}

$sb = New-Object System.Text.StringBuilder
$tidx = 0
$i = 0
while ($i -lt $lines.Length) {
    $line = $lines[$i].TrimEnd("`r")
    $isTable = $line.StartsWith('|') -and ($i + 1) -lt $lines.Length
    $sep = ''
    if ($isTable) { $sep = $lines[$i + 1].TrimEnd("`r") }
    if ($isTable -and $sep -match '^\|[\s:|-]+\|$' -and $sep.Contains('-')) {
        $tidx++
        $rows = New-Object System.Collections.ArrayList
        [void]$rows.Add($line)
        $j = $i + 1
        while ($j -lt $lines.Length -and $lines[$j].TrimStart().StartsWith('|')) {
            [void]$rows.Add($lines[$j].TrimEnd("`r"))
            $j++
        }
        # 解析
        $allCells = @()  # 每行: string[]（trim 后）
        foreach ($r in $rows) {
            $parts = $r.Trim() -split '\|'
            $rowCells = @()
            for ($k = 1; $k -lt $parts.Length - 1; $k++) { $rowCells += $parts[$k].Trim() }
            $allCells += , $rowCells
        }
        $ncols = 0
        foreach ($rc in $allCells) { if ($rc.Length -gt $ncols) { $ncols = $rc.Length } }
        # 列宽（内容行 = 全部行；分隔行是 rows[1]，其内容全是破折号不参与宽度）
        $widths = @()
        for ($c = 0; $c -lt $ncols; $c++) {
            $mx = 0
            $ri = 0
            foreach ($rc in $allCells) {
                if ($ri -ne 1 -and $c -lt $rc.Length) {
                    $w = DispWidth $rc[$c]
                    if ($w -gt $mx) { $mx = $w }
                }
                $ri++
            }
            $widths += ($mx + 2)
        }
        # 分隔行方向（基于原分隔行 cells）
        $alig = @()
        $sepCells = $allCells[1]
        for ($c = 0; $c -lt $ncols; $c++) {
            $orig = ''
            if ($c -lt $sepCells.Length) { $orig = $sepCells[$c] }
            $left = $orig.StartsWith(':')
            $right = $orig.EndsWith(':')
            if ($left -and $right) { $alig += 'C' } elseif ($right) { $alig += 'R' } elseif ($left) { $alig += 'L' } else { $alig += 'N' }
        }
        [void]$sb.AppendLine("===== TABLE $tidx (line $($i + 1)) widths=$($widths -join ',') align=$($alig -join '') =====")
        $ridx = 0
        foreach ($r in $rows) {
            $cs = $allCells[$ridx]
            $partsOut = @()
            if ($ridx -eq 1) {
                # 分隔行：无空格，破折号数 = W
                for ($c = 0; $c -lt $ncols; $c++) {
                    $W = $widths[$c]
                    $a = $alig[$c]
                    if ($a -eq 'C') { $partsOut += (':' + ('-' * ($W - 2)) + ':') }
                    elseif ($a -eq 'L') { $partsOut += (':' + ('-' * ($W - 1))) }
                    elseif ($a -eq 'R') { $partsOut += (('-' * ($W - 1)) + ':') }
                    else { $partsOut += ('-' * $W) }
                }
            } else {
                for ($c = 0; $c -lt $ncols; $c++) {
                    $v = ''
                    if ($c -lt $cs.Length) { $v = $cs[$c] }
                    $W = $widths[$c]
                    $dW = DispWidth $v
                    $pad = $W - 1 - $dW
                    if ($pad -lt 1) { $pad = 1 }
                    if ($alig[$c] -eq 'C') {
                        $total = $W - $dW
                        $left = [math]::Floor($total / 2)
                        $right = $total - $left
                        $partsOut += ((' ' * $left) + $v + (' ' * $right))
                    } else {
                        $partsOut += (' ' + $v + (' ' * $pad))
                    }
                }
            }
            [void]$sb.AppendLine('|' + ($partsOut -join '|') + '|')
            $ridx++
        }
        [void]$sb.AppendLine('')
        $i = $j
        continue
    }
    $i++
}
[void]$sb.AppendLine("TABLES=$tidx")
[System.IO.File]::WriteAllText($outPath, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Output ("done, tables=" + $tidx)
