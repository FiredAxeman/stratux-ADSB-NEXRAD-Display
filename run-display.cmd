@echo off
setlocal

where java >nul 2>&1
if errorlevel 1 (
    echo Java 17 is required but was not found on PATH.
    exit /b 1
)

for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~V"
echo %JAVA_VERSION% | findstr /b /c:"17." >nul
if errorlevel 1 (
    echo Java 17 is required. Found %JAVA_VERSION%.
    echo Set JAVA_HOME to a Java 17 installation before running this script.
    exit /b 1
)

java %* -jar "%~dp0target\stratux-display-1.0.0.jar"
