const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('estacaCast', {
  getState: () => ipcRenderer.invoke('state:get'),
  prepareComputer: () => ipcRenderer.invoke('setup:prepare'),
  configureFirewall: () => ipcRenderer.invoke('setup:firewall'),
  installOrOpenObs: () => ipcRenderer.invoke('obs:install-or-open'),
  openObs: () => ipcRenderer.invoke('obs:open'),
  startLocal: () => ipcRenderer.invoke('local:start'),
  stopAll: () => ipcRenderer.invoke('local:stop-all'),
  openPlayer: () => ipcRenderer.invoke('local:open-player'),
  copyText: (text) => ipcRenderer.invoke('clipboard:write', text),
  createQr: (text) => ipcRenderer.invoke('qr:create', text),
  startYoutube: (payload) => ipcRenderer.invoke('youtube:start', payload),
  stopYoutube: () => ipcRenderer.invoke('youtube:stop'),
  getSavedYoutubeKey: () => ipcRenderer.invoke('youtube:get-saved-key'),
  openYoutubeStudio: () => ipcRenderer.invoke('youtube:open-studio'),
  openLogs: () => ipcRenderer.invoke('logs:open'),
  checkUpdates: () => ipcRenderer.invoke('updates:check'),
  importObsBackup: () => ipcRenderer.invoke('obs:import-backup'),
  onState: (cb) => ipcRenderer.on('state:update', (_e, state) => cb(state)),
  onSetupProgress: (cb) => ipcRenderer.on('setup:progress', (_e, data) => cb(data)),
  onUpdateStatus: (cb) => ipcRenderer.on('updates:status', (_e, data) => cb(data)),
});
