@echo off
rem Gradle wrapper that uses mise-managed toolchain
rem All tool versions are defined in .mise.toml

rem Change to script directory
cd /d "%~dp0"

rem Run gradle within mise environment
mise exec -- gradle %*
