import { app } from 'electron';
import { platform } from 'node:os';
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const LINUX_DESKTOP_FILE_NAME = 'camerascontroller-agent.desktop';

/**
 * Turns start-on-login on or off, per user, no elevation required.
 * Windows: Electron's login-item API (HKCU\...\Run).
 * Linux: an XDG autostart entry under ~/.config/autostart.
 * Only ever enabled after the user joined a network with the autostart box
 * ticked, and switchable from the tray menu.
 */
export function setAutostart(enabled: boolean): void {
  const plat = platform();

  if (plat === 'win32') {
    app.setLoginItemSettings({ openAtLogin: enabled, path: process.execPath });
    return;
  }

  if (plat === 'linux') {
    const autostartDir = join(app.getPath('home'), '.config', 'autostart');
    const target = join(autostartDir, LINUX_DESKTOP_FILE_NAME);
    if (!enabled) {
      rmSync(target, { force: true });
      return;
    }
    if (existsSync(target)) return;
    mkdirSync(autostartDir, { recursive: true });
    const execPath = process.env.APPIMAGE ?? process.execPath;
    const desktopEntry = [
      '[Desktop Entry]',
      'Type=Application',
      'Name=CamerasController Agent',
      `Exec="${execPath}"`,
      'X-GNOME-Autostart-enabled=true',
      'Terminal=false',
    ].join('\n');
    writeFileSync(target, desktopEntry, { mode: 0o755 });
  }
}
