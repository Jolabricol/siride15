@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=9.1.0"
set "DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "CACHE=%GRADLE_USER_HOME%\wrapper\dists\gradle-%GRADLE_VERSION%-bin"
set "ZIP=%CACHE%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST=%CACHE%\gradle-%GRADLE_VERSION%"
if exist "%DIST%\bin\gradle.bat" goto run
if not exist "%CACHE%" mkdir "%CACHE%"
if not exist "%ZIP%" (
  echo Gradle %GRADLE_VERSION% absent : telechargement...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%DIST_URL%' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
if exist "%CACHE%\.extracting" rmdir /s /q "%CACHE%\.extracting"
mkdir "%CACHE%\.extracting"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%CACHE%\.extracting'"
if errorlevel 1 exit /b 1
if exist "%DIST%" rmdir /s /q "%DIST%"
move "%CACHE%\.extracting\gradle-%GRADLE_VERSION%" "%DIST%" >nul
rmdir /s /q "%CACHE%\.extracting"
:run
call "%DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
