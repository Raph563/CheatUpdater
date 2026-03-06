param(
  [Parameter(Mandatory=$true)]
  [string]$VpsPassword,
  [string]$VpsHost = "207.180.235.68",
  [string]$Repo = "Raph563/CheatUpdater",
  [string]$Tag,
  [string]$ReleaseName
)

$ErrorActionPreference = "Stop"

function Get-GitHubToken([string]$repoPath) {
  $credInput = "protocol=https`nhost=github.com`npath=$repoPath`n`n"
  $credOutput = $credInput | git credential fill
  $tokenLine = ($credOutput | Select-String '^password=').Line
  if (-not $tokenLine) { throw "GitHub token introuvable via git credential fill" }
  return $tokenLine.Substring(9)
}

function Get-VersionTag() {
  $metadataPath = "app/build/outputs/apk/release/output-metadata.json"
  if (-not (Test-Path $metadataPath)) { throw "Metadata release manquante: $metadataPath" }
  $json = Get-Content $metadataPath -Raw | ConvertFrom-Json
  $versionName = $json.elements[0].versionName
  if (-not $versionName) { throw "versionName introuvable dans output-metadata.json" }
  return "v$versionName"
}

./gradlew.bat clean :app:assembleRelease :app:assembleDebug

if (-not $Tag) { $Tag = Get-VersionTag }
if (-not $ReleaseName) { $ReleaseName = "CheatUpdater $Tag" }

$assets = @(
  "app/build/outputs/apk/release/app-universal-release.apk",
  "app/build/outputs/apk/release/app-arm64-v8a-release.apk",
  "app/build/outputs/apk/release/app-armeabi-v7a-release.apk",
  "app/build/outputs/apk/release/app-x86_64-release.apk"
)
$releaseDir = "release-artifacts/$Tag"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$shaPath = Join-Path $releaseDir "SHA256SUMS.txt"
(Get-ChildItem $assets | ForEach-Object {
  $h = Get-FileHash -Algorithm SHA256 $_.FullName
  "{0}  {1}" -f $h.Hash.ToLower(), $_.Name
}) | Set-Content $shaPath
$notesPath = Join-Path $releaseDir "RELEASE_NOTES.md"
@"
# $ReleaseName

- Auto-generated release via scripts/publish-and-deploy.ps1
"@ | Set-Content $notesPath

if (-not (git tag -l $Tag)) { git tag -a $Tag -m $ReleaseName }
git push origin master
git push origin $Tag

$token = Get-GitHubToken $Repo
$headers = @{ Authorization = "Bearer $token"; "User-Agent" = "cheatupdater-release-script"; Accept = "application/vnd.github+json" }

$release = $null
try {
  $release = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" -Headers $headers
} catch {
  $body = @{ tag_name=$Tag; name=$ReleaseName; body=(Get-Content $notesPath -Raw); draft=$false; prerelease=$false } | ConvertTo-Json
  $release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" -Headers $headers -Body $body -ContentType "application/json"
}
$releaseId = $release.id
$uploadBase = $release.upload_url.Split('{')[0]

$uploadFiles = @($assets + $shaPath)
$uploadNames = $uploadFiles | ForEach-Object { [System.IO.Path]::GetFileName($_) }
$existingAssets = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$Repo/releases/$releaseId/assets" -Headers $headers
foreach($ea in $existingAssets){ if($uploadNames -contains $ea.name){ Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$Repo/releases/assets/$($ea.id)" -Headers $headers | Out-Null } }

foreach($file in $uploadFiles){
  $name = [System.IO.Path]::GetFileName($file)
  $url = "${uploadBase}?name=$name"
  Invoke-RestMethod -Method Post -Uri $url -Headers @{ Authorization = "Bearer $token"; "User-Agent" = "cheatupdater-release-script"; "Content-Type" = "application/octet-stream" } -InFile $file | Out-Null
}

$plink = "plink"
$pscp = "C:\Program Files\PuTTY\pscp.exe"
$hostKey = "ssh-ed25519 255 SHA256:OewMdI2B38wISDbCnsiRDjx9UiZAF2AFSF7mkl+rpls"

$remoteCmd = "cd /opt/cheatupdater && git fetch --all --prune && git reset --hard origin/master && export COMPOSE_PROJECT_NAME=cheatupdater && docker compose up -d --build && mkdir -p /opt/cheatupdater/releases/$Tag"
& $plink -ssh -batch -hostkey $hostKey root@$VpsHost -pw $VpsPassword $remoteCmd

& $pscp -batch -scp -hostkey $hostKey -pw $VpsPassword @uploadFiles root@${VpsHost}:/opt/cheatupdater/releases/$Tag/

Write-Host "Done: $Tag published and deployed on $VpsHost"
