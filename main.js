const { app, BrowserWindow, ipcMain, dialog, shell, clipboard, safeStorage } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const QRCode = require('qrcode');
const { createRuntime } = require('./services/runtime');

const singleInstance = app.requestSingleInstanceLock();
if (!singleInstance) app.quit();

let mainWindow = null;
let runtime = null;
let stateTimer = null;
let updateStatus = 'idle';

function appRoot() {
  return path.join(process.env.LOCALAPPDATA || app.getPath('appData'), 'EstacaCast');
}

function getPaths() {
  const root = appRoot();
  return {
    root,
    tools: path.join(root, 'tools'),
    logs: path.join(root, 'logs'),
    state: path.join(root, 'state'),
    mediamtxDir: path.join(root, 'tools', 'mediamtx'),
    ffmpegDir: path.join(root, 'tools', 'ffmpeg'),
    temp: app.getPath('temp'),
    config: path.join(root, 'config.json'),
  };
}

function loadConfig() {
  const p = getPaths().config;
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return {}; }
}

function saveConfig(cfg) {
  const p = getPaths().config;
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(cfg, null, 2), 'utf8');
}

function encryptSecret(text) {
  if (!text) return '';
  if (!safeStorage.isEncryptionAvailable()) return '';
  return safeStorage.encryptString(text).toString('base64');
}

function decryptSecret(encoded) {
  if (!encoded || !safeStorage.isEncryptionAvailable()) return '';
  try { return safeStorage.decryptString(Buffer.from(encoded, 'base64')); } catch (_) { return ''; }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 900,
    minWidth: 1000,
    minHeight: 700,
    backgroundColor: '#07101f',
    title: 'Estaca Cast',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

async function sendState() {
  if (!mainWindow || mainWindow.isDestroyed() || !runtime) return;
  const state = await runtime.getState();
  state.version = app.getVersion();
  state.updateStatus = updateStatus;
  mainWindow.webContents.send('state:update', state);
}

function setupAutoUpdater() {
  if (!app.isPackaged) return;
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('checking-for-update', () => {
    updateStatus = 'checking';
    mainWindow?.webContents.send('updates:status', { status: 'checking', message: 'Verificando atualizações…' });
  });
  autoUpdater.on('update-available', (info) => {
    updateStatus = 'available';
    mainWindow?.webContents.send('updates:status', { status: 'available', message: `Baixando versão ${info.version}…` });
  });
  autoUpdater.on('download-progress', (p) => {
    mainWindow?.webContents.send('updates:status', { status: 'downloading', message: `Baixando atualização… ${Math.round(p.percent)}%` });
  });
  autoUpdater.on('update-not-available', () => {
    updateStatus = 'latest';
    mainWindow?.webContents.send('updates:status', { status: 'latest', message: 'Você já está na versão mais recente.' });
  });
  autoUpdater.on('error', (err) => {
    updateStatus = 'error';
    mainWindow?.webContents.send('updates:status', { status: 'error', message: err?.message || 'Erro ao verificar atualizações.' });
  });
  autoUpdater.on('update-downloaded', (info) => {
    updateStatus = 'downloaded';
    dialog.showMessageBox(mainWindow, {
      type: 'info',
      title: 'Atualização pronta',
      message: `A versão ${info.version} foi baixada.`,
      detail: 'O Estaca Cast pode reiniciar agora e instalar a atualização automaticamente.',
      buttons: ['Reiniciar e instalar', 'Depois'],
      defaultId: 0,
      cancelId: 1,
    }).then(({ response }) => {
      if (response === 0) autoUpdater.quitAndInstall();
    });
  });
}

async function configureFirewall() {
  const command = [
    "$ErrorActionPreference='Stop'",
    "Get-NetFirewallRule -DisplayName 'Estaca Cast WebRTC TCP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue",
    "Get-NetFirewallRule -DisplayName 'Estaca Cast WebRTC UDP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue",
    "New-NetFirewallRule -DisplayName 'Estaca Cast WebRTC TCP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8889,8888 | Out-Null",
    "New-NetFirewallRule -DisplayName 'Estaca Cast WebRTC UDP' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8189 | Out-Null",
  ].join('; ');
  const psArgs = `-NoProfile -ExecutionPolicy Bypass -Command \"${command.replace(/\"/g, '\\\"')}\"`;
  spawn('powershell.exe', ['-NoProfile', '-Command', `Start-Process powershell.exe -Verb RunAs -Wait -ArgumentList '${psArgs.replace(/'/g, "''")}'`], { windowsHide: true });
  return { ok: true, message: 'O Windows exibirá a confirmação de administrador para liberar as portas locais.' };
}

async function installOrOpenObs() {
  const state = await runtime.getState();
  if (state.obsPath) {
    spawn(state.obsPath, [], { detached: true, windowsHide: false });
    return { action: 'opened' };
  }
  try {
    spawn('winget.exe', ['install', '--id', 'OBSProject.OBSStudio', '-e', '--accept-package-agreements', '--accept-source-agreements'], { detached: true, windowsHide: false });
    return { action: 'installing' };
  } catch (_) {
    await shell.openExternal('https://obsproject.com/download');
    return { action: 'website' };
  }
}

app.whenReady().then(() => {
  runtime = createRuntime(getPaths());
  createWindow();
  setupAutoUpdater();

  mainWindow.webContents.once('did-finish-load', () => {
    sendState();
    setTimeout(() => {
      if (app.isPackaged) autoUpdater.checkForUpdates().catch(() => {});
    }, 5000);
  });

  stateTimer = setInterval(sendState, 1500);
});

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  }
});

app.on('window-all-closed', () => {
  if (stateTimer) clearInterval(stateTimer);
  if (runtime) runtime.stopAll();
  app.quit();
});

ipcMain.handle('state:get', async () => {
  const s = await runtime.getState();
  return { ...s, version: app.getVersion(), updateStatus };
});

ipcMain.handle('setup:prepare', async () => {
  try {
    await runtime.prepare((data) => mainWindow?.webContents.send('setup:progress', data));
    await sendState();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('setup:firewall', configureFirewall);
ipcMain.handle('obs:install-or-open', installOrOpenObs);
ipcMain.handle('obs:open', async () => {
  const s = await runtime.getState();
  if (!s.obsPath) return { ok: false, error: 'OBS não encontrado.' };
  spawn(s.obsPath, [], { detached: true, windowsHide: false });
  return { ok: true };
});

ipcMain.handle('local:start', async () => {
  try {
    await runtime.startMediaMtx();
    await sendState();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('local:stop-all', async () => {
  runtime.stopAll();
  await sendState();
  return { ok: true };
});

ipcMain.handle('local:open-player', async () => {
  const s = await runtime.getState();
  await shell.openExternal(s.localUrl);
  return { ok: true };
});

ipcMain.handle('clipboard:write', (_e, text) => {
  clipboard.writeText(String(text || ''));
  return { ok: true };
});

ipcMain.handle('qr:create', async (_e, text) => {
  return QRCode.toDataURL(String(text || ''), { width: 360, margin: 2 });
});

ipcMain.handle('youtube:start', async (_e, payload) => {
  try {
    const key = String(payload?.key || '').trim();
    if (payload?.remember && key) {
      const cfg = loadConfig();
      cfg.youtubeKey = encryptSecret(key);
      saveConfig(cfg);
    } else if (!payload?.remember) {
      const cfg = loadConfig();
      delete cfg.youtubeKey;
      saveConfig(cfg);
    }
    await runtime.startYoutube(key, { bitrate: payload?.bitrate || '4000k' });
    await sendState();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('youtube:stop', async () => {
  runtime.stopYoutube();
  await sendState();
  return { ok: true };
});

ipcMain.handle('youtube:get-saved-key', () => {
  const cfg = loadConfig();
  return decryptSecret(cfg.youtubeKey || '');
});

ipcMain.handle('youtube:open-studio', async () => {
  await shell.openExternal('https://studio.youtube.com/');
  return { ok: true };
});

ipcMain.handle('logs:open', async () => {
  const dir = getPaths().logs;
  fs.mkdirSync(dir, { recursive: true });
  await shell.openPath(dir);
  return { ok: true };
});

ipcMain.handle('updates:check', async () => {
  if (!app.isPackaged) return { ok: false, error: 'Atualizações automáticas funcionam no instalador publicado.' };
  try {
    await autoUpdater.checkForUpdates();
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('obs:import-backup', async () => {
  const choice = await dialog.showOpenDialog(mainWindow, {
    title: 'Selecionar pacote do OBS',
    properties: ['openFile'],
    filters: [{ name: 'Pacotes ZIP', extensions: ['zip'] }],
  });
  if (choice.canceled || !choice.filePaths[0]) return { ok: false, canceled: true };
  return { ok: false, error: 'Importação automática de cenas/perfis entrará na próxima etapa do projeto. O arquivo foi selecionado, mas ainda não será aplicado.' };
});
