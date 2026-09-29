import { app, BrowserWindow, ipcMain, Menu, nativeImage, Notification, session, Tray } from 'electron';
import { join } from 'node:path';
import { setAutostart } from './autostart.js';
import { loadOrCreateLocalConfig, saveLocalConfig } from './deviceId.js';
import { loadNetwork, loadSetup, registerSetupIpc } from '@mcc/shared/src/electron/networkStore.js';

// The agent is a background camera/mic source that only becomes active
// while one of the owner's clients is viewing it. It is never invisible:
// a tray icon is always shown, it turns red and a notification pops up for
// as long as someone is watching, and joining a network is an explicit,
// on-device step with a consent checkbox.
app.disableHardwareAcceleration();

// Separate profile (portable use, or several instances on one machine for testing).
if (process.env.CC_USER_DATA) app.setPath('userData', process.env.CC_USER_DATA);

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

let worker: BrowserWindow | null = null;
let setupWin: BrowserWindow | null = null;
let tray: Tray | null = null;
let streaming = false;
let local: ReturnType<typeof loadOrCreateLocalConfig>;

const icon = (name: string) => nativeImage.createFromPath(join(__dirname, 'assets', name));

function refreshTray() {
  if (!tray) return;
  const net = loadNetwork();
  tray.setImage(icon(streaming ? 'tray-live.png' : 'tray-idle.png'));
  tray.setToolTip(`CamerasController Agent — ${net ? (streaming ? 'идёт просмотр камеры' : `сеть «${net.name}», ожидание`) : 'не подключён к сети'}`);
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'CamerasController Agent', enabled: false },
      { label: net ? `Сеть: ${net.name}` : 'Не подключён к сети', enabled: false },
      { label: streaming ? '● Камеру сейчас смотрят' : 'Камера выключена', enabled: false },
      { type: 'separator' },
      net
        ? { label: 'Сеть и отключение…', click: () => openSetup('manage') }
        : { label: 'Подключить к сети…', click: () => openSetup('first') },
      {
        label: 'Запускать при входе в систему',
        type: 'checkbox',
        checked: local.autostart !== false,
        enabled: !!net,
        click: (item) => {
          local.autostart = item.checked;
          saveLocalConfig(local);
          setAutostart(item.checked);
        },
      },
      { type: 'separator' },
      { label: 'Закрыть агент', click: () => app.quit() },
    ])
  );
}

function openSetup(mode: 'first' | 'manage') {
  if (setupWin) {
    setupWin.focus();
    return;
  }
  setupWin = new BrowserWindow({
    width: 680,
    height: 820,
    title: 'CamerasController Agent',
    autoHideMenuBar: true,
    backgroundColor: '#05080d',
    webPreferences: { preload: join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  setupWin.on('closed', () => (setupWin = null));
  loadSetup(setupWin, mode);
}

function startWorker() {
  if (worker || !loadNetwork()) return;
  setAutostart(local.autostart !== false);
  worker = new BrowserWindow({
    show: false,
    webPreferences: { preload: join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  worker.loadFile(join(__dirname, 'renderer', 'index.html'));
  // WebRTC by default gathers a host candidate for every network adapter —
  // including VPN tunnel interfaces a remote viewer can never reach, which
  // flood ICE with dead-end pairs. Keep gathering to the default-route
  // interface (still both its private and public/STUN address).
  worker.webContents.setWebRTCIPHandlingPolicy('default_public_and_private_interfaces');
  worker.on('closed', () => (worker = null));
  refreshTray();
}

function stopWorker() {
  worker?.destroy();
  worker = null;
  streaming = false;
  refreshTray();
}

app.on('second-instance', () => openSetup(loadNetwork() ? 'manage' : 'first'));

app.whenReady().then(() => {
  local = loadOrCreateLocalConfig();

  // No one is sitting in front of the worker window to click "Allow camera",
  // so our own renderer's media requests are granted here. Only our own
  // local files ever load in this app.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media');
  });

  registerSetupIpc({
    role: 'agent',
    getDeviceName: () => local.name,
    getAutostart: () => local.autostart !== false,
    onSaved: (_cfg, opts) => {
      if (opts.deviceName) local.name = opts.deviceName.slice(0, 100);
      local.autostart = opts.autostart !== false;
      saveLocalConfig(local);
    },
    onDone: () => {
      setupWin?.close();
      startWorker();
      refreshTray();
    },
    onLeft: () => {
      setAutostart(false);
      stopWorker();
      setupWin?.close();
      refreshTray();
    },
  });

  ipcMain.handle('get-device-info', () => ({
    id: local.deviceId,
    name: local.name,
    platform: process.platform === 'win32' ? 'windows' : 'linux',
  }));
  ipcMain.handle('get-network', () => loadNetwork());

  ipcMain.on('stream-state', (_e, live: boolean) => {
    if (live && !streaming && Notification.isSupported()) {
      new Notification({
        title: 'Камеру сейчас смотрят',
        body: `«${local.name}» передаёт видео и звук в вашу сеть CamerasController.`,
        icon: icon('tray-live.png'),
      }).show();
    }
    streaming = live;
    refreshTray();
  });

  // Fired by the worker when this device's record was deleted from a client
  // — someone removed this camera from the network. Stop working until the
  // agent is started again.
  ipcMain.on('quit-app', () => stopWorker());
  ipcMain.on('agent-log', (_e, msg: string) => console.log('[renderer]', msg));

  tray = new Tray(icon('tray-idle.png'));
  tray.on('click', () => tray?.popUpContextMenu());
  refreshTray();

  if (loadNetwork()) startWorker();
  else openSetup('first');
});

app.on('window-all-closed', () => {
  // Keep running in the tray — this app is the background service.
});
