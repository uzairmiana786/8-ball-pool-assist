$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$version = '8.11.1'
$cache = Join-Path $env:TEMP 'poolvision-gradle'
New-Item -ItemType Directory -Force $cache | Out-Null
$zip = Join-Path $cache 'gradle.zip'
$url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
Invoke-WebRequest $url -OutFile $zip
$expected = (Invoke-RestMethod "$url.sha256").Trim().ToLower()
if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $expected) { throw 'Gradle checksum mismatch' }
Expand-Archive $zip $cache -Force
$bootstrap = Join-Path $cache 'bootstrap'
New-Item -ItemType Directory -Force $bootstrap | Out-Null
Set-Content (Join-Path $bootstrap 'settings.gradle') "rootProject.name='wrapper-bootstrap'"
& (Join-Path $cache "gradle-$version/bin/gradle.bat") -p $bootstrap wrapper --gradle-version $version --distribution-type bin
if ($LASTEXITCODE -ne 0) { throw 'Wrapper generation failed. Set JAVA_HOME to Android Studio jbr folder.' }
Copy-Item (Join-Path $bootstrap 'gradlew') $PSScriptRoot -Force
Copy-Item (Join-Path $bootstrap 'gradlew.bat') $PSScriptRoot -Force
Copy-Item (Join-Path $bootstrap 'gradle/wrapper/*') (Join-Path $PSScriptRoot 'gradle/wrapper') -Force
Write-Host 'Wrapper ready. Open this folder in Android Studio and Sync.'
