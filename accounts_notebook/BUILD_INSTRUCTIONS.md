# Build Instructions — مدونة الحسابات APK

## Prerequisites
- Flutter 3.47.6 at /opt/flutter
- Java 11+
- Python 3 with pyaxml: `pip install pyaxml`
- dalvik-dx.jar (com.jakewharton.android.repackaged:dalvik-dx:9.0.0_r3)

## Build Steps

### 1. Get dependencies
```bash
flutter pub get
```

### 2. Compile Dart to kernel
```bash
flutter build linux --release
```
Output: `.dart_tool/flutter_build/<hash>/app.dill`

### 3. Compile to ARM64
```bash
/opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-x64/gen_snapshot \
  --snapshot_kind=app-aot-elf \
  --elf=libapp_arm64.so \
  --strip \
  .dart_tool/flutter_build/<hash>/app.dill
```

### 4. Create classes.dex
```bash
java -cp dalvik-dx.jar com.android.dx.command.Main --dex \
  --output=classes.dex --core-library --min-sdk-version=26 \
  /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/flutter.jar
```

### 5. Create binary AndroidManifest.xml
```python
from pyaxml import AXML
import xml.etree.ElementTree as ET
root = ET.parse('android/app/src/main/AndroidManifest.xml').getroot()
axml = AXML(); axml.from_xml(root)
open('AndroidManifest.bin','wb').write(axml.pack())
```

### 6. Extract libflutter.so
```bash
unzip -p flutter.jar lib/arm64-v8a/libflutter.so > lib/arm64-v8a/libflutter.so
```

### 7. Assemble APK
```bash
zip -r app-unsigned.apk AndroidManifest.bin classes.dex lib/ assets/
```

### 8. Sign
```bash
jarsigner -keystore weshah.keystore -storepass weshah123 \
  -digestalg SHA-256 -sigalg SHA256withRSA app-unsigned.apk weshah
```

## APK Details
- Package: com.weshah.accounts
- Min SDK: 26 (Android 8.0)
- Target: ARM64-v8a
- Size: ~43MB (Flutter engine included)
- Signed with debug keystore
