@echo off
rem Thin forwarder so "oepm-init" is reachable once cli/ is on PATH. The
rem real script (and its dependency on gradlew.bat) lives at oepm-tool's
rem own root, one level up from here - NOT copied/duplicated, just
rem forwarded to, so there's only ever one real implementation to keep in
rem sync. Safe to put cli/ on PATH the way the root itself deliberately
rem isn't (see cli/oepm.bat's own comment) - this script has no "which
rem project" ambiguity to get wrong, it just calls the real oepm-init.bat
rem in place, preserving your actual working directory.
call "%~dp0..\oepm-init.bat" %*
exit /b %ERRORLEVEL%
