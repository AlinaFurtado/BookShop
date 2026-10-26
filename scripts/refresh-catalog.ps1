# Optional: refresh metadata for the small hand-picked catalog. No API key is used.
# Original summaries and theme tags stay in catalog.json; only verified metadata is replaced.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$catalogPath = Join-Path $projectRoot 'myBooks\myBooks\src\main\resources\catalog.json'
$books = [System.IO.File]::ReadAllText($catalogPath) | ConvertFrom-Json
$verified = @()
$count = 0
foreach ($book in $books) {
    $titleQuery = [Uri]::EscapeDataString($book.title)
    $authorQuery = [Uri]::EscapeDataString($book.author)
    $url = "https://openlibrary.org/search.json?title=$titleQuery&author=$authorQuery&limit=3&fields=key,title,author_name,cover_i,ratings_average,ratings_count,first_publish_year"
    try {
        $response = Invoke-RestMethod -Uri $url -TimeoutSec 15 -Headers @{ 'User-Agent' = 'BookBuddy-LearningProject/1.0' }
        $normalize = { param($text) ($text.ToLowerInvariant() -replace '[^a-z0-9]', '') }
        $match = $response.docs | Where-Object {
            (& $normalize $_.title) -eq (& $normalize $book.title)
        } | Select-Object -First 1
        if ($match -and $match.key -match '^/works/OL[0-9]+W$') {
            $book.id = $match.key
            if ($match.cover_i) { $book.coverId = [int]$match.cover_i }
            if ($match.ratings_count -gt 0 -and $null -ne $match.ratings_average) {
                $book.rating = [double]$match.ratings_average
                $book.ratingCount = [int]$match.ratings_count
            }
            if ($match.first_publish_year) { $book.year = [int]$match.first_publish_year }
            $verified += [pscustomobject]@{ title = $book.title; url = $url; work = $match.key }
            $count++
        }
    } catch { Write-Warning "Metadata unavailable for $($book.title); retaining existing values." }
    Write-Host "$count verified so far: $($book.title)"
    Start-Sleep -Milliseconds 1100
}
$encoding = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($catalogPath, ($books | ConvertTo-Json -Depth 10), $encoding)
$provenance = [pscustomobject]@{
    checkedAt = (Get-Date).ToUniversalTime().ToString('o')
    note = 'Book descriptions and theme tags are original curated summaries. Ratings, work IDs and cover IDs below were retrieved from Open Library; ratings can change.'
    verified = $verified
}
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'catalog-sources.json'), ($provenance | ConvertTo-Json -Depth 8), $encoding)
Write-Host "Saved $count verified books. Rebuild/restart the backend to load the refreshed catalog."
