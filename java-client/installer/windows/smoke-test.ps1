param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$AppImage,
    [Parameter(Mandatory=$true)][string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
# This installs/uninstalls only on a fresh, disposable GitHub-hosted Windows runner.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or -not $IsWindows) {
    throw 'Installer acceptance is restricted to disposable GitHub-hosted Windows runners.'
}
$workspace = [IO.Path]::GetFullPath($env:GITHUB_WORKSPACE).TrimEnd('\') + '\'
$testRoot = [IO.Path]::GetFullPath($OutputRoot)
foreach ($path in @($testRoot, [IO.Path]::GetFullPath($Installer), [IO.Path]::GetFullPath($AppImage))) {
    if (-not $path.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture outside workspace' }
    for ($ancestor = $path; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Fixture contains a reparse point'
        }
    }
}
if (Test-Path -LiteralPath $testRoot) { throw 'Fixture must be a new directory' }
$registryRelative = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{55841B51-BA2D-4BC4-BB9F-8AD99606334D}_is1'
$userRegistration = 'HKCU:\' + $registryRelative
$machineRegistration = 'HKLM:\' + $registryRelative
$desktopLink = Join-Path ([Environment]::GetFolderPath('Desktop')) 'TokenPro.lnk'
$startLink = Join-Path ([Environment]::GetFolderPath('Programs')) 'TokenPro.lnk'
$dataRoot = Join-Path $env:APPDATA 'TokenPro'
foreach ($existing in @($userRegistration, $machineRegistration, $desktopLink, $startLink, $dataRoot)) {
    if (Test-Path -LiteralPath $existing) { throw 'Runner already has TokenPro state; refusing to overwrite it' }
}
[void][IO.Directory]::CreateDirectory($testRoot)
[void][IO.Directory]::CreateDirectory($dataRoot)
$marker = Join-Path $dataRoot 'installer-acceptance-preserve.txt'
$markerValue = [Guid]::NewGuid().ToString('N')
[IO.File]::WriteAllText($marker, $markerValue)
$installRoot = Join-Path $testRoot '中文 用户目录\TokenPro'

function Invoke-FixtureProcess([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot ($LogName + '.stdout.log')) `
        -RedirectStandardError (Join-Path $testRoot ($LogName + '.stderr.log'))
    if (-not $process.WaitForExit(120000)) { throw "$LogName timed out; fixture retained for diagnosis" }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "$LogName failed with exit code $($process.ExitCode)" }
}

$setupArguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/SP-', '/NORESTART', '/NOCLOSEAPPLICATIONS',
    '/NORESTARTAPPLICATIONS', '/RESTARTEXITCODE=23', '/TASKS=desktopicon',
    ('/DIR="' + $installRoot + '"'), ('/LOG="' + (Join-Path $testRoot 'setup.log') + '"'))
Invoke-FixtureProcess $Installer $setupArguments 'install'
$registration = Get-ItemProperty -LiteralPath $userRegistration
if ($registration.DisplayVersion -ne '1.2.65' -or $registration.'Inno Setup: Language' -ne 'zh_CN') { throw 'Version/language registration mismatch' }
if ([IO.Path]::GetFullPath($registration.InstallLocation).TrimEnd('\') -ne $installRoot) { throw 'Installation escaped fixture directory' }
if (Test-Path -LiteralPath $machineRegistration) { throw 'Installer wrote machine-wide registration' }
foreach ($relative in @('TokenPro.exe', 'app\TokenPro.jar', 'app\TokenPro.cfg', 'runtime\release')) {
    $expected = (Get-FileHash -LiteralPath (Join-Path $AppImage $relative) -Algorithm SHA256).Hash
    $actual = (Get-FileHash -LiteralPath (Join-Path $installRoot $relative) -Algorithm SHA256).Hash
    if ($expected -ne $actual) { throw "Installed file mismatch: $relative" }
}
$shortcutReader = New-Object -ComObject WScript.Shell
foreach ($linkPath in @($desktopLink, $startLink)) {
    if (-not (Test-Path -LiteralPath $linkPath)) { throw 'Shortcut missing' }
    $link = $shortcutReader.CreateShortcut($linkPath)
    if ($link.TargetPath -ne (Join-Path $installRoot 'TokenPro.exe') -or $link.Description -ne 'TokenPro · AI 模型接入') { throw 'Shortcut target/Chinese description mismatch' }
}
Invoke-FixtureProcess (Join-Path $installRoot 'TokenPro.exe') @('--self-test') 'native-self-test'
$testOutput = [IO.File]::ReadAllText((Join-Path $testRoot 'native-self-test.stdout.log'))
if ($testOutput -notmatch '(\d+) checks passed') { throw 'Installed native runtime self-test did not pass' }
$nativeTestResult = $Matches[0]
if ([IO.File]::ReadAllText($marker) -ne $markerValue) { throw 'Installation changed existing user data' }

# Re-installing the same build should preserve an unrelated file and existing account data.
$unrelated = Join-Path $installRoot '用户自建文件.txt'
[IO.File]::WriteAllText($unrelated, $markerValue)
Invoke-FixtureProcess $Installer $setupArguments 'reinstall'
if ([IO.File]::ReadAllText($unrelated) -ne $markerValue) { throw 'Reinstall removed unrelated data' }
$uninstaller = Join-Path $installRoot 'unins000.exe'
if (-not $uninstaller.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $registration.InstallLocation.TrimEnd('\') -ne $installRoot) { throw 'Uninstall target is not the verified fixture' }
Invoke-FixtureProcess $uninstaller @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/LOG="' + (Join-Path $testRoot 'uninstall.log') + '"')) 'uninstall'
if (Test-Path -LiteralPath (Join-Path $installRoot 'TokenPro.exe')) { throw 'Uninstaller left installed program behind' }
foreach ($removed in @($userRegistration, $desktopLink, $startLink)) {
    if (Test-Path -LiteralPath $removed) { throw 'Uninstaller left registration or shortcut behind' }
}
if ([IO.File]::ReadAllText($marker) -ne $markerValue -or [IO.File]::ReadAllText($unrelated) -ne $markerValue) { throw 'Uninstaller removed user data' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$report = [ordered]@{
    status='passed'; installer_sha256=(Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash
    native_self_test=$nativeTestResult; language='zh_CN'; registration='current-user'
    shortcuts_verified=$true; reinstall_preserves_data=$true; uninstall_preserves_data=$true
    runner_token_is_admin=$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    visual_review='not performed'; install_root=$installRoot
}
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'acceptance.json') -Encoding utf8
Write-Output ($report | ConvertTo-Json -Compress)
