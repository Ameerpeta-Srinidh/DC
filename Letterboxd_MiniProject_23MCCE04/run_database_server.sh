#!/bin/bash
# Usage: ./run_database_server.sh [port] [primaryFile] [replicaFile] [lagMs]
# Defaults: 9800 primary.db replica.db 1500
cd "$(dirname "$0")"
cd out
java -cp ".:../lib/*" server.DatabaseServer "$@"
