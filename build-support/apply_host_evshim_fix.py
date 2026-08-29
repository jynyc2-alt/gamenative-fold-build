#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'GameNative')
pluvia = root / 'app/src/main/java/app/gamenative/PluviaApp.kt'

if not pluvia.exists():
    raise SystemExit(f'PluviaApp.kt not found under {root}')

text = pluvia.read_text(encoding='utf-8')
marker = 'android.system.Os.setenv("EVSHIM_BASE_PATH", filesDir.absolutePath, true)'
if marker in text:
    print('Host EVSHIM_BASE_PATH fix already present.')
    raise SystemExit(0)

anchor = '        super.onCreate()\n        instance = this\n\n'
if text.count(anchor) != 1:
    raise SystemExit(f'Expected exactly one PluviaApp onCreate anchor, found {text.count(anchor)}')

fix = '''        // Side-by-side controller fix from upstream GameNative PR #1818.\n        // libevshim.so initializes shared memory in a C constructor as soon as the\n        // library is loaded. Set the host-process path BEFORE preloadSystemLibraries(),\n        // otherwise renamed applicationIds fall back to /data/data/app.gamenative/files\n        // and virtual/on-screen controller events never reach the guest.\n        try {\n            android.system.Os.setenv("EVSHIM_BASE_PATH", filesDir.absolutePath, true)\n        } catch (e: Exception) {\n            android.util.Log.w("PluviaApp", "Failed to set EVSHIM_BASE_PATH for host process", e)\n        }\n\n'''

text = text.replace(anchor, anchor + fix, 1)
pluvia.write_text(text, encoding='utf-8')
print('Applied host-process EVSHIM_BASE_PATH fix before native library preload.')
