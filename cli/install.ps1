# One-time setup: puts this directory (cli/) on the current user's PATH
# so the global `oepm` command works from inside any oepm-managed
# project, without a .\oepm.bat prefix and without touching each project
# individually. Safe to re-run - does nothing if already set up. See
# README.md's "Per-machine setup" for why this is a *separate* script
# from the per-project oepm/oepm.bat, not the same thing installed
# differently.

$cliDir = $PSScriptRoot
$currentUserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
$entries = $currentUserPath -split ";" | Where-Object { $_ -ne "" }

$alreadyPresent = $entries | Where-Object {
    $_.TrimEnd('\') -ieq $cliDir.TrimEnd('\')
}

if ($alreadyPresent) {
    Write-Host "Already set up - $cliDir is already on PATH."
    exit 0
}

$newPath = if ($currentUserPath) { "$currentUserPath;$cliDir" } else { $cliDir }
[Environment]::SetEnvironmentVariable("PATH", $newPath, "User")

Write-Host "Added $cliDir to PATH."
Write-Host "Open a new terminal for it to take effect."
