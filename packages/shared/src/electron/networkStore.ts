// Main-process side of the network setup, shared by the desktop agent and
// client: persists the network config in the app's userData folder and
// serves the setup screen's IPC calls. Imported only from Electron main
// processes (it needs node:fs and electron).
import { app, ipcMain, safeStorage, type BrowserWindow } from 'electron';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { validateNetwork, type NetworkConfig } from '../network.js';

const file = () => join(app.getPath('userData'), 'network.bin');

// The config holds the whole invite (i.e. the network's password), so it is
// encrypted with the OS keystore (DPAPI on Windows, libsecret/kwallet on
// Linux) when one is available. On a Linux box without a keyring it falls
// back to plain text in the user's own profile — still only readable by
// that account.
export function loadNetwork(): NetworkConfig | null {
  try {
    if (!existsSync(file())) return null;
    const raw = readFileSync(file());
    const text = raw[0] === 0x7b /* { */ ? raw.toString('utf-8') : safeStorage.decryptString(raw);
    return validateNetwork(JSON.parse(text));
  } catch (err) {
    console.error('network config unreadable, starting setup again', err);
    return null;
  }
}

export function saveNetwork(cfg: NetworkConfig) {
  const text = JSON.stringify(validateNetwork(cfg));
  writeFileSync(file(), safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf-8'));
}

export function clearNetwork() {
  rmSync(file(), { force: true });
}

export interface SetupHooks {
  role: 'agent' | 'client';
  getDeviceName?: () => string;
  getAutostart?: () => boolean;
  onSaved?: (cfg: NetworkConfig, opts: { deviceName?: string; autostart?: boolean }) => void;
  /** Setup finished (or was closed from invite/manage mode). */
  onDone: () => void;
  /** Device left the network — config is already deleted. */
  onLeft: () => void;
}

export function registerSetupIpc(hooks: SetupHooks) {
  ipcMain.handle('setup:get-network', () => loadNetwork());
  ipcMain.handle('setup:get-device-name', () => hooks.getDeviceName?.() ?? '');
  ipcMain.handle('setup:get-autostart', () => hooks.getAutostart?.() ?? true);
  ipcMain.handle('setup:save-network', (_e, cfg: NetworkConfig, opts: { deviceName?: string; autostart?: boolean }) => {
    saveNetwork(cfg);
    hooks.onSaved?.(validateNetwork(cfg), opts ?? {});
  });
  ipcMain.handle('setup:leave-network', () => {
    clearNetwork();
    hooks.onLeft();
  });
  ipcMain.on('setup:done', () => hooks.onDone());
}

export function loadSetup(win: BrowserWindow, mode: 'first' | 'invite' | 'manage') {
  return win.loadFile(join(__dirname, 'renderer', 'setup.html'), { query: { mode } });
}
