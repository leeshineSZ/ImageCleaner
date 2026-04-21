@rem Gradle wrapper stub
@rem GitHub Actions setup-gradle handles the actual wrapper

@rem Find java.exe
set DEFAULT_JAVA_EXE=java.exe
set JAVA_EXE=%DEFAULT_JAVA_EXE%
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java.exe

"%JAVA_EXE%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
