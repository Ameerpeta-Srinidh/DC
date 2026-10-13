#!/bin/bash
# Usage: ./run_client.sh [host] [port]
# Point this at the LOAD BALANCER's port (default 9870), not an app server directly.
cd "$(dirname "$0")"
cd out
java -cp ".:../lib/*" client.LetterboxdClient "$@"
