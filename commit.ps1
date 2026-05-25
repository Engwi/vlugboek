param(
    [Parameter(Mandatory = $true)]
    [string]$Message
)

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "      Git Commit & Push Utility" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

# Ensure we're inside a git repo
if (-not (Test-Path ".git")) {
    Write-Host "ERROR: This folder is not a Git repository." -ForegroundColor Red
    exit 1
}

Write-Host "Staging all changes..." -ForegroundColor Yellow
git add .

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Failed to stage files." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Files staged for commit:" -ForegroundColor Yellow
git status --short

Write-Host ""
Write-Host "Creating commit..." -ForegroundColor Yellow
git commit -m $Message

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Commit failed." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Pushing to remote..." -ForegroundColor Yellow
git push

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "SUCCESS: Changes committed and pushed." -ForegroundColor Green
}
else {
    Write-Host ""
    Write-Host "Push failed." -ForegroundColor Red
}