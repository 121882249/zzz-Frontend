param([Parameter(Mandatory=$true)][string]$Launcher)
$ErrorActionPreference='Stop'
$launcherPath=(Resolve-Path -LiteralPath $Launcher).Path
if([IO.Path]::GetFileName($launcherPath) -ne 'TokenPro.exe'){throw 'Expected the freshly packaged TokenPro launcher'}
$kitRoot=Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
$manifestTool=Get-ChildItem -LiteralPath $kitRoot -Directory -ErrorAction Stop |
    Sort-Object Name -Descending | ForEach-Object {Join-Path $_.FullName 'x64/mt.exe'} |
    Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
if(-not $manifestTool){throw 'Windows SDK manifest tool is required; no tools will be downloaded or installed'}
$manifestPath=Join-Path ([IO.Path]::GetDirectoryName($launcherPath)) 'TokenPro.launcher.manifest'
& $manifestTool '-nologo' ("-inputresource:$launcherPath;#1") ("-out:$manifestPath")
if($LASTEXITCODE -ne 0){throw 'Could not read original launcher manifest'}
$document=[xml][IO.File]::ReadAllText($manifestPath)
$level=$document.SelectSingleNode("//*[local-name()='requestedExecutionLevel']")
if(-not $level -or $level.GetAttribute('level') -ne 'asInvoker'){throw 'Launcher must retain asInvoker privileges'}
$application=$document.SelectSingleNode("/*[local-name()='assembly']/*[local-name()='application']")
if(-not $application){$application=$document.CreateElement('application','urn:schemas-microsoft-com:asm.v3');[void]$document.DocumentElement.AppendChild($application)}
$settings=$application.SelectSingleNode("*[local-name()='windowsSettings']")
if(-not $settings){$settings=$document.CreateElement('windowsSettings','urn:schemas-microsoft-com:asm.v3');[void]$application.AppendChild($settings)}
$codePage=$settings.SelectSingleNode("*[local-name()='activeCodePage']")
if(-not $codePage){$codePage=$document.CreateElement('activeCodePage','http://schemas.microsoft.com/SMI/2019/WindowsSettings');[void]$settings.AppendChild($codePage)}
$codePage.InnerText='UTF-8'
$document.Save($manifestPath)
& $manifestTool '-nologo' '-manifest' $manifestPath ("-outputresource:$launcherPath;#1")
if($LASTEXITCODE -ne 0){throw 'Could not embed per-application UTF-8 manifest'}
& $manifestTool '-nologo' ("-inputresource:$launcherPath;#1") ("-out:$manifestPath")
if($LASTEXITCODE -ne 0){throw 'Could not verify embedded manifest'}
$verified=[xml][IO.File]::ReadAllText($manifestPath)
if($verified.SelectSingleNode("//*[local-name()='activeCodePage']").InnerText -ne 'UTF-8' -or
   $verified.SelectSingleNode("//*[local-name()='requestedExecutionLevel']").GetAttribute('level') -ne 'asInvoker'){
    throw 'Launcher manifest verification failed'
}
Write-Output 'Launcher manifest verified: UTF-8, asInvoker. System settings unchanged.'
