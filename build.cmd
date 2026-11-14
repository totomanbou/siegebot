@echo off
rem Usage: build.cmd package   (jar -> target\SiegeBot-0.1.0.jar)
rem        build.cmd test
rem Uses tools\apache-maven-3.9.16 and tools\m2 when present, otherwise mvn on PATH.
if exist "%~dp0tools\apache-maven-3.9.16\bin\mvn.cmd" (
  call "%~dp0tools\apache-maven-3.9.16\bin\mvn.cmd" -B "-Dmaven.repo.local=%~dp0tools\m2" %*
) else (
  call mvn -B %*
)
