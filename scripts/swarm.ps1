# Runs a swarm script against the running harness: .\scripts\swarm.ps1 scripts\kv-roundtrip.groovy kv
# Set SWARM_HARNESS to reach a harness on another port, for example http://localhost:8090.
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
)
groovy -cp (Join-Path $PSScriptRoot 'lib') $Script @Arguments
exit $LASTEXITCODE
