import { app, BrowserWindow, ipcMain } from 'electron';
import { join } from 'node:path';
import { createWriteStream, existsSync, mkdirSync, renameSync, type WriteStream } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { loadNetwork, loadSetup, registerSetupIpc } from '@mcc/shared/src/electron/networkStore.js';

// Separate profile (portable use, or several instances on one machine for testing).
if (process.env.CC_USER_DATA) app.setPath('userData', process.env.CC_USER_DATA);

let win: BrowserWindow | null = null;
let inviteWin: BrowserWindow | null = null;

const webPreferences = () => ({
  preload: join(__dirname, 'preload.js'),
  contextIsolation: true,
  nodeIntegration: false,
});

/** The console itself, or the setup screen when this install has no network yet. */
function loadMain() {
  if (!win) return;
  if (loadNetwork()) win.loadFile(join(__dirname, 'renderer', 'index.html'));
  else loadSetup(win, 'first');
}

function openInvite() {
  if (inviteWin) return inviteWin.focus();
  inviteWin = new BrowserWindow({
    width: 760,
    height: 700,
    parent: win ?? undefined,
    title: 'Пригласить устройство',
    autoHideMenuBar: true,
    backgroundColor: '#05080d',
    webPreferences: webPreferences(),
  });
  inviteWin.on('closed', () => (inviteWin = null));
  loadSetup(inviteWin, 'invite');
}

app.whenReady().then(() => {
  registerSetupIpc({
    role: 'client',
    onDone: () => {
      if (inviteWin) inviteWin.close();
      else loadMain();
    },
    onLeft: () => {
      inviteWin?.close();
      loadMain();
    },
  });
  ipcMain.handle('get-network', () => loadNetwork());
  ipcMain.on('open-invite', () => openInvite());

  win = new BrowserWindow({
    width: 1100,
    height: 720,
    title: 'CamerasController',
    backgroundColor: '#05080d',
    webPreferences: {
      ...webPreferences(),
      // Recordings must keep advancing while the window is minimized or
      // behind another one, so the renderer (and the <video> elements
      // decoding every open camera) can't be throttled just because it
      // isn't currently visible.
      backgroundThrottling: false,
    },
  });
  win.setMenuBarVisibility(false);
  loadMain();

  // Ctrl+wheel (and pinch-to-zoom on a trackpad) otherwise triggers
  // Chromium's own page zoom — independent of, and invisible to, the
  // canvas's own 'wheel' listener that steps through view modes. This app
  // has no use for page zoom, so pin it at 1x.
  win.webContents.setVisualZoomLevelLimits(1, 1);

  // WebRTC by default gathers a host candidate for every network adapter,
  // including dead ends a peer on another machine can never reach (VPN
  // tunnels, Teredo, …). Restricting gathering to the default-route
  // interface (still both its private and public/STUN address) keeps ICE
  // on pairs that can actually connect.
  win.webContents.setWebRTCIPHandlingPolicy('default_public_and_private_interfaces');
});

// Renderer diagnostics on stdout (run from a terminal to see them).
ipcMain.on('diag-log', (_e, msg: string) => console.log('[renderer]', msg));

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// ---------- per-viewing-session recording ----------
// Every viewing session is recorded to disk automatically, start to finish,
// so there's a local record of exactly when a given camera was watched.

function sanitizeForFilename(s: string): string {
  return s.replace(/[^a-zA-Z0-9а-яА-ЯёЁ_-]+/g, '_');
}

function recordingsDir(): string {
  const dir = join(app.getPath('videos'), 'CamerasController');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  return dir;
}

interface OpenRecording {
  stream: WriteStream;
  partPath: string;
  deviceName: string;
  startIso: string;
}
const openRecordings = new Map<string, OpenRecording>();

ipcMain.handle('recording-start', (_e, deviceName: string, startIso: string) => {
  const recordingId = randomUUID();
  const partPath = join(
    recordingsDir(),
    `${sanitizeForFilename(deviceName)}_${sanitizeForFilename(startIso)}.webm.part`
  );
  openRecordings.set(recordingId, {
    stream: createWriteStream(partPath),
    partPath,
    deviceName,
    startIso,
  });
  return recordingId;
});

ipcMain.on('recording-chunk', (_e, recordingId: string, chunk: ArrayBuffer) => {
  openRecordings.get(recordingId)?.stream.write(Buffer.from(chunk));
});

ipcMain.on('recording-finish', (_e, recordingId: string, endIso: string) => {
  const rec = openRecordings.get(recordingId);
  if (!rec) return;
  openRecordings.delete(recordingId);
  rec.stream.end(() => {
    const finalPath = join(
      recordingsDir(),
      `${sanitizeForFilename(rec.deviceName)}_${sanitizeForFilename(rec.startIso)}_to_${sanitizeForFilename(endIso)}.webm`
    );
    try {
      renameSync(rec.partPath, finalPath);
    } catch {
      // best-effort — the .part file is still a valid, playable recording either way
    }
  });
});
