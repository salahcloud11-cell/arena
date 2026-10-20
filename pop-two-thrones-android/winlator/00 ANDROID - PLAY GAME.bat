@echo off
rem ==================================================================
rem  Prince of Persia: The Two Thrones - START GAME (Android / Winlator)
rem
rem  How to use: in Winlator open  Containers > (your container) > File
rem  Manager > D: > princ of percia  and tap this file.
rem
rem  What it does: it writes the registry keys that the game launcher
rem  needs (the same keys as "RUN THIS FIX FIRST.bat"), then it starts
rem  the game launcher (PrinceOfPersia.exe). Safe to run every time.
rem ==================================================================
cd /d "%~dp0"
set "GAMEPATH=%~dp0"
set "GAMEPATH=%GAMEPATH:~0,-1%"
set "KEY64=HKLM\SOFTWARE\Wow6432Node\Ubisoft\Prince of Persia The Two Thrones\1.00.999"
set "KEY32=HKLM\SOFTWARE\Ubisoft\Prince of Persia The Two Thrones\1.00.999"

echo Prince of Persia: The Two Thrones
echo Game folder: %GAMEPATH%
echo Writing registry keys...

reg add "%KEY64%" /v "Product_Executable" /t REG_SZ /d "PrinceOfPersia.exe" /f >nul 2>&1
reg add "%KEY64%" /v "Product_Language" /t REG_SZ /d "9" /f >nul 2>&1
reg add "%KEY64%" /v "Product_Path" /t REG_SZ /d "%GAMEPATH%" /f >nul 2>&1
reg add "%KEY64%" /v "Product_Release" /t REG_SZ /d "Retail EMEA" /f >nul 2>&1
reg add "%KEY64%" /v "Profiles_Path" /t REG_SZ /d "%GAMEPATH%" /f >nul 2>&1

rem Same keys through the 32-bit registry view (extra safety, errors ignored)
reg add "%KEY32%" /v "Product_Executable" /t REG_SZ /d "PrinceOfPersia.exe" /f /reg:32 >nul 2>&1
reg add "%KEY32%" /v "Product_Language" /t REG_SZ /d "9" /f /reg:32 >nul 2>&1
reg add "%KEY32%" /v "Product_Path" /t REG_SZ /d "%GAMEPATH%" /f /reg:32 >nul 2>&1
reg add "%KEY32%" /v "Product_Release" /t REG_SZ /d "Retail EMEA" /f /reg:32 >nul 2>&1
reg add "%KEY32%" /v "Profiles_Path" /t REG_SZ /d "%GAMEPATH%" /f /reg:32 >nul 2>&1

if not exist "PrinceOfPersia.exe" (
  echo.
  echo ERROR: PrinceOfPersia.exe was not found next to this file.
  echo Put this file inside the game folder, next to PrinceOfPersia.exe.
  pause
  exit /b 1
)

echo Starting the game launcher...
start "" "PrinceOfPersia.exe"
exit /b 0
