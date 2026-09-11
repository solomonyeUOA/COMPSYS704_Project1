@echo off
python "%~dp0tools\project.py" run %*
if errorlevel 1 pause
