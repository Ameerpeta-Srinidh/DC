@echo off
REM Usage: run_load_balancer.bat [lbPort] [host:port,host:port,...]
cd /d "%~dp0out"
java -cp ".;../lib/*" server.LoadBalancer %*
