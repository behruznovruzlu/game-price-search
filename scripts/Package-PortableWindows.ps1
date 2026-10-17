[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$CreateZip
)

$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Package-PortableWindows.ps1 must be run on Windows.'
}

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$rootPrefix = $projectRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

function Get-ProjectPath([string]$RelativePath) {
    $resolvedPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $RelativePath))
    if (-not $resolvedPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Packaging path is outside the project: $resolvedPath"
    }
    return $resolvedPath
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaCommand = Get-Command 'java.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $javaCommand) {
        throw 'A full JDK 21 is required. Set JAVA_HOME or pass -JavaHome.'
    }
    $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
}

$JavaHome = [System.IO.Path]::GetFullPath($JavaHome)
$jlinkExe = Join-Path $JavaHome 'bin\jlink.exe'
$javaExe = Join-Path $JavaHome 'bin\java.exe'
$jmodsDirectory = Join-Path $JavaHome 'jmods'
$releasePath = Join-Path $JavaHome 'release'
if (-not (Test-Path -LiteralPath $jlinkExe -PathType Leaf) -or
    -not (Test-Path -LiteralPath $javaExe -PathType Leaf) -or
    -not (Test-Path -LiteralPath $jmodsDirectory -PathType Container) -or
    -not (Test-Path -LiteralPath $releasePath -PathType Leaf)) {
    throw "The selected Java directory must be a full JDK 21 with jlink, java, jmods and release: $JavaHome"
}

$releaseText = Get-Content -LiteralPath $releasePath -Raw
if ($releaseText -notmatch '(?m)^JAVA_VERSION="(?<version>21(?:[.\-+][^"]*)?)"\r?$') {
    throw "The selected packaging JDK must be Java 21: $JavaHome"
}
$runtimeVersion = $Matches['version']

$applicationVersion = '1.0.0'
$jarName = "game-price-search-$applicationVersion.jar"
$jarPath = Get-ProjectPath "target\$jarName"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Built application JAR not found: $jarPath. Run mvn clean verify first."
}

$packageId = [Guid]::NewGuid().ToString('N')
$packageDirectory = Get-ProjectPath "dist\windows\portable-$packageId"
$imageDirectory = Join-Path $packageDirectory 'GamePriceSearch'
$runtimeDirectory = Join-Path $imageDirectory 'runtime'
$appDirectory = Join-Path $imageDirectory 'app'
$logPath = Get-ProjectPath "target\portable-jlink-$packageId.log"
if (Test-Path -LiteralPath $packageDirectory) {
    throw "The fresh packaging directory already exists: $packageDirectory"
}

[void][System.IO.Directory]::CreateDirectory($appDirectory)

# Keep the ordinary Java launchers required by the CMD script. java.net.http
# and the crypto modules support HTTPS, including the Windows trust store.
$jlinkArguments = @(
    '--module-path', $jmodsDirectory,
    '--add-modules', 'java.base,java.desktop,java.logging,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.crypto.mscapi',
    '--output', $runtimeDirectory,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages'
)

$previousPreference = $ErrorActionPreference
try {
    # Capture native stderr as well, including with Windows PowerShell 5.1.
    $ErrorActionPreference = 'Continue'
    $buildOutput = @(& $jlinkExe @jlinkArguments 2>&1)
    $buildExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
$logLines = @($buildOutput | ForEach-Object { $_.ToString() })
Set-Content -LiteralPath $logPath -Value ([string]::Join([Environment]::NewLine, [string[]]$logLines)) -Encoding UTF8
Write-Output "Runtime build diagnostics: $logPath"
if ($buildExitCode -ne 0) {
    throw "jlink failed with exit code $buildExitCode. Incomplete build retained at $packageDirectory."
}

foreach ($requiredRuntimeFile in @('bin\java.exe', 'bin\javaw.exe', 'lib\modules', 'release')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeDirectory $requiredRuntimeFile) -PathType Leaf)) {
        throw "jlink did not produce required runtime file $requiredRuntimeFile. Build retained at $packageDirectory."
    }
}

# Copy only the built application; previous packages and user files stay outside.
$packagedJar = Join-Path $appDirectory $jarName
Copy-Item -LiteralPath $jarPath -Destination $packagedJar
$sourceJarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
$packagedJarHash = (Get-FileHash -LiteralPath $packagedJar -Algorithm SHA256).Hash
if ($sourceJarHash -ne $packagedJarHash) {
    throw "Packaged application JAR hash does not match the build: $packagedJar"
}

# %~dp0 resolves files beside the launcher, independently of the caller's cwd.
# Explicit arguments use the console runtime and retain the caller's quoting.
$launcher = @'
@echo off
setlocal DisableDelayedExpansion
set "GAME_PRICE_JAVA=%~dp0runtime\bin\javaw.exe"
set "GAME_PRICE_CONSOLE_JAVA=%~dp0runtime\bin\java.exe"
set "GAME_PRICE_JAR=%~dp0app\__JAR_NAME__"
if not exist "%GAME_PRICE_JAVA%" goto missing_runtime
if not exist "%GAME_PRICE_CONSOLE_JAVA%" goto missing_runtime
if not exist "%GAME_PRICE_JAR%" goto missing_jar
if "%~1"=="" goto open_browser
"%GAME_PRICE_CONSOLE_JAVA%" -Xmx512m -jar "%GAME_PRICE_JAR%" %*
exit /b %errorlevel%
:open_browser
start "Game Price Search" "%GAME_PRICE_JAVA%" -Xmx512m -jar "%GAME_PRICE_JAR%" --open-browser
if errorlevel 1 goto launch_failed
exit /b 0
:missing_runtime
echo Game Price Search cannot start: the bundled Java runtime is missing.
echo Extract the whole GamePriceSearch folder before starting GamePriceSearch.cmd.
pause
exit /b 1
:missing_jar
echo Game Price Search cannot start: the application JAR is missing.
echo Keep GamePriceSearch.cmd together with its app and runtime folders.
pause
exit /b 1
:launch_failed
echo Game Price Search could not start its bundled Java runtime.
pause
exit /b 1
'@
$launcher = $launcher.Replace('__JAR_NAME__', $jarName)
[System.IO.File]::WriteAllText((Join-Path $imageDirectory 'GamePriceSearch.cmd'), ($launcher -replace '\r?\n', "`r`n"), [System.Text.Encoding]::ASCII)

$status = @(
    'UNSIGNED DEVELOPMENT PACKAGE',
    "Application version: $applicationVersion",
    "Bundled runtime version: $runtimeVersion",
    'Launcher: GamePriceSearch.cmd (plain Windows command script).',
    'The complete app and runtime folders are required.',
    'No trusted application publisher signature or antivirus clearance is claimed.',
    'No application was started by this packaging script.'
)
$status | Set-Content -LiteralPath (Join-Path $imageDirectory 'DEVELOPMENT-STATUS.txt') -Encoding UTF8

$instructions = @(
    'Game Price Search - Windows portable development package',
    '',
    'Extract the whole GamePriceSearch folder. Double-click GamePriceSearch.cmd.',
    'Your default browser opens the local application. No Java installation or terminal command is required.',
    'Use Quit app in the browser to stop it; closing the browser tab alone leaves it running.',
    'Searches require an internet connection and use the public CheapShark API. Prices are USD.',
    'Search a title, select a game and compare store offers. Saved games stay in this browser localStorage.',
    'Saved games are specific to this browser and local address; they are not synced to an account.',
    'Prices are fetched on demand; no background monitoring, email or price notifications are implemented.',
    '',
    'This is an unsigned development package. See DEVELOPMENT-STATUS.txt.',
    'SHA256SUMS.txt records file hashes; it is not a digital signature or antivirus approval.'
)
$instructions | Set-Content -LiteralPath (Join-Path $imageDirectory 'README.txt') -Encoding UTF8

$imagePrefix = $imageDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$checksums = @(Get-ChildItem -LiteralPath $imageDirectory -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relativePath = $_.FullName.Substring($imagePrefix.Length).Replace('\', '/')
    $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$fileHash  $relativePath"
})
$checksums | Set-Content -LiteralPath (Join-Path $imageDirectory 'SHA256SUMS.txt') -Encoding ASCII

if ($CreateZip) {
    $zipPath = Join-Path $packageDirectory "GamePriceSearch-$applicationVersion-windows-portable.zip"
    Compress-Archive -LiteralPath $imageDirectory -DestinationPath $zipPath -CompressionLevel Optimal

    # Validate the archive without extracting or starting the application.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $archiveNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($requiredArchiveFile in @('GamePriceSearch/GamePriceSearch.cmd', "GamePriceSearch/app/$jarName", 'GamePriceSearch/runtime/bin/java.exe', 'GamePriceSearch/runtime/bin/javaw.exe', 'GamePriceSearch/README.txt', 'GamePriceSearch/DEVELOPMENT-STATUS.txt', 'GamePriceSearch/SHA256SUMS.txt')) {
            if ($archiveNames -notcontains $requiredArchiveFile) {
                throw "Portable ZIP is missing $requiredArchiveFile. Build retained at $packageDirectory."
            }
        }
    } finally {
        $archive.Dispose()
    }
    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$zipHash  $([System.IO.Path]::GetFileName($zipPath))" | Set-Content -LiteralPath "$zipPath.sha256" -Encoding ASCII
    Write-Output "Development ZIP: $zipPath"
}

Write-Output "Portable development launcher: $(Join-Path $imageDirectory 'GamePriceSearch.cmd')"
Write-Output 'Unsigned development package. No application was started.'
