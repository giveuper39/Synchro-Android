[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SetupOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @(':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$toolsRoot = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$gradleRoot = Join-Path $toolsRoot 'gradle-8.11.1'
$downloadRoot = Join-Path $toolsRoot 'downloads'
$logRoot = Join-Path $toolsRoot 'logs'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javacCommand = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javacCommand) { $JavaHome = Split-Path -Parent (Split-Path -Parent $javacCommand.Source) }
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JDK 21 is required. Pass -JavaHome <JDK-directory>, set JAVA_HOME, or add the JDK bin directory to PATH.'
}
$javaExecutable = Join-Path $JavaHome 'bin\java.exe'

if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JDK 21 is required. Supply its directory using -JavaHome. Not found: $javaExecutable"
}

function Get-VerifiedDownload {
    param([string]$Url, [string]$FileName, [string]$Sha256)
    $archivePath = Join-Path $downloadRoot $FileName
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Write-Host "Downloading $FileName"
        Invoke-WebRequest -Uri $Url -OutFile ($archivePath + '.partial')
        $downloadHash = (Get-FileHash -LiteralPath ($archivePath + '.partial') -Algorithm SHA256).Hash
        if ($downloadHash -ne $Sha256) { throw "SHA-256 mismatch for $FileName" }
        Move-Item -LiteralPath ($archivePath + '.partial') -Destination $archivePath -Force
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $Sha256) {
        throw "SHA-256 mismatch for $archivePath"
    }
    return $archivePath
}

$environmentNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'PATH', 'TEMP', 'TMP')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    foreach ($directory in @($toolsRoot, $downloadRoot, $logRoot, (Join-Path $toolsRoot 'tmp'), (Join-Path $toolsRoot 'android-user'), (Join-Path $toolsRoot 'gradle-home'))) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
    $env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'
    $env:TEMP = Join-Path $toolsRoot 'tmp'
    $env:TMP = $env:TEMP
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH

    if (-not (Test-Path -LiteralPath $sdkManager)) {
        $commandLineArchive = Get-VerifiedDownload `
            -Url 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' `
            -FileName 'commandlinetools-win-15859902_latest.zip' `
            -Sha256 '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
        $stagingRoot = Join-Path $toolsRoot 'unpack-commandline'
        Expand-Archive -LiteralPath $commandLineArchive -DestinationPath $stagingRoot -Force
        $commandLineRoot = Join-Path $sdkRoot 'cmdline-tools\latest'
        New-Item -ItemType Directory -Path $commandLineRoot -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $stagingRoot 'cmdline-tools') |
            Copy-Item -Destination $commandLineRoot -Recurse -Force
    }

    if (-not (Test-Path -LiteralPath (Join-Path $gradleRoot 'bin\gradle.bat'))) {
        $gradleArchive = Get-VerifiedDownload `
            -Url 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' `
            -FileName 'gradle-8.11.1-bin.zip' `
            -Sha256 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
        Expand-Archive -LiteralPath $gradleArchive -DestinationPath $toolsRoot -Force
    }

    $androidJar = Join-Path $sdkRoot 'platforms\android-36\android.jar'
    $aaptExecutable = Join-Path $sdkRoot 'build-tools\35.0.0\aapt2.exe'
    if (-not ((Test-Path -LiteralPath $androidJar) -and (Test-Path -LiteralPath $aaptExecutable))) {
        Write-Host 'Installing Android SDK platform 36 and build-tools 35.0.0; output is saved in .tools/logs/sdk-install.log.'
        1..30 | ForEach-Object { 'y' } |
            & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;35.0.0' '--channel=0' *> (Join-Path $logRoot 'sdk-install.log')
        if ($LASTEXITCODE -ne 0) {
            throw "SDK installation failed. See $(Join-Path $logRoot 'sdk-install.log')"
        }
    }

    $sdkProperty = 'sdk.dir=' + $sdkRoot.Replace('\', '/').Replace(':', '\:')
    $localPropertiesPath = Join-Path $PSScriptRoot 'local.properties'
    $properties = @()
    if (Test-Path -LiteralPath $localPropertiesPath) {
        $properties = @(Get-Content -LiteralPath $localPropertiesPath | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*[:=]' })
    }
    @($properties) + $sdkProperty | Set-Content -LiteralPath $localPropertiesPath -Encoding ascii

    if ($SetupOnly) {
        Write-Host "Android SDK ready: $sdkRoot"
        Write-Host "Gradle ready: $gradleRoot"
        return
    }

    if (-not $GradleArguments -or $GradleArguments.Count -eq 0) { $GradleArguments = @(':app:assembleDebug') }
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & (Join-Path $gradleRoot 'bin\gradle.bat') '--no-daemon' '--console=plain' @GradleArguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}
finally {
    foreach ($name in $environmentNames) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}
