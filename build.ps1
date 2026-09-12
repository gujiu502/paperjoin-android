param(
    [string[]]$Task = @('assembleDebug'),
    [switch]$Install,
    [string]$Serial = '127.0.0.1:5555'
)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
if (-not $env:JAVA_HOME) {
    $candidateJdk = Join-Path $env:ProgramFiles 'Java\jdk-17'
    if (Test-Path -LiteralPath (Join-Path $candidateJdk 'bin\java.exe')) {
        $env:JAVA_HOME = $candidateJdk
    }
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'gradlew.bat') @Task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }

    $variant = if ($Task -match 'assembleRelease') { 'release' } else { 'debug' }
    $apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\$variant\app-$variant.apk"
    if (Test-Path -LiteralPath $apkPath) {
        $outputDirectory = Join-Path $PSScriptRoot 'output'
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
        Copy-Item -LiteralPath $apkPath -Destination (Join-Path $outputDirectory 'PaperJoin.apk') -Force
        Write-Host "APK: $(Join-Path $outputDirectory 'PaperJoin.apk')"
    }

    if ($Install) {
        if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the debug APK before installation.' }
        & adb -s $Serial install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw "ADB installation failed with exit code $LASTEXITCODE." }
    }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
