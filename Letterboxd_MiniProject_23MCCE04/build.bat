@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
dir /s /B /A:-D *.java > sources.txt
javac -cp "lib/*" -d out @sources.txt
set "BUILD_RESULT=%ERRORLEVEL%"
del sources.txt
if not "%BUILD_RESULT%"=="0" exit /b %BUILD_RESULT%
echo Build OK -^> out\
