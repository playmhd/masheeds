# Requires PowerShell 7. Run with -Apply to update published audio.
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../local.properties') |
    Where-Object { $_ -match '^SUPABASE_(URL|PUBLISHABLE_KEY)=' } | ConvertFrom-StringData
$base = ([string]$settings.SUPABASE_URL).TrimEnd('/')
$publicKey = [string]$settings.SUPABASE_PUBLISHABLE_KEY
if ($base -notmatch '^https://[a-z0-9]+\.supabase\.co$' -or $publicKey -notmatch '^sb_publishable_[a-z0-9_-]+$') {
    throw 'Set the project URL and publishable key in local.properties.'
}

$publicPrefix = "$base/storage/v1/object/public/discover/"
$catalogRequest = @{
    Uri = "$base/rest/v1/discover_tracks?published=eq.true&select=audio_url"
    Headers = @{ apikey = $publicKey }
    MaximumRedirection = 0
    TimeoutSec = 30
}
$catalog = Invoke-RestMethod @catalogRequest
$urls = @($catalog | ForEach-Object { [uri]$_.audio_url } | Where-Object {
    $_.IsAbsoluteUri -and $_.AbsoluteUri.StartsWith($publicPrefix, [StringComparison]::Ordinal) -and
    $_.AbsolutePath.EndsWith('.mp3', [StringComparison]::OrdinalIgnoreCase) -and !$_.Query -and !$_.Fragment
} | Select-Object -Unique)

function Get-AudioRange([string]$Url) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri $Url -Headers @{ Range = 'bytes=0-1023' } -MaximumRedirection 0 -TimeoutSec 30
    $watch.Stop()
    if ($response.StatusCode -ne 206 -or ($response.Headers['Content-Range'] -join '') -notmatch '^bytes 0-\d+/\d+$') {
        throw 'Expected a partial audio response.'
    }
    @{
        Seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 2)
        CacheControl = $response.Headers['Cache-Control'] -join ', '
        CacheStatus = $response.Headers['cf-cache-status'] -join ', '
    }
}

function Get-OriginUrl([string]$Url) {
    $Url + '?cacheNonce=' + [Guid]::NewGuid().ToString('N')
}

function Save-Audio([string]$Url, [string]$Path) {
    Invoke-WebRequest -Uri (Get-OriginUrl $Url) -OutFile $Path -PassThru -MaximumRedirection 0 -TimeoutSec 180
}

$target = 'max-age=86400'
$authorization = $null
if ($Apply -and $urls.Count) {
    $secret = $env:SUPABASE_SECRET_KEY
    if (!$secret) {
        $secure = Read-Host "Existing Supabase secret key for $base" -AsSecureString
        $secret = [Net.NetworkCredential]::new('', $secure).Password
    }
    if ($secret -notmatch '^sb_secret_[a-z0-9_-]+$') {
        throw 'Use an existing server-side sb_secret_ key from the project API Keys page.'
    }
    $authorization = @{ apikey = $secret; 'Cache-Control' = $target }
    $secret = $null
}

$index = 0
foreach ($url in $urls) {
    $index++
    $range = Get-AudioRange $url.AbsoluteUri
    "MP3 $index of $($urls.Count). HTTP 206 in $($range.Seconds)s. $($range.CacheStatus) $($range.CacheControl)"
    if (!$Apply) {
        continue
    }

    $before = New-TemporaryFile
    $after = New-TemporaryFile
    try {
        $original = Save-Audio $url.AbsoluteUri $before.FullName
        if ($original.StatusCode -ne 200 -or (Get-Item $before.FullName).Length -le 0 -or
            ($original.Headers['Content-Type'] -join '') -notmatch '^audio/mpeg(?:;|$)') {
            throw "MP3 $index did not download as a complete audio file."
        }
        $hash = (Get-FileHash -LiteralPath $before.FullName -Algorithm SHA256).Hash
        $currentControl = $original.Headers['Cache-Control'] -join ', '
        if ($currentControl -notmatch '\bmax-age=86400\b') {
            $path = $url.AbsoluteUri.Substring($publicPrefix.Length)
            $update = @{
                Method = 'Put'
                Uri = "$base/storage/v1/object/discover/$path"
                InFile = $before.FullName
                ContentType = 'audio/mpeg'
                Headers = $authorization
                MaximumRedirection = 0
                TimeoutSec = 180
            }
            $null = Invoke-WebRequest @update
        }

        $verified = Save-Audio $url.AbsoluteUri $after.FullName
        if ($verified.StatusCode -ne 200 -or
            ($verified.Headers['Cache-Control'] -join ', ') -notmatch '\bmax-age=86400\b' -or
            (Get-FileHash -LiteralPath $after.FullName -Algorithm SHA256).Hash -ne $hash) {
            throw "MP3 $index failed checksum or cache verification. Check Storage before retrying."
        }
        $null = Get-AudioRange (Get-OriginUrl $url.AbsoluteUri)
        "MP3 $index verified. SHA-256 $hash, 24-hour cache, HTTP 206."
    } finally {
        Remove-Item -LiteralPath $before.FullName, $after.FullName -ErrorAction SilentlyContinue
    }
}

if (!$Apply) {
    "Checked $($urls.Count) published MP3s. Run with -Apply to update their cache lifetime."
}
