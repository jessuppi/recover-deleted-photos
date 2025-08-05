@echo off
REM ##########################################################################
REM
REM  Gradle startup script for Windows
REM
REM ##########################################################################
set DIR=%~dp0
"%DIR%\gradle\wrapper\gradle-wrapper.jar" %*
