@echo off
cd /d "%~dp0..\frontend"
node "C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js" run dev > ..\logs\frontend.log 2> ..\logs\frontend.err
