import { contextBridge, ipcRenderer } from 'electron';

/** Exposes the setup screen's API; each app's preload calls this with its role. */
export function exposeSetupApi(role: 'agent' | 'client') {
  contextBridge.exposeInMainWorld('mccSetup', {
    role,
    getNetwork: () => ipcRenderer.invoke('setup:get-network'),
    getDeviceName: () => ipcRenderer.invoke('setup:get-device-name'),
    getAutostart: () => ipcRenderer.invoke('setup:get-autostart'),
    saveNetwork: (cfg: unknown, opts: unknown) => ipcRenderer.invoke('setup:save-network', cfg, opts),
    leaveNetwork: () => ipcRenderer.invoke('setup:leave-network'),
    done: () => ipcRenderer.send('setup:done'),
  });
}
