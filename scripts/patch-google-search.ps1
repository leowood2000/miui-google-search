param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$Package = 'com.android.quicksearchbox'
$Root = "/data/user/0/$Package"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("miui-google-search-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: adb -s $Serial $($Arguments -join ' ')" }
}

function Read-DeviceText {
    param([string]$Path)
    $localPath = Join-Path $TempRoot ("read-" + [guid]::NewGuid().ToString('N') + '.tmp')
    & adb -s $Serial exec-out su -M -c "cat '$Path'" > $localPath
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $Path" }
    try { return Get-Content -LiteralPath $localPath -Raw }
    finally { Remove-Item -LiteralPath $localPath -Force -ErrorAction SilentlyContinue }
}

function Write-DeviceText {
    param([string]$LocalPath, [string]$RemotePath)
    $remoteTmp = "/data/local/tmp/miui-google-search-$(Split-Path $RemotePath -Leaf)"
    Invoke-Adb push $LocalPath $remoteTmp | Out-Null
    Invoke-Adb shell su -M -c "cp '$remoteTmp' '$RemotePath'"
    Invoke-Adb shell su -M -c "rm -f '$remoteTmp'"
}

function Backup-And-Replace {
    param([string]$LocalPath, [string]$RemotePath)
    $backup = "$RemotePath.codexbak-google-search"
    & adb -s $Serial shell su -M -c "ls '$backup'" *> $null
    if ($LASTEXITCODE -ne 0) {
        Invoke-Adb shell su -M -c "cp -p '$RemotePath' '$backup'"
    }
    Write-DeviceText $LocalPath $RemotePath
}

New-Item -ItemType Directory -Force $TempRoot | Out-Null
try {
    Invoke-Adb shell am force-stop $Package

    $findCommand = "find '$Root/files/data/websearch' -name searchengineNew.json -type f 2>/dev/null"
    $paths = (& adb -s $Serial shell su -M -c $findCommand) | Where-Object { $_ -match 'searchengineNew\.json$' }
    if (-not $paths) { throw 'No searchengineNew.json was found on the device.' }

    foreach ($remotePath in $paths) {
        $locale = if ($remotePath -match 'websearch-zh-CN') { 'zh-CN' } else { 'en' }
        $localPath = Join-Path $TempRoot "searchengine-$locale.json"
        $text = Read-DeviceText $remotePath
        $json = $text | ConvertFrom-Json
        $json.data.defaultSearchEngineMap.globalSearchSearchBox = 'google'
        $json.data.defaultSearchEngineMap.globalSearchHotList = 'google'

        foreach ($sceneName in @('globalSearchSearchBox', 'globalSearchHotList')) {
            foreach ($engine in $json.data.searchEngineSceneMap.$sceneName.searchEngines) {
                if ($engine.searchEngineName -eq '360') {
                    $engine.searchEngineName = 'google'
                    $engine.searchUrl = 'https://www.google.com/search?q={searchTerms}'
                    $engine.iconUrl = 'https://www.google.com/favicon.ico'
                    $engine.title_zh_CN = 'Google'
                    $engine.title_zh_TW = 'Google'
                    $engine.title_en_US = 'Google'
                    $engine.title_bo_CN = 'Google'
                    $engine.title_ug_CN = 'Google'
                    $engine.channelNo = 'google'
                }
            }
        }

        $json | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $localPath -Encoding utf8
        Backup-And-Replace $localPath $remotePath
    }

    $searchEngineRemote = "$Root/shared_prefs/searchEngine.xml"
    $searchEngineLocal = Join-Path $TempRoot 'searchEngine.xml'
    $searchEngineText = Read-DeviceText $searchEngineRemote
    $searchEngineText = $searchEngineText -replace '(<string name="current_engine">)[^<]+', '${1}google'
    $searchEngineText = $searchEngineText -replace '(&quot;b&quot;:&quot;)[^&]+(&quot;)', '${1}google${2}'
    Set-Content -LiteralPath $searchEngineLocal -Value $searchEngineText -Encoding utf8
    Backup-And-Replace $searchEngineLocal $searchEngineRemote

    $settingsRemote = "$Root/shared_prefs/SearchSettings.xml"
    $settingsLocal = Join-Path $TempRoot 'SearchSettings.xml'
    $settingsText = Read-DeviceText $settingsRemote
    $settingsText = $settingsText -replace '(<string name="common_setting_engine">)[^<]+', '${1}google'
    Set-Content -LiteralPath $settingsLocal -Value $settingsText -Encoding utf8
    Backup-And-Replace $settingsLocal $settingsRemote

    Write-Host "Patched $Package. Restart the global search UI to apply it."
}
finally {
    if (Test-Path -LiteralPath $TempRoot) { Remove-Item -LiteralPath $TempRoot -Recurse -Force }
}
