@echo off
REM Usage: run_client.bat [host] [port]  -- point at the LOAD BALANCER port
cd /d "%~dp0out"
java -cp ".;../lib/*" client.LetterboxdClient %*
