from __future__ import annotations

import logging
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional

log = logging.getLogger('tg-mtproto-proxy')


def _find_app_bundle() -> Optional[Path]:
    """On macOS, walk up sys.executable to find the enclosing .app bundle."""
    if sys.platform != 'darwin':
        return None
    try:
        p = Path(sys.executable).resolve()
    except OSError:
        return None
    for _ in range(8):
        if p.suffix == '.app' and p.is_dir():
            return p
        if p == p.parent:
            return None
        p = p.parent
    return None


def _can_write_target() -> bool:
    """Check if we have write permission for the install target."""
    try:
        if sys.platform == 'darwin':
            app = _find_app_bundle()
            if app is None:
                return False
            return os.access(str(app), os.W_OK) and os.access(str(app.parent), os.W_OK)
        exe = Path(sys.executable)
        return os.access(str(exe), os.W_OK) and os.access(str(exe.parent), os.W_OK)
    except OSError:
        return False


def is_supported() -> bool:
    """Auto-update is supported on frozen builds where we can write to the install target."""
    if not getattr(sys, 'frozen', False):
        return False
    if sys.platform not in ('win32', 'linux', 'darwin'):
        return False
    return _can_write_target()


def find_asset_url(assets: list) -> Optional[str]:
    name = _asset_name()
    if name is None:
        return None
    for asset in assets:
        if asset.get('name') == name:
            return asset.get('browser_download_url')
    return None


def _asset_name() -> Optional[str]:
    if sys.platform == 'win32':
        ver = sys.getwindowsversion()
        if ver.major >= 10:
            return 'TgWsProxy_windows.exe'
        bits = struct.calcsize('P') * 8
        return f'TgWsProxy_windows_7_{"64bit" if bits == 64 else "32bit"}.exe'
    if sys.platform == 'linux':
        return 'TgWsProxy_linux_amd64'
    if sys.platform == 'darwin':
        return 'TgWsProxy_macos_universal.dmg'
    return None


def run_update(assets: list, new_version: str) -> bool:
    """Generate a platform updater script and launch it detached. Returns True on success."""
    url = find_asset_url(assets)
    if not url:
        log.warning("Auto-update: no matching asset found for this platform")
        return False

    if not _can_write_target():
        log.warning("Auto-update: no write permission for install target")
        return False

    pid = os.getpid()

    try:
        if sys.platform == 'win32':
            script = _ps1(pid, sys.executable, url, new_version)
            tmp = Path(tempfile.mktemp(suffix='.ps1'))
            tmp.write_text(script, encoding='utf-8')
            subprocess.Popen(
                ['powershell', '-ExecutionPolicy', 'Bypass',
                 '-WindowStyle', 'Normal', '-File', str(tmp)],
                creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP,
            )
        elif sys.platform == 'darwin':
            app = _find_app_bundle()
            if app is None:
                log.warning("Auto-update: cannot locate .app bundle")
                return False
            script = _sh_macos(pid, str(app), url, new_version)
            tmp = Path(tempfile.mktemp(suffix='.sh'))
            tmp.write_text(script, encoding='utf-8')
            tmp.chmod(0o755)
            subprocess.Popen(['/bin/bash', str(tmp)], start_new_session=True)
        else:
            script = _sh_linux(pid, sys.executable, url, new_version)
            tmp = Path(tempfile.mktemp(suffix='.sh'))
            tmp.write_text(script, encoding='utf-8')
            tmp.chmod(0o755)
            subprocess.Popen(['/bin/bash', str(tmp)], start_new_session=True)
        log.info("Auto-update: updater launched for v%s, shutting down", new_version)
        return True
    except Exception as exc:
        log.error("Auto-update: failed to launch updater: %s", repr(exc))
        return False


def _ps1(pid: int, exe: str, url: str, version: str) -> str:
    exe_esc = exe.replace("'", "''")
    return f"""\
$pid_target = {pid}
$exe = '{exe_esc}'
$url = '{url}'
$ver = '{version}'

Write-Host "TG WS Proxy updater: installing $ver..."

$proc = Get-Process -Id $pid_target -ErrorAction SilentlyContinue
if ($proc) {{
    Write-Host "Waiting for process to exit..."
    if (-not $proc.WaitForExit(15000)) {{
        $proc | Stop-Process -Force
        Start-Sleep -Milliseconds 500
    }}
}}

$tmp = $exe + '.update'
Write-Host "Downloading..."
try {{
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    (New-Object Net.WebClient).DownloadFile($url, $tmp)
}} catch {{
    Write-Host "Download failed: $_"
    Start-Sleep 3
    exit 1
}}

try {{
    Move-Item -LiteralPath $tmp -Destination $exe -Force
}} catch {{
    Write-Host "Replace failed: $_"
    Start-Sleep 3
    exit 1
}}

Write-Host "Done. Restarting..."
Start-Process -FilePath $exe
Start-Sleep -Milliseconds 500
Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"""


def _sh_linux(pid: int, exe: str, url: str, version: str) -> str:
    exe_esc = exe.replace("'", "'\\''")
    return f"""\
#!/bin/bash
PID={pid}
EXE='{exe_esc}'
URL='{url}'
VER='{version}'

echo "TG WS Proxy updater: installing $VER..."
echo "Waiting for process to exit..."
for i in $(seq 1 30); do
    kill -0 $PID 2>/dev/null || break
    sleep 0.5
done
kill -9 $PID 2>/dev/null || true
sleep 0.3

TMP="$EXE.update"
echo "Downloading..."
if command -v curl &>/dev/null; then
    curl -fsSL -o "$TMP" "$URL" || {{ echo "Download failed"; sleep 3; exit 1; }}
else
    wget -q -O "$TMP" "$URL" || {{ echo "Download failed"; sleep 3; exit 1; }}
fi
chmod +x "$TMP"
mv -f "$TMP" "$EXE" || {{ echo "Replace failed"; sleep 3; exit 1; }}

echo "Done. Restarting..."
nohup "$EXE" >/dev/null 2>&1 &
rm -f "$0"
"""


def _sh_macos(pid: int, app_path: str, url: str, version: str) -> str:
    app_esc = app_path.replace("'", "'\\''")
    return f"""\
#!/bin/bash
PID={pid}
APP='{app_esc}'
URL='{url}'
VER='{version}'

echo "TG WS Proxy updater: installing $VER..."
echo "Waiting for process to exit..."
for i in $(seq 1 30); do
    kill -0 $PID 2>/dev/null || break
    sleep 0.5
done
kill -9 $PID 2>/dev/null || true
sleep 0.3

TMP_DMG="$(mktemp -t tgwsproxy).dmg"
echo "Downloading..."
if ! curl -fsSL -o "$TMP_DMG" "$URL"; then
    echo "Download failed"
    rm -f "$TMP_DMG"
    sleep 3
    exit 1
fi

echo "Mounting..."
MOUNT=$(hdiutil attach -nobrowse -readonly "$TMP_DMG" 2>/dev/null | grep -oE '/Volumes/.*$' | tail -1)
if [ -z "$MOUNT" ]; then
    echo "Mount failed"
    rm -f "$TMP_DMG"
    sleep 3
    exit 1
fi

SRC_APP=$(find "$MOUNT" -maxdepth 2 -name "*.app" -type d 2>/dev/null | head -1)
if [ -z "$SRC_APP" ]; then
    hdiutil detach -quiet "$MOUNT" 2>/dev/null
    rm -f "$TMP_DMG"
    echo "No .app found in DMG"
    sleep 3
    exit 1
fi

echo "Installing..."
BACKUP="$APP.bak"
rm -rf "$BACKUP"
mv -f "$APP" "$BACKUP" 2>/dev/null || true
if ! cp -R "$SRC_APP" "$APP"; then
    mv -f "$BACKUP" "$APP" 2>/dev/null || true
    hdiutil detach -quiet "$MOUNT" 2>/dev/null
    rm -f "$TMP_DMG"
    echo "Install failed"
    sleep 3
    exit 1
fi
rm -rf "$BACKUP"

hdiutil detach -quiet "$MOUNT" 2>/dev/null
rm -f "$TMP_DMG"

xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

echo "Done. Restarting..."
open "$APP"
rm -f "$0"
"""
