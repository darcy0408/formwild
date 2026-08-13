@echo off
REM FormWild launcher.
REM  - chcp 65001 + stdout.encoding put the console into UTF-8 so degree signs and
REM    middle dots render instead of turning into question marks.
REM  - --enable-native-access quiets JDK 26's restricted-method warning for OpenCV's
REM    native loader; the JDK says the unflagged path "will be blocked in a future
REM    release", so the flag is passed now rather than discovered later.
REM  - No --enable-preview: everything FormWild uses is final in Java 26.
chcp 65001 >nul
java --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0target\formwild.jar" %*
