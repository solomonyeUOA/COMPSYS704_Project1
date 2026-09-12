@echo off
rem Software simulation only. Original run-project.bat stays normal speed.
python "%~dp0tools\project.py" run --demo-slowdown 5 %*
if errorlevel 1 pause
