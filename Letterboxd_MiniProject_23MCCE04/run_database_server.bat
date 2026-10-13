@echo off
REM Usage: run_database_server.bat [port] [primaryFile] [replicaFile] [lagMs]
cd /d "%~dp0out"
java -cp ".;../lib/*" server.DatabaseServer %*
