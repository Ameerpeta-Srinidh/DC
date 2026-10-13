@echo off
REM Usage: run_app_server.bat [appPort] [dbHost] [dbPort]
cd /d "%~dp0out"
java -cp ".;../lib/*" server.LetterboxdAppServer %*
