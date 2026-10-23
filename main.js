const { app, BrowserWindow, ipcMain, dialog, shell, clipboard, safeStorage } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('path');
const fs = require('fs');
const os = require('os');
const dgram = require('dgram');
const { spawn } = require('child_process');
const QRCode = require('qrcode');
const { createRuntime } = require('./services/runtime');
const { addCameraToObs } = require('./services/obs');

const singleInstance = app.requestSingleInstanceLock();
if (!singleInstance) app.quit();

let mainWindow = null;
let runtime = null;
let stateTimer = null;
let updateStatus = 'idle';
let discoverySocket = null;

const ANDROID_APK_URL = 'https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Camera.apk';

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

function startDiscoveryService() {
  if (discoverySocket || !runtime) return;

  discoverySocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discoverySocket.on('error', () => {});
  discoverySocket.on('message', async (msg, rinfo) => {
    if (String(msg || '').trim() !== 'ESTACACAST_DISCOVER_V1') return;

    try {
      let state = await runtime.getState();
      if (state.tools?.mediamtx && !state.mediaOnline) {
        try {
          await runtime.startMediaMtx();
          state = await runtime.getState();
          await sendState();
        } catch (_) {}
      }

      const response = Buffer.from(JSON.stringify({
        type: 'estacacast',
        name: `Estaca Cast — ${os.hostname()}`,
        ip: state.localIp,
        ready: state.mediaOnline,
        srtPort: state.cameraSrtPort || 8890,
        streamId: state.cameraStreamId || 'publish:camera',
        rtspUrl: state.cameraRtspUrl || 'rtsp://localhost:8554/camera',
      }), 'utf8');

      discoverySocket?.send(response, rinfo.port, rinfo.address);
    } catch (_) {}
  });

  discoverySocket.bind(37020, '0.0.0.0');
}

function stopDiscoveryService() {
  if (!discoverySocket) return;
  try { discoverySocket.close(); } catch (_) {}
  discoverySocket = null;
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

function runPowerShell(args) {
  return new Promise((resolve) => {
    let stdout = '';
    let stderr = '';
    const child = spawn('powershell.exe', args, {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    child.stdout.on('data', (data) => { stdout += String(data); });
    child.stderr.on('data', (data) => { stderr += String(data); });
    child.on('error', (error) => resolve({ code: -1, stdout, stderr: error.message }));
    child.on('close', (code) => resolve({ code: Number(code ?? -1), stdout, stderr }));
  });
}

async function configureFirewall() {
  const token = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const resultFile = path.join(app.getPath('temp'), `estacacast-firewall-${token}.txt`);
  const escapedResult = resultFile.replace(/'/g, "''");

  const elevatedScript = `
$ErrorActionPreference = 'Stop'
$resultFile = '${escapedResult}'
try {
  $rules = @(
    @{ Name = 'Estaca Cast HLS TCP 8888'; Protocol = 'TCP'; Port = 8888 },
    @{ Name = 'Estaca Cast WebRTC TCP 8889'; Protocol = 'TCP'; Port = 8889 },
    @{ Name = 'Estaca Cast WebRTC UDP 8189'; Protocol = 'UDP'; Port = 8189 },
    @{ Name = 'Estaca Cast SRT UDP 8890'; Protocol = 'UDP'; Port = 8890 },
    @{ Name = 'Estaca Cast Discovery UDP 37020'; Protocol = 'UDP'; Port = 37020 }
  )

  $legacyNames = @(
    'Estaca Cast WebRTC TCP',
    'Estaca Cast WebRTC UDP',
    'Estaca Cast SRT',
    'Estaca Cast Camera Discovery'
  )

  foreach ($legacy in $legacyNames) {
    Get-NetFirewallRule -DisplayName $legacy -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
  }

  foreach ($rule in $rules) {
    Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow -Protocol $rule.Protocol -LocalPort $rule.Port -Profile Any -Enabled True -ErrorAction Stop | Out-Null
  }

  Set-Content -LiteralPath $resultFile -Value 'OK' -Encoding UTF8
  exit 0
} catch {
  Set-Content -LiteralPath $resultFile -Value ('ERROR|' + $_.Exception.Message) -Encoding UTF8
  exit 1
}
`;

  const encoded = Buffer.from(elevatedScript, 'utf16le').toString('base64');
  const launcher = [
    "$ErrorActionPreference='Stop'",
    'try {',
    `  $p = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand ${encoded}'`,
    '  exit $p.ExitCode',
    '} catch {',
    '  if ($_.Exception.NativeErrorCode -eq 1223) { exit 1223 }',
    '  Write-Error $_.Exception.Message',
    '  exit 1',
    '}',
  ].join('; ');

  const result = await runPowerShell([
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-Command', launcher,
  ]);

  let details = '';
  try {
    if (fs.existsSync(resultFile)) details = fs.readFileSync(resultFile, 'utf8').trim();
  } catch (_) {}
  try { if (fs.existsSync(resultFile)) fs.unlinkSync(resultFile); } catch (_) {}

  if (result.code === 1223) {
    return { ok: false, error: 'A confirmação de administrador foi cancelada.' };
  }

  if (result.code !== 0 || (details && !details.startsWith('OK'))) {
    const detailMessage = details.startsWith('ERROR|') ? details.slice(6) : (result.stderr.trim() || details);
    return {
      ok: false,
      error: `Não foi possível configurar o Firewall do Windows.${detailMessage ? `\n\nDetalhes: ${detailMessage}` : ''}`,
    };
  }

  const ruleNames = [
    'Estaca Cast HLS TCP 8888',
    'Estaca Cast WebRTC TCP 8889',
    'Estaca Cast WebRTC UDP 8189',
    'Estaca Cast SRT UDP 8890',
    'Estaca Cast Discovery UDP 37020',
  ];
  const verifyScript = `$missing=@(); ${ruleNames.map((name) => `if (-not (Get-NetFirewallRule -DisplayName '${name}' -ErrorAction SilentlyContinue)) { $missing += '${name}' }`).join('; ')}; if ($missing.Count -gt 0) { Write-Output ($missing -join '|'); exit 2 }; exit 0`;
  const verification = await runPowerShell(['-NoProfile', '-Command', verifyScript]);

  if (verification.code !== 0) {
    return {
      ok: false,
      error: `As regras foram criadas, mas a verificação final falhou.${verification.stdout.trim() ? `\n\nAusentes: ${verification.stdout.trim().replace(/\|/g, ', ')}` : ''}`,
    };
  }

  return {
    ok: true,
    message: 'Firewall configurado e verificado: TCP 8888/8889 e UDP 8189/8890/37020 liberados.',
  };
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
  startDiscoveryService();
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
  stopDiscoveryService();
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

ipcMain.handle('obs:add-camera', async () => {
  try {
    let s = await runtime.getState();
    if (!s.obsPath) return { ok: false, error: 'OBS não encontrado. Instale ou abra o OBS primeiro.' };

    if (!s.mediaOnline) {
      await runtime.startMediaMtx();
      s = await runtime.getState();
      await sendState();
    }

    return await addCameraToObs({
      rtspUrl: s.cameraRtspUrl || 'rtsp://localhost:8554/camera',
    });
  } catch (err) {
    return { ok: false, error: err?.message || String(err) };
  }
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

ipcMain.handle('android:download', async () => {
  await shell.openExternal(ANDROID_APK_URL);
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
