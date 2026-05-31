param(
    [string]$PlantUmlVersion = "1.2025.3"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tempJar = Join-Path ([System.IO.Path]::GetTempPath()) "plantuml-$PlantUmlVersion.jar"
$downloadUrl = "https://github.com/plantuml/plantuml/releases/download/v$PlantUmlVersion/plantuml-$PlantUmlVersion.jar"

if (-not (Test-Path $tempJar)) {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $tempJar -ErrorAction Stop
}

$pumlFiles = Get-ChildItem -Path $scriptDir -Filter "*.puml" | Select-Object -ExpandProperty FullName
if (-not $pumlFiles) {
    throw "No .puml files found in $scriptDir"
}

java -jar $tempJar -charset UTF-8 -tpng @pumlFiles
