@echo off
cd /d "%~dp0"
java -Xms128m -Xmx2g -jar release\optimus-heatroute.jar --spring.profiles.active=local
