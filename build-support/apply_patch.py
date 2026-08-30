#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'GameNative')
xserver = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt'
gradle = root / 'app/build.gradle.kts'
bionic = root / 'app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java'
controller_src = Path(__file__).resolve().parent / 'FoldRearTriggerController.java'
controller_dst = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/FoldRearTriggerController.java'

if not xserver.exists() or not gradle.exists() or not bionic.exists():
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

hook = '''\n            // Galaxy Fold-style dual display: keep the game on the primary/inner screen and\n            // automatically present configurable LT/RT touch controls on the cover screen.\n            if (activity != null && rearTriggerController == null) {\n                rearTriggerController = FoldRearTriggerController(\n                    activity,\n                    icView,\n                    xServerView.getxServer().winHandler,\n                ).also { it.start() }\n            }\n'''
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

# Separate Android package so this build installs alongside official GameNative.
official_id = '        applicationId = "app.gamenative"\n'
fold_id = '        applicationId = "app.gamenative.foldtriggers"\n'
if fold_id not in g:
    if g.count(official_id) != 1:
        raise SystemExit(f'applicationId: expected exactly one official ID, found {g.count(official_id)}')
    g = g.replace(official_id, fold_id, 1)

# Fold-specific monotonically increasing version. Future in-place upgrades must use a
# larger versionCode while retaining the same package ID and signing certificate.
official_version = '        versionCode = 22\n        versionName = "1.2.0"\n'
fold_version = '        versionCode = 2301\n        versionName = "1.2.0-fold.1"\n'
if fold_version not in g:
    if g.count(official_version) != 1:
        raise SystemExit(f'version: expected one stock version block, found {g.count(official_version)}')
    g = g.replace(official_version, fold_version, 1)

gradle.write_text(g, encoding='utf-8')

# GameNative contains several absolute app-private paths baked around the official
# package name. A side-by-side applicationId means those paths point into the other
# installed app. Rewrite every app-private absolute path in main sources/resources so
# Wine, evshim, media helpers, drives, etc. all remain inside this Fold package.
official_private = '/data/data/app.gamenative'
fold_private = '/data/data/app.gamenative.foldtriggers'
path_replacements = 0
for p in (root / 'app/src/main').rglob('*'):
    if not p.is_file() or p.suffix.lower() not in {'.java', '.kt', '.c', '.h', '.cpp', '.xml', '.txt', '.sh'}:
        continue
    try:
        s = p.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    count = s.count(official_private)
    if count:
        p.write_text(s.replace(official_private, fold_private), encoding='utf-8')
        path_replacements += count

if path_replacements == 0:
    raise SystemExit('package path rewrite: found no /data/data/app.gamenative references')

# Explicitly tell the Wine-side evshim preload which Android files directory owns
# gamepad_shm. The separate host-process initialization fix is applied by
# apply_host_evshim_fix.py before native libraries are preloaded.
b = bionic.read_text(encoding='utf-8')
env_anchor = (
    '        EnvVars envVars = new EnvVars();\n\n'
    '        // Use the ControllerManager\'s dynamic count for the environment variable\n'
)
env_insert = '        envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());\n'
if env_insert.strip() not in b:
    if b.count(env_anchor) != 1:
        raise SystemExit(f'EVSHIM_BASE_PATH: expected exactly one env anchor, found {b.count(env_anchor)}')
    b = b.replace(env_anchor, env_anchor + env_insert, 1)
    bionic.write_text(b, encoding='utf-8')

# Make launcher name unmistakable in every shipped locale.
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

print(
    'Fold side-by-side patch applied: editable rear LT/RT + package-aware input paths + '
    f'versionCode 2301 ({path_replacements} absolute paths, {changed_names} app labels updated).'
)
