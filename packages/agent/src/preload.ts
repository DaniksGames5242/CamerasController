import { contextBridge, ipcRenderer } from 'electron';
import { exposeSetupApi } from '@mcc/shared/src/electron/setupPreload.js';

contextBridge.exposeInMainWorld('mcc', {
  getDeviceInfo: () => ipcRenderer.invoke('get-device-info'),
  getNetwork: () => ipcRenderer.invoke('get-network'),
  setStreaming: (live: boolean) => ipcRenderer.send('stream-state', live),
  quitApp: () => ipcRenderer.send('quit-app'),
  log: (msg: string) => ipcRenderer.send('agent-log', msg),
});

exposeSetupApi('agent');
