$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$Version = '1.2.67'
$compilerCandidates = @(
  $env:INNO_ISCC,
  "${env:ProgramFiles(x86)}/Inno Setup 6/ISCC.exe",
  "$env:ProgramFiles/Inno Setup 6/ISCC.exe",
  "$env:LOCALAPPDATA/Programs/Inno Setup 6/ISCC.exe"
)
$compiler = $compilerCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $compiler) { throw '需要 Inno Setup 6.7.1+。请安装构建工具或用 INNO_ISCC 指定 ISCC.exe；脚本不会自动下载或安装工具。' }
# ISCC.exe may report PE FileVersion 0.0.0.0; the .iss template checks the actual engine version.
& ./build.ps1
if ($LASTEXITCODE -ne 0) { throw "build.ps1 failed with exit code $LASTEXITCODE" }
$packageRoot = Join-Path $PSScriptRoot ('build/windows-package-' + [Guid]::NewGuid().ToString('N'))
$inputRoot = Join-Path $packageRoot 'input'
$imageRoot = Join-Path $packageRoot 'image'
New-Item -ItemType Directory -Path $inputRoot -Force | Out-Null
New-Item -ItemType Directory -Path $imageRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'dist') -Force | Out-Null
Copy-Item -LiteralPath 'build/TokenPro.jar' -Destination (Join-Path $inputRoot 'TokenPro.jar')
& "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name TokenPro --app-version $Version `
  --input $inputRoot --main-jar TokenPro.jar --main-class work.tokenpro.client.Main `
  --module-path "$env:JAVA_HOME/jmods" `
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver,jdk.crypto.ec `
  --icon ../Router.ico `
  --vendor TokenPro --description 'TokenPro · AI 模型接入与用量管理' --dest $imageRoot
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
& './installer/windows/enable-utf8-launcher.ps1' -Launcher (Join-Path $imageRoot 'TokenPro/TokenPro.exe')
& $compiler ("/DAppVersion=" + $Version) ("/DAppImageDir=" + (Join-Path $imageRoot 'TokenPro')) `
  ("/DOutputDirPath=" + (Join-Path $PSScriptRoot 'dist')) 'installer/windows/TokenPro.iss'
if ($LASTEXITCODE -ne 0) { throw "中文安装包编译失败，退出码 $LASTEXITCODE" }
if ($env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted') {
  & './installer/windows/smoke-test.ps1' `
    -Installer (Join-Path $PSScriptRoot ("dist/TokenPro-$Version-Windows-x64.exe")) `
    -AppImage (Join-Path $imageRoot 'TokenPro') -OutputRoot (Join-Path $packageRoot 'acceptance') -Version $Version
}
Write-Host '已生成中文星空主题 Windows 安装包，默认仅为当前用户安装。'
