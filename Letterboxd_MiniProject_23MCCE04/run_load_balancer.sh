#!/bin/bash
# Usage: ./run_load_balancer.sh [lbPort] [host:port,host:port,...]
# Defaults: 9870 localhost:9876,localhost:9877,localhost:9878
cd "$(dirname "$0")"
cd out
java -cp ".:../lib/*" server.LoadBalancer "$@"
