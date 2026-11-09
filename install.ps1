<#
.SYNOPSIS
  Installeert AKO Notulist via adb met de Play Store als installer, zodat Android Auto de app toont.
.EXAMPLE
  .\install.ps1
  .\install.ps1 -Apk C:\Downloads\AKO-Notulist-1.0.0.apk
#>
param(
    [string]$Apk = ""
)
$ErrorActionPreference = "Stop"

if (-not $Apk) {
    $latest = Get-ChildItem -Path "app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest) { $Apk = $latest.FullName }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Error "Geen APK gevonden. Bouw eerst met '.\gradlew.bat assembleRelease' of geef -Apk <pad> mee."
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb niet gevonden. Installeer de Android SDK Platform-Tools en voeg de map toe aan je PATH."
}

$devices = (& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }
if (-not $devices) {
    Write-Error "Geen toestel gevonden. Zet USB-foutopsporing aan en bevestig de vingerafdruk op je telefoon."
}
if (@($devices).Count -gt 1 -and -not $env:ANDROID_SERIAL) {
    Write-Host "Meerdere toestellen gevonden:" ; $devices | ForEach-Object { Write-Host "  $_" }
    Write-Error "Kies er één met: `$env:ANDROID_SERIAL='<serial>'; .\install.ps1"
}

Write-Host "Installeren: $Apk"
# -r = bijwerken met behoud van gegevens, -i com.android.vending = Play Store als installer
& adb install -r -i com.android.vending "$Apk"
if ($LASTEXITCODE -ne 0) { Write-Error "Installatie mislukt (exitcode $LASTEXITCODE)." }

$info = (& adb shell pm list packages -i nl.arnoldkolkman.akonotulist) -join "`n"
Write-Host $info
if ($info -notmatch "installer=com.android.vending") {
    Write-Warning "De installer is NIET de Play Store; Android Auto toont de app dan niet."
    Write-Warning "Staat de Play Store (com.android.vending) op dit toestel? Zonder Play Store wordt -i genegeerd."
    exit 2
}
Write-Host "Klaar. Open AKO Notulist op je telefoon en stel je OpenAI API-sleutel in."
