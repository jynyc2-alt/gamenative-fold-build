#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'GameNative')
gradle = root / 'app/build.gradle.kts'
bionic = root / 'app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java'

if not gradle.exists() or not bionic.exists():
    raise SystemExit(f'GameNative source not found under {root}')

# Diagnostic package: side-by-side only. Intentionally NO WindowArea/rear-display code.
official_id = '        applicationId = "app.gamenative"\n'
baseline_id = '        applicationId = "app.gamenative.foldbaseline"\n'
g = gradle.read_text(encoding='utf-8')
if baseline_id not in g:
    if g.count(official_id) != 1:
        raise SystemExit(f'applicationId: expected one official ID, found {g.count(official_id)}')
    g = g.replace(official_id, baseline_id, 1)
gradle.write_text(g, encoding='utf-8')

# Make runtime absolute app-private paths point at this diagnostic package.
official_private = '/data/data/app.gamenative'
baseline_private = '/data/data/app.gamenative.foldbaseline'
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
        p.write_text(s.replace(official_private, baseline_private), encoding='utf-8')
        path_replacements += count

if path_replacements == 0:
    raise SystemExit('package path rewrite: found no absolute app.gamenative paths')

# Explicitly keep Wine-side evshim on this package's files directory.
b = bionic.read_text(encoding='utf-8')
env_anchor = (
    '        EnvVars envVars = new EnvVars();\n\n'
    '        // Use the ControllerManager\'s dynamic count for the environment variable\n'
)
env_insert = '        envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());\n'
if env_insert.strip() not in b:
    if b.count(env_anchor) != 1:
        raise SystemExit(f'EVSHIM_BASE_PATH: expected one anchor, found {b.count(env_anchor)}')
    b = b.replace(env_anchor, env_anchor + env_insert, 1)
bionic.write_text(b, encoding='utf-8')

# Distinct launcher name.
name_pattern = re.compile(r'(<string\s+name="app_name"[^>]*>)(.*?)(</string>)')
changed_names = 0
for strings_file in sorted((root / 'app/src/main/res').glob('values*/strings.xml')):
    s = strings_file.read_text(encoding='utf-8')
    new_s, count = name_pattern.subn(r'\1GameNative Fold Baseline\3', s)
    if count:
        strings_file.write_text(new_s, encoding='utf-8')
        changed_names += count

if changed_names == 0:
    raise SystemExit('app name: no app_name resources found')

print(f'Baseline side-by-side diagnostic applied: NO rear-display code; {path_replacements} path rewrites, {changed_names} labels.')
