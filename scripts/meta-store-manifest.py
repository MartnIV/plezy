#!/usr/bin/env python3
"""Generate the AndroidManifest the Meta Horizon Store will accept.

Derived from the real manifest rather than kept as a second copy, so the two
cannot drift. Every change here is one the store demands of an immersive app
and that would be wrong for the phone, TV and automotive builds:

  * landscape only, and a single-task launch mode on the launcher activity
  * no LEANBACK_LAUNCHER category, which the store rejects outright
  * a declared GLES version
  * focus awareness, so the system overlay can appear without pausing the app

Usage: meta-store-manifest.py <source> <destination>
"""
import re
import sys
from pathlib import Path

FOCUS_AWARE = '            <meta-data android:name="com.oculus.vr.focusaware" android:value="true" />\n'


def main(source: Path, destination: Path) -> int:
    xml = source.read_text()

    # The launcher activity must be landscape and single-task. Applied only to
    # MainActivity: the immersive activity is already single-task and has no
    # orientation to speak of.
    xml = xml.replace(
        '            android:name=".MainActivity"\n'
        '            android:exported="true"\n'
        '            android:launchMode="singleTop"',
        '            android:name=".MainActivity"\n'
        '            android:exported="true"\n'
        '            android:launchMode="singleTask"\n'
        '            android:screenOrientation="landscape"',
        1,
    )

    # A leanback category makes the store refuse the build.
    xml = re.sub(
        r'[ \t]*<category android:name="android\.intent\.category\.LEANBACK_LAUNCHER"\s*/>\n',
        '',
        xml,
    )

    # A graphics API has to be declared; the app renders with GLES 3.
    xml = xml.replace(
        '    <uses-feature android:name="android.hardware.vr.headtracking"',
        '    <uses-feature android:glEsVersion="0x00030000" android:required="true" />\n'
        '    <uses-feature android:name="android.hardware.vr.headtracking"',
        1,
    )

    # Focus awareness, on every activity that can be foreground in a headset.
    for marker in ('android:name=".MainActivity"', 'android:name=".xr.ImmersivePlayerActivity"'):
        start = xml.index(marker)
        close = xml.index('>', start)
        # Insert just inside the activity element, after its attributes.
        xml = xml[: close + 1] + '\n' + FOCUS_AWARE.rstrip('\n') + xml[close + 1 :]

    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(xml)

    # Fail loudly rather than upload something the store will reject.
    problems = []
    if 'LEANBACK_LAUNCHER' in xml:
        problems.append('leanback category still present')
    if 'android:screenOrientation="landscape"' not in xml:
        problems.append('launcher activity is not landscape')
    if 'singleTask' not in xml:
        problems.append('launch mode was not changed')
    if xml.count('com.oculus.vr.focusaware') < 2:
        problems.append('focus awareness missing from an activity')
    if 'glEsVersion' not in xml:
        problems.append('no graphics API declared')
    if problems:
        print('manifest generation failed:', '; '.join(problems), file=sys.stderr)
        return 1

    print(f'generated {destination}')
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(Path(sys.argv[1]), Path(sys.argv[2])))
