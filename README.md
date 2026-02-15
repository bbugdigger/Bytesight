### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

---

### Available Gradle Tasks

Build normal sample JAR 
```
./gradlew :sample:jar
```

Build obfuscated version with ProGuard
```
./gradlew :sample:obfuscate
```

Run normal version (for baseline testing)
```
./gradlew :sample:run
```

Run obfuscated version
```
./gradlew :sample:runObfuscated
```

Or run directly:
```
java -jar sample/build/libs/sample-0.1.0-SNAPSHOT.jar           # Normal
java -jar sample/build/obfuscated/sample-obfuscated.jar         # Obfuscated
```

#### Output Files
After running 
```
./gradlew :sample:obfuscate:
```
| File | Description |
|------|-------------|
| sample/build/obfuscated/sample-obfuscated.jar | The obfuscated JAR |
| sample/build/obfuscated/mapping.txt | ProGuard mapping file (original → obfuscated names) |
| sample/build/obfuscated/proguard.pro | Generated ProGuard configuration |

#### Obfuscation Details
The ProGuard configuration applies aggressive obfuscation:
- Class renaming: All classes except SampleApplication are renamed to short names (a, b, c, etc.)
- Package flattening: All packages are merged into o/
- Method renaming: All methods are renamed
- Optimization: 3 passes of optimization
- Shrinking: Removes unused code

Future steps:
- JDBG
- https://github.com/BaseMC/javgent
- https://github.com/LXGaming/Reconstruct
- https://github.com/christopherney/Enigma
- https://github.com/open-obfuscator/dProtect
- https://github.com/lamhoangx/proguard-deobfuscator
- https://www.guardsquare.com/manual/tools/retrace
- https://github.com/mirkosertic/MetaIR