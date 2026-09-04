@echo off
setlocal enabledelayedexpansion

rem Global oepm CLI - install this once (put this file's directory on
rem PATH) and `oepm install`/`oepm propath`/`oepm registry add` work from
rem inside ANY oepm-managed project, no per-project ".\oepm.bat" needed.
rem
rem Unlike the per-project oepm.bat (copied into each scaffolded project
rem by scaffoldProject, which finds its target project via its OWN file
rem location - %~dp0), this script finds its target project from your
rem CURRENT DIRECTORY, walking upward looking for openedge-project.json -
rem the same way git/npm find their project root from any subfolder.
rem That's what makes it safe to put on PATH: it always operates on
rem whatever project you're actually standing in, never on wherever this
rem script itself happens to be installed.
rem
rem The per-project oepm.bat still exists and still matters - it's what
rem makes a project fully self-contained and usable without any global
rem setup (e.g. in CI, or on a machine where this hasn't been installed).
rem This is a local-dev convenience layered on top, not a replacement.

set "SEARCH_DIR=%CD%"

:find_root
if exist "%SEARCH_DIR%\openedge-project.json" goto found

for %%I in ("%SEARCH_DIR%\..") do set "PARENT_DIR=%%~fI"
if /I "%PARENT_DIR%"=="%SEARCH_DIR%" (
    echo Not inside an oepm project - no openedge-project.json found in %CD% or any parent directory. 1>&2
    exit /b 1
)
set "SEARCH_DIR=%PARENT_DIR%"
goto find_root

:found
set "PROJECT_ROOT=%SEARCH_DIR%"
set "OEPM_SUBDIR=%PROJECT_ROOT%\.oepm"

rem Two layouts a scaffolded project can be in: Gradle's own files tucked
rem into .oepm/ (new projects), or at the project root (legacy - e.g. the
rem real openedge-package-manager demo repo, which predates the .oepm/
rem layout). Detected automatically so this one script works for both.
if exist "%OEPM_SUBDIR%\gradlew.bat" (
    set "GRADLEW=%OEPM_SUBDIR%\gradlew.bat"
    set "USE_SUBDIR=1"
) else (
    set "GRADLEW=%PROJECT_ROOT%\gradlew.bat"
    set "USE_SUBDIR=0"
)

if not exist "%GRADLEW%" (
    echo Found %PROJECT_ROOT%\openedge-project.json, but no gradlew.bat there ^(or in .oepm\^) - is this really an oepm project? 1>&2
    exit /b 1
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

if /I "%COMMAND%"=="prune" (
    if "%~2"=="" (
        call :run_gradle oepmPrune
    ) else if /I "%~2"=="--dry-run" (
        call :run_gradle oepmPrune -PoepmDryRun
    ) else (
        goto usage
    )
    goto :eof
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
echo Usage (running against %PROJECT_ROOT%):
echo   oepm install                          resolve declared dependencies
echo   oepm install ^<package^>[:^<versionSpec^>] add + resolve a dependency in one step
echo   oepm propath [--tests]                 print the generated PROPATH
echo                                          (--tests also includes buildPath's "test" entries)
echo   oepm registry add [^<prefix^> ^<url^> [^<name^>]]  add a registry to oepm-registries.properties
echo                                          (interactive if prefix/url are omitted)
echo   oepm prune [--dry-run]                 remove oepm_packages/ entries no longer part of
echo                                          the resolved dependency graph
exit /b 1
