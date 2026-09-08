$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& ./build.ps1
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist | Out-Null
& "$env:JAVA_HOME/bin/jpackage.exe" --type exe --name TokenPro --app-version 1.1.1 `
  --input build --main-jar TokenPro.jar --main-class work.tokenpro.client.Main `
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver `
  --icon ../Router.ico `
  --vendor TokenPro --description "TokenPro cross-platform desktop client" --dest dist `
  --win-menu --win-shortcut --win-dir-chooser
Write-Host "Created Windows installer in java-client/dist"
