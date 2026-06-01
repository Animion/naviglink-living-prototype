@rem
@rem Gradle wrapper pro Windows.
@rem Otevři projekt v Android Studio — Studio dogeneruje gradle-wrapper.jar.
@rem Alternativně: gradle wrapper --gradle-version 8.10.2
@rem

@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%JAR%" (
  echo Chybi %JAR% 1>&2
  echo Otevri projekt v Android Studio nebo spust: gradle wrapper --gradle-version 8.10.2 1>&2
  exit /b 1
)

java -jar "%JAR%" %*
endlocal
