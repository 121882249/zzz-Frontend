$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& ./build.ps1
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist | Out-Null
Remove-Item -Recurse -Force build/input -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build/input | Out-Null
Copy-Item build/TokenPro.jar build/input/TokenPro.jar -Force
& "$env:JAVA_HOME/bin/jpackage.exe" --type exe --name TokenPro --app-version 1.2.13 `
  --input build/input --main-jar TokenPro.jar --main-class work.tokenpro.client.Main `
  --module-path "$env:JAVA_HOME/jmods" `
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver,jdk.crypto.ec `
  --icon ../Router.ico `
  --vendor TokenPro --description "TokenPro cross-platform desktop client" --dest dist `
  --win-menu --win-shortcut --win-dir-chooser
Write-Host "Created Windows installer in java-client/dist"
