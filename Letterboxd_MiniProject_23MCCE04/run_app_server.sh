#!/bin/bash
# Usage: ./run_app_server.sh [appPort] [dbHost] [dbPort]
# Defaults: 9876 localhost 9800
# Start several of these on different ports (9876, 9877, 9878, ...) to
# demonstrate horizontal scaling — all of them talk to the SAME DatabaseServer.
cd "$(dirname "$0")"
cd out
java -cp ".:../lib/*" server.LetterboxdAppServer "$@"
