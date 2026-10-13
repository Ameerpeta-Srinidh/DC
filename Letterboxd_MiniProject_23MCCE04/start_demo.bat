@echo off
setlocal
set "ROOT=%~dp0"
call "%ROOT%build.bat"
if errorlevel 1 exit /b 1

start "Frame by Frame - Database" /D "%ROOT%out" cmd /k java -cp ".;../lib/*" server.DatabaseServer 9800 primary.db replica.db 750
timeout /t 2 /nobreak >nul
start "Frame by Frame - App 1" /D "%ROOT%out" cmd /k java -cp ".;../lib/*" server.LetterboxdAppServer 9876 localhost 9800
start "Frame by Frame - App 2" /D "%ROOT%out" cmd /k java -cp ".;../lib/*" server.LetterboxdAppServer 9877 localhost 9800
timeout /t 2 /nobreak >nul
start "Frame by Frame - Load Balancer" /D "%ROOT%out" cmd /k java -cp ".;../lib/*" server.LoadBalancer 9870 localhost:9876,localhost:9877
start "Frame by Frame - Web Gateway" /D "%ROOT%out" cmd /k java -cp ".;../lib/*" server.WebGateway localhost 9870 8080
timeout /t 2 /nobreak >nul
start "" "http://localhost:8080"
echo All services have been launched. Close their terminal windows to stop them.
