@echo off
setlocal enabledelayedexpansion

set DIR=%~dp0
set OEPM_SUBDIR=%DIR%.oepm

rem Two layouts a scaffolded project can be in: Gradle's own files tucked
rem into .oepm/ (new projects), or at this same root (legacy - e.g. the
rem real openedge-package-manager demo repo, which predates the .oepm/
rem layout). Detected automatically so this one script works for both,
rem with no migration needed for existing projects. Invocation goes
rem through the :run_gradle subroutine below rather than building a
rem "-p ..." flag into a variable directly - embedding quotes inside a
rem batch variable's value doesn't substitute reliably at the call site.
if exist "%OEPM_SUBDIR%\gradlew.bat" (
    set GRADLEW=%OEPM_SUBDIR%\gradlew.bat
    set USE_SUBDIR=1
) else (
    set GRADLEW=%DIR%gradlew.bat
    set USE_SUBDIR=0
)

if "%~1"=="" goto usage
set COMMAND=%~1

if /I "%COMMAND%"=="install" (
    if "%~2"=="" (
        call :run_gradle oepmInstall
    ) else (
        call :run_gradle oepmInstall "-PoepmAdd=%~2"
    )
    goto :eof
)

if /I "%COMMAND%"=="propath" (
    if "%~2"=="" (
        call :run_gradle oepmPropath
    ) else if /I "%~2"=="--tests" (
        call :run_gradle oepmPropath -PoepmIncludeTests
    ) else (
        goto usage
    )
    goto :eof
)

if /I "%COMMAND%"=="registry" (
    if /I not "%~2"=="add" goto usage
    if "%~3"=="" (
        set /p PREFIX="Registry prefix (e.g. ba.): "
        if "!PREFIX!"=="" (
            echo Registry prefix is required.
            exit /b 1
        )
        set /p URL="Catalog URL: "
        if "!URL!"=="" (
            echo Catalog URL is required.
            exit /b 1
        )
        call :run_gradle oepmRegistryAdd "-PregistryPrefix=!PREFIX!" "-PcatalogUrl=!URL!"
        goto :eof
    )
    if "%~5"=="" if not "%~4"=="" (
        call :run_gradle oepmRegistryAdd "-PregistryPrefix=%~3" "-PcatalogUrl=%~4"
        goto :eof
    )
    if not "%~5"=="" (
        call :run_gradle oepmRegistryAdd "-PregistryPrefix=%~3" "-PcatalogUrl=%~4" "-PregistryName=%~5"
        goto :eof
    )
    goto usage
)

goto usage

:run_gradle
if "%USE_SUBDIR%"=="1" (
    call "%GRADLEW%" -p "%OEPM_SUBDIR%" %*
) else (
    call "%GRADLEW%" %*
)
goto :eof

:usage
echo Usage:
echo   oepm install                          resolve declared dependencies
echo   oepm install ^<package^>[:^<versionSpec^>] add + resolve a dependency in one step
echo   oepm propath [--tests]                 print the generated PROPATH
echo                                          (--tests also includes buildPath's "test" entries)
echo   oepm registry add [^<prefix^> ^<url^> [^<name^>]]  add a registry to oepm-registries.properties
echo                                          (interactive if prefix/url are omitted)
exit /b 1
