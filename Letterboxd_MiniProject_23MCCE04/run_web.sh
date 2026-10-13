#!/bin/bash
# Usage: ./run_web.sh [appHost] [appPort] [webPort]
cd "$(dirname "$0")"
cd out
java -cp ".:../lib/*" server.WebGateway "$@"
