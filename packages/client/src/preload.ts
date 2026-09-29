import { contextBridge, ipcRenderer } from 'electron';
import { exposeSetupApi } from '@mcc/shared/src/electron/setupPreload.js';

contextBridge.exposeInMainWorld('mcc', {
  startRecording: (deviceName: string, startIso: string): Promise<string> =>
    ipcRenderer.invoke('recording-start', deviceName, startIso),
  writeRecordingChunk: (recordingId: string, chunk: ArrayBuffer) =>
    ipcRenderer.send('recording-chunk', recordingId, chunk),
  finishRecording: (recordingId: string, endIso: string) =>
    ipcRenderer.send('recording-finish', recordingId, endIso),
  log: (msg: string) => ipcRenderer.send('diag-log', msg),
  getNetwork: () => ipcRenderer.invoke('get-network'),
  openInvite: () => ipcRenderer.send('open-invite'),
});

exposeSetupApi('client');
