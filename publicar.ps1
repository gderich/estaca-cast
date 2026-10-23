$ErrorActionPreference = 'Stop'
$RepoOwner = 'gderich'
$RepoName = 'estaca-cast'
$Branch = 'main'

function Fail($msg) { Write-Host "[ERRO] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }

if (-not (Test-Path '.git')) { Fail 'Execute este script na raiz do repositório Git.' }
if (-not (Test-Path 'package.json')) { Fail 'package.json não encontrado.' }

$pkg = Get-Content 'package.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$version = [string]$pkg.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { Fail "Versão inválida: $version" }
$tag = "v$version"

$remote = git remote get-url origin 2>$null
if ([string]::IsNullOrWhiteSpace($remote)) { Fail "Remote 'origin' não configurado." }

Write-Host "Publicando Estaca Cast $tag" -ForegroundColor Cyan
Write-Host "O GitHub Actions irá gerar EstacaCast-Setup-$version.exe e publicar a Release." -ForegroundColor DarkGray
$answer = Read-Host 'Continuar? [S/N]'
if ($answer -notmatch '^[Ss]$') { exit 0 }

git add -A
if ($LASTEXITCODE -ne 0) { Fail 'git add falhou.' }

git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
  git commit -m "release: $tag"
  if ($LASTEXITCODE -ne 0) { Fail 'Commit falhou.' }
} else {
  Write-Host 'Nenhuma alteração nova para commit.' -ForegroundColor Yellow
}

git push origin $Branch
if ($LASTEXITCODE -ne 0) { Fail 'Push da branch falhou.' }

$exists = git ls-remote --tags origin "refs/tags/$tag"
if ($exists) { Fail "A tag $tag já existe no GitHub. Aumente a versão no package.json." }

git tag $tag
git push origin $tag
if ($LASTEXITCODE -ne 0) { Fail 'Push da tag falhou.' }

Ok "Tag $tag enviada. Acompanhe Actions no GitHub."
