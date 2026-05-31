param(
    [string]$PlantUmlVersion = "1.2025.3"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tempJar = Join-Path $env:TEMP "plantuml-$PlantUmlVersion.jar"
$downloadUrl = "https://github.com/plantuml/plantuml/releases/download/v$PlantUmlVersion/plantuml-$PlantUmlVersion.jar"

if (-not (Test-Path $tempJar)) {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $tempJar
}

java -jar $tempJar -charset UTF-8 -tpng (Join-Path $scriptDir "*.puml")
