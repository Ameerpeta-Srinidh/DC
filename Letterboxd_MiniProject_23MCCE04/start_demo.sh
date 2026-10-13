#!/bin/bash
set -e
cd "$(dirname "$0")"
./build.sh
java -cp "out:lib/*" server.DatabaseServer 9800 primary.db replica.db 750 &
DB_PID=$!
APP1_PID=
APP2_PID=
LB_PID=
cleanup() {
    for pid in "$LB_PID" "$APP1_PID" "$APP2_PID" "$DB_PID"; do
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
}
trap cleanup EXIT INT TERM
sleep 2
java -cp "out:lib/*" server.LetterboxdAppServer 9876 localhost 9800 &
APP1_PID=$!
java -cp "out:lib/*" server.LetterboxdAppServer 9877 localhost 9800 &
APP2_PID=$!
java -cp "out:lib/*" server.LoadBalancer 9870 localhost:9876,localhost:9877 &
LB_PID=$!
sleep 2
java -cp "out:lib/*" server.WebGateway localhost 9870 8080
