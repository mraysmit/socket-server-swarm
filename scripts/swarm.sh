#!/usr/bin/env sh
# Runs a swarm script against the running harness: scripts/swarm.sh scripts/kv-roundtrip.groovy kv
# Set SWARM_HARNESS to reach a harness on another port, for example http://localhost:8090.
here=$(cd "$(dirname "$0")" && pwd)
script=$1
shift
exec groovy -cp "$here/lib" "$script" "$@"
