param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$Package = 'com.android.quicksearchbox'
$Root = "/data/user/0/$Package"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: adb -s $Serial $($Arguments -join ' ')" }
}

Invoke-Adb shell am force-stop $Package
$paths = (& adb -s $Serial shell su -M -c "find '$Root/files/data/websearch' -name searchengineNew.json -type f 2>/dev/null") | Where-Object { $_ -match 'searchengineNew\.json$' }
$restorePaths = @($paths) + @("$Root/shared_prefs/searchEngine.xml", "$Root/shared_prefs/SearchSettings.xml")

foreach ($path in $restorePaths) {
    $backup = "$path.codexbak-google-search"
    & adb -s $Serial shell su -M -c "ls '$backup'" *> $null
    if ($LASTEXITCODE -eq 0) {
        Invoke-Adb shell su -M -c "cp '$backup' '$path'"
        Write-Host "Restored $path"
    }
}

Write-Host "Original search configuration restored where backups were present."
