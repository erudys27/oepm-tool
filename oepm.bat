@echo off
setlocal

set DIR=%~dp0
set GRADLEW=%DIR%gradlew.bat

if "%~1"=="" goto usage
set COMMAND=%~1

if /I "%COMMAND%"=="install" (
    if "%~2"=="" (
        call "%GRADLEW%" oepmInstall
    ) else (
        call "%GRADLEW%" oepmInstall "-PoepmAdd=%~2"
    )
    goto :eof
)

if /I "%COMMAND%"=="propath" (
    call "%GRADLEW%" oepmPropath
    goto :eof
)

:usage
echo Usage:
echo   oepm install                          resolve declared dependencies
echo   oepm install ^<package^>[:^<versionSpec^>] add + resolve a dependency in one step
echo   oepm propath                           print the generated PROPATH
exit /b 1
