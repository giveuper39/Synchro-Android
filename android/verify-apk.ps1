<#
.SYNOPSIS
Checks debug APK signatures, ZIP page alignment, ELF64 LOAD/RELRO alignment,
expected ABIs and the packaged LGPL license without installing an NDK.
.DESCRIPTION
The default exit status checks signatures, ZIP alignment, ELF LOAD segments,
expected ABIs and the LGPL asset. RELRO is reported separately. -StrictRelro
also makes a non-aligned GNU_RELRO segment fail the command. Passing the default
checks alone does not establish complete 16 KB runtime compatibility.
#>
[CmdletBinding()]
param(
    [string]$ApkDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = (Join-Path (Split-Path -Parent $PSScriptRoot) '.tools\android-sdk'),
    [string]$BuildToolsVersion = '35.0.0',
    [ValidateSet('debug', 'release')][string]$BuildType = 'debug',
    [switch]$StrictRelro,
    [string]$JsonOutputPath
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ApkDirectory)) {
    $ApkDirectory = Join-Path $PSScriptRoot "app\build\outputs\apk\$BuildType"
}
$PSNativeCommandUseErrorActionPreference = $false
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Elf64Alignment {
    param([IO.Compression.ZipArchiveEntry]$Entry)
    $entryStream = $Entry.Open()
    $memoryStream = [IO.MemoryStream]::new()
    try {
        $entryStream.CopyTo($memoryStream)
        $bytes = $memoryStream.ToArray()
    }
    finally {
        $entryStream.Dispose()
        $memoryStream.Dispose()
    }
    if (-not [BitConverter]::IsLittleEndian -or $bytes.Length -lt 64 -or
        [BitConverter]::ToUInt32($bytes, 0) -ne 0x464c457f -or
        $bytes[4] -ne 2 -or $bytes[5] -ne 1) {
        throw "Expected little-endian ELF64: $($Entry.FullName)"
    }

    # Elf64_Ehdr: e_machine +18, e_phoff +32, e_phentsize +54, e_phnum +56.
    $machine = [BitConverter]::ToUInt16($bytes, 18)
    $programHeaderOffset = [BitConverter]::ToUInt64($bytes, 32)
    $programHeaderSize = [BitConverter]::ToUInt16($bytes, 54)
    $programHeaderCount = [BitConverter]::ToUInt16($bytes, 56)
    if ($programHeaderSize -lt 56 -or $programHeaderCount -eq 0 -or
        $programHeaderOffset -gt $bytes.Length -or
        ($programHeaderCount * $programHeaderSize) -gt ($bytes.Length - $programHeaderOffset)) {
        throw "Invalid ELF64 program-header table: $($Entry.FullName)"
    }

    $loads = @()
    $relros = @()
    for ($index = 0; $index -lt $programHeaderCount; $index++) {
        $offset = [int]($programHeaderOffset + $index * $programHeaderSize)
        # Elf64_Phdr: p_type +0, p_offset +8, p_vaddr +16, p_memsz +40, p_align +48.
        $type = [BitConverter]::ToUInt32($bytes, $offset)
        $fileOffset = [BitConverter]::ToUInt64($bytes, $offset + 8)
        $virtualAddress = [BitConverter]::ToUInt64($bytes, $offset + 16)
        $memorySize = [BitConverter]::ToUInt64($bytes, $offset + 40)
        $alignment = [BitConverter]::ToUInt64($bytes, $offset + 48)
        if ($type -eq 1) {
            $loads += [PSCustomObject]@{
                fileOffset = ('0x{0:x}' -f $fileOffset)
                virtualAddress = ('0x{0:x}' -f $virtualAddress)
                memorySize = ('0x{0:x}' -f $memorySize)
                alignment = $alignment
                aligned16KB = ($alignment -ge 16384 -and
                    ($alignment -band ($alignment - 1)) -eq 0 -and
                    $fileOffset % 16384 -eq $virtualAddress % 16384)
            }
        }
        if ($type -eq 0x6474e552) {
            if ($virtualAddress -gt ([ulong]::MaxValue - $memorySize)) {
                throw "Overflow in GNU_RELRO range: $($Entry.FullName)"
            }
            $end = $virtualAddress + $memorySize
            $relros += [PSCustomObject]@{
                virtualAddress = ('0x{0:x}' -f $virtualAddress)
                memorySize = ('0x{0:x}' -f $memorySize)
                end = ('0x{0:x}' -f $end)
                endModulo16384 = $end % 16384
                aligned16KB = ($end % 16384 -eq 0)
            }
        }
    }
    $abi = $Entry.FullName.Split('/')[1]
    $machineMatchesAbi = (($abi -eq 'arm64-v8a' -and $machine -eq 183) -or
        ($abi -eq 'x86_64' -and $machine -eq 62))
    return [PSCustomObject]@{
        entry = $Entry.FullName
        machine = $machine
        machineMatchesAbi = $machineMatchesAbi
        loadSegments = $loads
        relroSegments = $relros
        loadAligned16KB = ($machineMatchesAbi -and $loads.Count -gt 0 -and
            @($loads | Where-Object { -not $_.aligned16KB }).Count -eq 0)
        relroAligned16KB = (@($relros | Where-Object { -not $_.aligned16KB }).Count -eq 0)
    }
}

function Invoke-VerificationTool {
    param([string]$Executable, [string[]]$Arguments)
    $output = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    return [PSCustomObject]@{ exitCode = $LASTEXITCODE; output = $output }
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javacCommand = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javacCommand) { $JavaHome = Split-Path -Parent (Split-Path -Parent $javacCommand.Source) }
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or
    -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Pass -JavaHome <JDK-directory>, set JAVA_HOME, or add the JDK bin directory to PATH.'
}
$buildTools = Join-Path $AndroidHome "build-tools\$BuildToolsVersion"
$apksigner = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
foreach ($tool in @($apksigner, $zipalign)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing Android build tool: $tool" }
}
$sourceLicensePath = Join-Path $PSScriptRoot 'app\src\main\assets\LGPL-2.1.txt'
$sourceLicenseHash = (Get-FileHash -LiteralPath $sourceLicensePath -Algorithm SHA256).Hash.ToLowerInvariant()
$variants = @(
    @{ file = "app-arm64-v8a-$BuildType.apk"; abis = @('arm64-v8a') },
    @{ file = "app-x86_64-$BuildType.apk"; abis = @('x86_64') },
    @{ file = "app-universal-$BuildType.apk"; abis = @('arm64-v8a', 'x86_64') }
)
$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
try {
    $env:JAVA_HOME = $JavaHome
    $apks = @()
    foreach ($variant in $variants) {
        $apkPath = (Resolve-Path -LiteralPath (Join-Path $ApkDirectory $variant.file)).Path
        $signature = Invoke-VerificationTool -Executable $apksigner -Arguments @('verify', '--verbose', $apkPath)
        $zip = Invoke-VerificationTool -Executable $zipalign -Arguments @('-c', '-P', '16', '4', $apkPath)
        $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
        try {
            $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
            $abis = @($nativeEntries | ForEach-Object { $_.FullName.Split('/')[1] } | Sort-Object -Unique)
            $libraries = @($nativeEntries | ForEach-Object { Read-Elf64Alignment -Entry $_ })
            $license = $archive.GetEntry('assets/LGPL-2.1.txt')
            $licenseHash = $null
            if ($license) {
                $licenseStream = $license.Open()
                $shaAlgorithm = [Security.Cryptography.SHA256]::Create()
                try { $licenseHash = [BitConverter]::ToString($shaAlgorithm.ComputeHash($licenseStream)).Replace('-', '').ToLowerInvariant() }
                finally { $licenseStream.Dispose(); $shaAlgorithm.Dispose() }
            }
            $apks += [PSCustomObject]@{
                path = $apkPath
                bytes = (Get-Item -LiteralPath $apkPath).Length
                sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
                abis = $abis
                expectedAbis = $variant.abis
                expectedAbisMatch = (($abis -join ',') -eq (($variant.abis | Sort-Object) -join ','))
                licensePresent = ($null -ne $license)
                licenseBytes = $(if ($license) { $license.Length } else { 0 })
                licenseMatchesSource = ($licenseHash -eq $sourceLicenseHash)
                licenseSha256 = $licenseHash
                signatureVerified = ($signature.exitCode -eq 0)
                signatureOutput = $signature.output
                zipAligned16KB = ($zip.exitCode -eq 0)
                zipalignOutput = $zip.output
                elfLoadAligned16KB = ($libraries.Count -gt 0 -and @($libraries | Where-Object { -not $_.loadAligned16KB }).Count -eq 0)
                relroAligned16KB = ($libraries.Count -gt 0 -and @($libraries | Where-Object { -not $_.relroAligned16KB }).Count -eq 0)
                libraries = $libraries
            }
        }
        finally { $archive.Dispose() }
    }
}
finally {
    if ($null -eq $previousJavaHome) { Remove-Item -LiteralPath Env:JAVA_HOME -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable('JAVA_HOME', $previousJavaHome, 'Process') }
}

$corePass = (@($apks | Where-Object { -not ($_.signatureVerified -and $_.zipAligned16KB -and $_.elfLoadAligned16KB) }).Count -eq 0)
$relroPass = (@($apks | Where-Object { -not $_.relroAligned16KB }).Count -eq 0)
$metadataPass = (@($apks | Where-Object { -not ($_.expectedAbisMatch -and $_.licensePresent -and $_.licenseMatchesSource) }).Count -eq 0)
$report = [PSCustomObject]@{
    checkedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    reference = 'https://developer.android.com/guide/practices/page-sizes'
    signatureZipAndElfLoadPassed = $corePass
    abiAndLicensePassed = $metadataPass
    strictRelroRequested = [bool]$StrictRelro
    strictRelroPassed = $relroPass
    exitChecksPassed = ($corePass -and $metadataPass -and (-not $StrictRelro -or $relroPass))
    apks = $apks
}
if ($JsonOutputPath) {
    $outputPath = [IO.Path]::GetFullPath($JsonOutputPath)
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $outputPath -Encoding utf8
    Write-Host "JSON report: $outputPath"
}
foreach ($apk in $apks) {
    Write-Host "$([IO.Path]::GetFileName($apk.path)): signature=$($apk.signatureVerified), ZIP16KB=$($apk.zipAligned16KB), ELF_LOAD16KB=$($apk.elfLoadAligned16KB), RELRO16KB=$($apk.relroAligned16KB), ABI=$($apk.expectedAbisMatch), LGPL=$($apk.licenseMatchesSource)"
}
if (-not $metadataPass) { Write-Warning 'Expected ABI or LGPL asset checks failed; inspect the JSON report.' }
if (-not $relroPass) { Write-Warning 'GNU_RELRO is not 16 KB aligned in some libraries. Full 16 KB runtime compatibility is not established.' }
if (-not $report.exitChecksPassed) { exit 1 }
exit 0
