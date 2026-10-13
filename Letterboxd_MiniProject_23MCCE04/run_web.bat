@echo off
REM Usage: run_web.bat [appHost] [appPort] [webPort]
cd /d "%~dp0out"
java -cp ".;../lib/*" server.WebGateway %*
