@echo off
cd /d "%~dp0..\emailer"
if "%VLUGBOEK_MAILER_PORT%"=="" set "VLUGBOEK_MAILER_PORT=8788"
set "PORT=%VLUGBOEK_MAILER_PORT%"
if "%HOST%"=="" set "HOST=127.0.0.1"
if "%MAIL_JSON_LIMIT%"=="" set "MAIL_JSON_LIMIT=50mb"
if not exist node_modules (
  node "C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js" install > ..\logs\emailer-install.log 2> ..\logs\emailer-install.err
)
node server.mjs > ..\logs\emailer.log 2> ..\logs\emailer.err
