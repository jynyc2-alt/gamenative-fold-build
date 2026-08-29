#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'GameNative')
xserver = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt'
gradle = root / 'app/build.gradle.kts'
evshim = root / 'app/src/main/cpp/evshim/evshim.c'
controller_src = Path(__file__).resolve().parent / 'FoldRearTriggerController.java'
controller_dst = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/FoldRearTriggerController.java'

if not xserver.exists() or not gradle.exists() or not evshim.exists():
    raise SystemExit(f'GameNative source not found under {root}')

shutil.copy2(controller_src, controller_dst)

text = xserver.read_text(encoding='utf-8')

def insert_once(text: str, anchor: str, insertion: str, label: str) -> str:
    if insertion.strip() in text:
        return text
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one anchor, found {count}')
    return text.replace(anchor, anchor + insertion, 1)

text = insert_once(
    text,
    '    var physicalControllerHandler: PhysicalControllerHandler? by remember { mutableStateOf(null) }\n',
    '    var rearTriggerController: FoldRearTriggerController? by remember { mutableStateOf(null) }\n',
    'rear controller state',
)

text = insert_once(
    text,
    '            physicalControllerHandler = null\n',
    '            rearTriggerController?.stop()\n            rearTriggerController = null\n',
    'rear controller cleanup',
)

hook = '''\n            // Galaxy Fold-style dual display: keep the game on the primary/inner screen and\n            // automatically present a dedicated LT/RT touch surface on the rear/cover screen.\n            if (activity != null && rearTriggerController == null) {\n                rearTriggerController = FoldRearTriggerController(\n                    activity,\n                    icView,\n                    xServerView.getxServer().winHandler,\n                ).also { it.start() }\n            }\n'''
text = insert_once(
    text,
    '            xServerView.getxServer().winHandler.setInputControlsView(PluviaApp.inputControlsView)\n',
    hook,
    'rear controller startup',
)

xserver.write_text(text, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
dep = '    implementation("androidx.window:window:1.6.0-alpha05")\n'
if dep not in g:
    anchor = '    implementation("androidx.documentfile:documentfile:1.0.1")\n'
    if g.count(anchor) != 1:
        raise SystemExit(f'window dependency: expected exactly one anchor, found {g.count(anchor)}')
    g = g.replace(anchor, anchor + '\n    // Fold cover/rear-display trigger controls\n' + dep, 1)

# Give the modified build a different Android application ID so it installs alongside
# the official app.gamenative package. Keep the namespace/source packages unchanged.
official_id = '        applicationId = "app.gamenative"\n'
fold_id = '        applicationId = "app.gamenative.foldtriggers"\n'
if fold_id not in g:
    if g.count(official_id) != 1:
        raise SystemExit(f'applicationId: expected exactly one official ID, found {g.count(official_id)}')
    g = g.replace(official_id, fold_id, 1)

gradle.write_text(g, encoding='utf-8')

# The native evshim controller bridge has a fallback path hardcoded to the official
# package. In a side-by-side install that makes Wine/SDL read the official app's
# gamepad_shm while Java writes the Fold app's gamepad_shm, so Android controls still
# haptic/animate but the game sees no input. Point the native fallback at this package.
e = evshim.read_text(encoding='utf-8')
official_shm_base = '        base = "/data/data/app.gamenative/files";\n'
fold_shm_base = '        base = "/data/data/app.gamenative.foldtriggers/files";\n'
if fold_shm_base not in e:
    if e.count(official_shm_base) != 1:
        raise SystemExit(f'evshim base path: expected exactly one official path, found {e.count(official_shm_base)}')
    e = e.replace(official_shm_base, fold_shm_base, 1)
evshim.write_text(e, encoding='utf-8')

# Make the launcher label distinct in every shipped locale. The FileProvider authority
# already uses ${applicationId}, so it also becomes unique automatically.
name_pattern = re.compile(r'(<string\s+name="app_name"[^>]*>)(.*?)(</string>)')
changed_names = 0
for strings_file in sorted((root / 'app/src/main/res').glob('values*/strings.xml')):
    s = strings_file.read_text(encoding='utf-8')
    new_s, count = name_pattern.subn(r'\1GameNative Fold\3', s)
    if count:
        strings_file.write_text(new_s, encoding='utf-8')
        changed_names += count

if changed_names == 0:
    raise SystemExit('app name: no app_name resources found')

print(f'Fold side-by-side patch applied: rear LT/RT + native input path fixed ({changed_names} app labels updated).')
