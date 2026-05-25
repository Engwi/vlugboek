@echo off
cd /d "%~dp0.."
if not exist logs mkdir logs
if not exist tmp mkdir tmp
if "%VLUGBOEK_MAILER_URL%"=="" set "VLUGBOEK_MAILER_URL=http://127.0.0.1:8788/send-document"
if exist "emailer\.env" (
  for /f "usebackq tokens=1,* delims==" %%A in ("emailer\.env") do (
    if /I "%%A"=="MAIL_WEBHOOK_TOKEN" set "VLUGBOEK_MAILER_TOKEN=%%B"
  )
)
"C:\Program Files\Java\jdk-21\bin\java.exe" -Djava.io.tmpdir=tmp -jar target\vlugboek-0.0.1-SNAPSHOT.jar > logs\backend.log 2> logs\backend.err
