<#
    Собирает TgRelay.jar без Gradle и Maven.

    Компиляция идёт против API сервера: paperclip-джарник API не содержит, оно лежит
    в libraries — там же, где сервер держит его для себя. Инструменты берём из того JDK,
    на котором работает сам сервер (иначе в PATH попадается шим javapath без jar.exe).

    Запуск:
        powershell -ExecutionPolicy Bypass -File build.ps1
        powershell -ExecutionPolicy Bypass -File build.ps1 -ServerRoot "C:\MCServer"
#>
param(
    [string]$ServerRoot = 'C:\build.losos.world',
    [string]$OutJar = 'C:\build.losos.world\_diag\TgRelay.jar'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- инструменты JDK ---------------------------------------------------------

$jdkBin = $null
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and (Test-Path $_.ExecutablePath) } | Select-Object -First 1
if ($running) { $jdkBin = Split-Path $running.ExecutablePath }

# java.exe в PATH часто оказывается шимом javapath, где нет ни javac, ни jar —
# тогда ищем настоящий JDK в стандартных местах
$needsJdk = (-not $jdkBin) -or (-not (Test-Path (Join-Path $jdkBin 'javac.exe'))) -or (-not (Test-Path (Join-Path $jdkBin 'jar.exe')))
if ($needsJdk) {
    $roots = @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Zulu', 'C:\Program Files\Microsoft')
    $found = @()
    foreach ($r in $roots) {
        $found += Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\jar.exe') }
    }
    $candidate = $found | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($candidate) { $jdkBin = Join-Path $candidate.FullName 'bin' }
}

if (-not $jdkBin) { throw 'не нашёл JDK с javac.exe' }
$javac = Join-Path $jdkBin 'javac.exe'
$jarTool = Join-Path $jdkBin 'jar.exe'
"JDK: $jdkBin"

# --- classpath ---------------------------------------------------------------

$apiJar = Get-ChildItem (Join-Path $ServerRoot 'libraries\io\papermc\paper\paper-api') -Recurse -Filter 'paper-api-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apiJar) { throw "не нашёл paper-api в $ServerRoot\libraries" }

$adventure = Get-ChildItem (Join-Path $ServerRoot 'libraries\net\kyori') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' }
$bungee = Get-ChildItem (Join-Path $ServerRoot 'libraries\net\md-5') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue

$classpath = (@($apiJar.FullName) + @($adventure.FullName) + @($bungee.FullName)) -join ';'
"classpath: $($apiJar.Name) + $(@($adventure).Count) adventure + $(@($bungee).Count) bungee"

# --- сборка ------------------------------------------------------------------

$classes = Join-Path $env:TEMP ('tgrelay-classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$argfile = Join-Path $env:TEMP ('tgrelay-' + [guid]::NewGuid().ToString('N') + '.args')
$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java

# javac в @argfile считает обратный слэш экранирующим — пути пишем через прямые
$lines = @('--release', '21', '-encoding', 'UTF-8', '-nowarn',
           '-d', "`"$($classes.Replace('\', '/'))`"",
           '-cp', "`"$($classpath.Replace('\', '/'))`"")
$lines += $sources | ForEach-Object { "`"$($_.FullName.Replace('\', '/'))`"" }
[System.IO.File]::WriteAllLines($argfile, $lines, (New-Object System.Text.ASCIIEncoding))

& $javac "@$argfile"
if ($LASTEXITCODE -ne 0) { throw 'компиляция не прошла' }

Copy-Item (Join-Path $root 'src\main\resources\*') $classes -Force

if (Test-Path $OutJar) { Remove-Item $OutJar -Force }
Push-Location $classes
try {
    & $jarTool --create --file $OutJar --main-class world.losos.tgrelay.TgRelay . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'сборка jar не прошла' }
} finally {
    Pop-Location
}

Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $argfile -Force -ErrorAction SilentlyContinue
"готово: $OutJar ({0:N0} Б)" -f (Get-Item $OutJar).Length
