#!/usr/bin/env python3
from pathlib import Path
import shutil, sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'GameNative')
xserver = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt'
gradle = root / 'app/build.gradle.kts'
controller_src = Path(__file__).resolve().parent / 'FoldRearTriggerController.java'
controller_dst = root / 'app/src/main/java/app/gamenative/ui/screen/xserver/FoldRearTriggerController.java'

if not xserver.exists() or not gradle.exists():
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
    gradle.write_text(g, encoding='utf-8')

print('Fold rear-display LT/RT patch applied successfully.')
