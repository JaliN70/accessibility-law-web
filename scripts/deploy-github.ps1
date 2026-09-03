# GitHub Pages 一鍵部署（需先完成 gh auth login）
$ErrorActionPreference = "Stop"
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

Set-Location "C:\Users\jalin.you\AJ-WORK\accessibility-law-web"

gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "請先登入 GitHub：" -ForegroundColor Yellow
    gh auth login --hostname github.com --git-protocol https --web
}

$repo = "accessibility-law-web"
$user = (gh api user -q .login)
$exists = gh repo view "$user/$repo" 2>$null
if ($LASTEXITCODE -ne 0) {
    gh repo create $repo --public --source=. --remote=origin --push --description "建築物無障礙設施設計規範 - 手機網頁版"
} else {
    git push -u origin main
}

gh api -X POST "repos/$user/$repo/pages" -f "build_type=legacy" -f "source[branch]=main" -f "source[path]=/" 2>$null
if ($LASTEXITCODE -ne 0) {
    gh api -X PUT "repos/$user/$repo/pages" -f "build_type=legacy" -f "source[branch]=main" -f "source[path]=/" 2>$null
}

$url = "https://$user.github.io/$repo/"
Write-Host ""
Write-Host "部署完成！" -ForegroundColor Green
Write-Host "網址：$url" -ForegroundColor Cyan
Write-Host "iPhone：Safari 開啟 → 分享 → 加入主畫面" -ForegroundColor Gray
