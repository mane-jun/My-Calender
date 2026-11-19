param(
    [string]$StudioHome = 'C:\Program Files\Android\Android Studio',
    [string]$SdkHome = $env:ANDROID_HOME,
    [switch]$TestsOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Failed with exit code {0}: {1}' -f $LASTEXITCODE, $Program) }
}

if (-not $SdkHome) { $SdkHome = $env:ANDROID_SDK_ROOT }
if (-not $SdkHome) { $SdkHome = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'build'
$java = Join-Path $StudioHome 'jbr\bin\java.exe'
$javac = Join-Path $StudioHome 'jbr\bin\javac.exe'
$jar = Join-Path $StudioHome 'jbr\bin\jar.exe'
$keytool = Join-Path $StudioHome 'jbr\bin\keytool.exe'
$kotlin = Join-Path $StudioHome 'plugins\Kotlin\kotlinc'
$kotlinCompiler = Join-Path $kotlin 'lib\*'
$stdlib = Join-Path $kotlin 'lib\kotlin-stdlib.jar'
$compilerEntry = 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler'

New-Item -ItemType Directory -Force -Path $output | Out-Null
$main = Join-Path $root 'app\src\main'
$test = Join-Path $root 'app\src\test\java\com\mycalender\CoreChecks.kt'
$core = (Get-ChildItem -LiteralPath (Join-Path $main 'java\com\mycalender\tasks') -File |
    Where-Object Name -In 'Schedule.kt','TaskRepository.kt').FullName
$checks = Join-Path $output 'core-checks.jar'
Invoke-Checked $java (@('-cp', $kotlinCompiler, $compilerEntry, '-kotlin-home', $kotlin,
    '-jvm-target', '1.8', '-d', $checks) + $core + $test)
Invoke-Checked $java @('-cp', "$checks;$stdlib", 'com.mycalender.CoreChecksKt')
if ($TestsOnly) { return }
if (-not (Test-Path -LiteralPath $SdkHome)) { throw 'Set -SdkHome to the Android SDK installation path.' }

$platform = Get-ChildItem -LiteralPath (Join-Path $SdkHome 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-(\d+)(\.\d+)?$' -and (Test-Path (Join-Path $_.FullName 'android.jar')) } |
    Sort-Object { [version]($_.Name.Substring(8) + $(if ($_.Name.Contains('.')) { '' } else { '.0' })) } -Descending |
    Select-Object -First 1
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkHome 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $platform -or -not $buildTools) { throw 'Install an Android SDK platform (API 35 or higher) and Android Build-Tools.' }
$android = Join-Path $platform.FullName 'android.jar'
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$d8 = Join-Path $buildTools.FullName 'lib\d8.jar'
$studioD8 = Join-Path $StudioHome 'plugins\android\lib\r8.jar'
if (Test-Path -LiteralPath $studioD8) { $d8 = $studioD8 }
$apksigner = Join-Path $buildTools.FullName 'lib\apksigner.jar'
$work = Join-Path $output ('package-' + [guid]::NewGuid().ToString('N'))
$generated = Join-Path $work 'generated'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Path $work,$generated,$classes,$dex | Out-Null

try {
    $resources = Join-Path $work 'resources.zip'
    $unsigned = Join-Path $work 'unsigned.apk'
    $aligned = Join-Path $work 'aligned.apk'
    $code = Join-Path $work 'app-classes.jar'
    $app = Join-Path $output 'My-Calender-debug.apk'

    Invoke-Checked $aapt2 @('compile', '--dir', (Join-Path $main 'res'), '-o', $resources)
    Invoke-Checked $aapt2 @('link', '-I', $android, '--manifest', (Join-Path $main 'AndroidManifest.xml'),
        '--java', $generated, '-o', $unsigned, $resources)
    $generatedCode = (Get-ChildItem -LiteralPath $generated -Recurse -File -Filter '*.java').FullName
    Invoke-Checked $javac (@('--release', '8', '-classpath', $android, '-d', $classes) + $generatedCode)
    $sources = (Get-ChildItem -LiteralPath (Join-Path $main 'java') -Recurse -File -Filter '*.kt').FullName
    Invoke-Checked $java (@('-cp', $kotlinCompiler, $compilerEntry, '-kotlin-home', $kotlin,
        '-jvm-target', '1.8', '-classpath', "$android;$classes", '-d', $classes) + $sources)
    Invoke-Checked $jar @('cf', $code, '-C', $classes, '.')
    Invoke-Checked $java @('-cp', $d8, 'com.android.tools.r8.D8', '--lib', $android,
        '--min-api', '26', '--output', $dex, $code, $stdlib)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $dex -File -Filter 'classes*.dex') {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $file.Name, [IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally { $archive.Dispose() }

    Invoke-Checked $zipalign @('-P', '16', '-f', '4', $unsigned, $aligned)
    $keystore = Join-Path $output 'mycalender-debug.keystore'
    if (-not (Test-Path -LiteralPath $keystore)) {
        Invoke-Checked $keytool @('-genkeypair', '-keystore', $keystore, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'mycalenderdebug', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '3650', '-dname', 'CN=My-Calender Development, O=Local Project, C=KR')
    }
    Invoke-Checked $java @('-jar', $apksigner, 'sign', '--ks', $keystore,
        '--ks-pass', 'pass:android', '--ks-key-alias', 'mycalenderdebug', '--out', $app, $aligned)
    Invoke-Checked $java @('-jar', $apksigner, 'verify', '--verbose', $app)
    Write-Output "APK: $app"
} finally {
    $outputPath = [IO.Path]::GetFullPath($output).TrimEnd('\') + '\'
    $workPath = (Resolve-Path -LiteralPath $work).Path
    if ($workPath.StartsWith($outputPath, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $workPath -Recurse -Force
    }
}
