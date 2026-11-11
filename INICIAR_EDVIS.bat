@echo off
title E.D.V.I.S. // TACTICAL BRAIN SERVER
color 0B
echo ===================================================================
echo   INICIANDO E.D.V.I.S. (Electronic Direct Vision Intelligence System)
echo ===================================================================
cd /d "%~dp0edvis-backend"
start "" "http://localhost:8080"
npm start
pause
