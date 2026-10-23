const { contextBridge, ipcRenderer } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

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

async function configureFirewallDirect() {
  const token = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const resultFile = path.join(os.tmpdir(), `estacacast-firewall-${token}.txt`);
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

  const expected = [
    'Estaca Cast HLS TCP 8888',
    'Estaca Cast WebRTC TCP 8889',
    'Estaca Cast WebRTC UDP 8189',
    'Estaca Cast SRT UDP 8890',
    'Estaca Cast Discovery UDP 37020',
  ];

  const verifyScript = `$missing=@(); ${expected.map((name) => `if (-not (Get-NetFirewallRule -DisplayName '${name}' -ErrorAction SilentlyContinue)) { $missing += '${name}' }`).join('; ')}; if ($missing.Count -gt 0) { Write-Output ($missing -join '|'); exit 2 }; exit 0`;
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

contextBridge.exposeInMainWorld('estacaCast', {
  getState: () => ipcRenderer.invoke('state:get'),
  prepareComputer: () => ipcRenderer.invoke('setup:prepare'),
  configureFirewall: configureFirewallDirect,
  installOrOpenObs: () => ipcRenderer.invoke('obs:install-or-open'),
  openObs: () => ipcRenderer.invoke('obs:open'),
  startLocal: () => ipcRenderer.invoke('local:start'),
  stopAll: () => ipcRenderer.invoke('local:stop-all'),
  openPlayer: () => ipcRenderer.invoke('local:open-player'),
  downloadAndroid: () => ipcRenderer.invoke('android:download'),
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
