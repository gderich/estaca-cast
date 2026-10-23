const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const https = require('https');
const net = require('net');
const { spawn, spawnSync } = require('child_process');
const AdmZip = require('adm-zip');

const MEDIAMTX_VERSION = '1.21.2';
const MEDIAMTX_URL = `https://github.com/bluenviron/mediamtx/releases/download/v${MEDIAMTX_VERSION}/mediamtx_v${MEDIAMTX_VERSION}_windows_amd64.zip`;

function createRuntime(paths) {
  let mediaProc = null;
  let youtubeProc = null;

  for (const dir of [paths.root, paths.tools, paths.logs, paths.state, paths.mediamtxDir, paths.ffmpegDir]) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const mediaExe = path.join(paths.mediamtxDir, 'mediamtx.exe');
  const mediaCfg = path.join(paths.mediamtxDir, 'mediamtx.yml');
  const mediaLog = path.join(paths.logs, 'mediamtx.log');
  const youtubeLog = path.join(paths.logs, 'youtube-ffmpeg.log');

  function logLine(file, line) {
    fs.appendFileSync(file, `[${new Date().toISOString()}] ${line}\n`, 'utf8');
  }

  function download(url, dest, onProgress) {
    return new Promise((resolve, reject) => {
      const request = (target) => {
        https.get(target, (res) => {
          if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
            res.resume();
            return request(res.headers.location);
          }
          if (res.statusCode !== 200) {
            res.resume();
            return reject(new Error(`Download falhou (HTTP ${res.statusCode}).`));
          }
          const total = Number(res.headers['content-length'] || 0);
          let got = 0;
          const out = fs.createWriteStream(dest);
          res.on('data', (chunk) => {
            got += chunk.length;
            if (total && onProgress) onProgress(got / total);
          });
          res.pipe(out);
          out.on('finish', () => out.close(resolve));
          out.on('error', reject);
        }).on('error', reject);
      };
      request(url);
    });
  }

  function findFfmpeg() {
    const local = path.join(paths.ffmpegDir, 'ffmpeg.exe');
    if (fs.existsSync(local)) return local;

    const where = spawnSync('where.exe', ['ffmpeg.exe'], { encoding: 'utf8', windowsHide: true });
    if (where.status === 0) {
      const first = String(where.stdout || '').split(/\r?\n/).find(Boolean);
      if (first && fs.existsSync(first.trim())) return first.trim();
    }

    const candidates = [
      path.join(process.env.LOCALAPPDATA || '', 'Microsoft', 'WinGet', 'Links', 'ffmpeg.exe'),
      'C:\\ffmpeg\\bin\\ffmpeg.exe',
    ];
    return candidates.find((p) => p && fs.existsSync(p)) || null;
  }

  function findObs() {
    const candidates = [
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'obs-studio', 'bin', '64bit', 'obs64.exe'),
      path.join(process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', 'obs-studio', 'bin', '64bit', 'obs64.exe'),
    ];
    return candidates.find(fs.existsSync) || null;
  }

  function getLocalIp() {
    const nets = os.networkInterfaces();
    const preferred = [];
    const fallback = [];
    for (const [name, entries] of Object.entries(nets)) {
      for (const addr of entries || []) {
        if (addr.family !== 'IPv4' || addr.internal) continue;
        const item = { name: name.toLowerCase(), address: addr.address };
        if (/wi-?fi|wireless|ethernet|lan/.test(item.name)) preferred.push(item);
        else fallback.push(item);
      }
    }
    return (preferred[0] || fallback[0] || { address: '127.0.0.1' }).address;
  }

  function testPort(port, host = '127.0.0.1', timeout = 450) {
    return new Promise((resolve) => {
      const sock = new net.Socket();
      let done = false;
      const finish = (value) => {
        if (done) return;
        done = true;
        try { sock.destroy(); } catch (_) {}
        resolve(value);
      };
      sock.setTimeout(timeout);
      sock.once('connect', () => finish(true));
      sock.once('timeout', () => finish(false));
      sock.once('error', () => finish(false));
      sock.connect(port, host);
    });
  }

  function fetchJson(url) {
    return new Promise((resolve, reject) => {
      http.get(url, { timeout: 900 }, (res) => {
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (c) => body += c);
        res.on('end', () => {
          if (res.statusCode < 200 || res.statusCode >= 300) return reject(new Error(`HTTP ${res.statusCode}`));
          try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
        });
      }).on('error', reject);
    });
  }

  async function waitPort(port, timeoutMs = 7000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (await testPort(port)) return true;
      await new Promise((r) => setTimeout(r, 250));
    }
    return false;
  }

  function writeMediaConfig() {
    const cfg = [
      'logLevel: info',
      'api: true',
      'apiAddress: 127.0.0.1:9997',
      'srt: true',
      'srtAddress: :8890',
      'hlsAlwaysRemux: true',
      'hlsVariant: lowLatency',
      'hlsSegmentCount: 7',
      'hlsSegmentDuration: 1s',
      'hlsPartDuration: 200ms',
      'paths:',
      '  conferencia:',
      '    source: publisher',
      '  camera:',
      '    source: publisher',
      '',
    ].join('\n');
    fs.writeFileSync(mediaCfg, cfg, { encoding: 'utf8' });
  }

  async function installMediaMtx(onProgress) {
    if (fs.existsSync(mediaExe)) return;
    const zipPath = path.join(paths.temp, `estacacast-mediamtx-${Date.now()}.zip`);
    onProgress?.({ percent: 0.05, message: 'Baixando MediaMTX…' });
    await download(MEDIAMTX_URL, zipPath, (p) => onProgress?.({
      percent: 0.05 + p * 0.35,
      message: `Baixando MediaMTX… ${Math.round(p * 100)}%`,
    }));
    onProgress?.({ percent: 0.43, message: 'Extraindo MediaMTX…' });
    const zip = new AdmZip(zipPath);
    zip.extractAllTo(paths.mediamtxDir, true);
    try { fs.unlinkSync(zipPath); } catch (_) {}
    if (!fs.existsSync(mediaExe)) {
      throw new Error('O MediaMTX foi baixado, mas mediamtx.exe não foi encontrado após a extração.');
    }
  }

  async function installFfmpeg(onProgress) {
    if (findFfmpeg()) return;
    onProgress?.({ percent: 0.5, message: 'FFmpeg não encontrado. Instalando pelo Windows Package Manager…' });
    const result = spawnSync('winget.exe', [
      'install', '--id', 'Gyan.FFmpeg', '-e',
      '--accept-package-agreements', '--accept-source-agreements',
      '--disable-interactivity',
    ], { encoding: 'utf8', windowsHide: true });
    if (result.status !== 0 && !findFfmpeg()) {
      throw new Error('Não foi possível instalar o FFmpeg automaticamente. Instale Gyan.FFmpeg pelo winget e tente novamente.');
    }
  }

  async function prepare(onProgress) {
    onProgress?.({ percent: 0.01, message: 'Verificando ferramentas…' });
    await installMediaMtx(onProgress);
    writeMediaConfig();
    onProgress?.({ percent: 0.48, message: 'MediaMTX pronto, incluindo câmera SRT.' });
    await installFfmpeg(onProgress);
    onProgress?.({ percent: 1, message: 'Tudo pronto.' });
  }

  async function startMediaMtx() {
    if (await testPort(8889)) return true;
    if (!fs.existsSync(mediaExe)) await prepare();
    writeMediaConfig();

    const out = fs.openSync(mediaLog, 'a');
    mediaProc = spawn(mediaExe, [mediaCfg], {
      cwd: paths.mediamtxDir,
      windowsHide: true,
      stdio: ['ignore', out, out],
    });
    mediaProc.on('exit', (code) => logLine(mediaLog, `MediaMTX finalizado com código ${code}`));

    if (!(await waitPort(8889, 8000))) {
      let tail = '';
      try { tail = fs.readFileSync(mediaLog, 'utf8').split(/\r?\n/).slice(-18).join('\n'); } catch (_) {}
      throw new Error(`Não foi possível iniciar o MediaMTX.\n\n${tail}`);
    }
    return true;
  }

  async function pathPublishing(name) {
    if (!(await testPort(9997))) return false;
    try {
      const data = await fetchJson('http://127.0.0.1:9997/v3/paths/list');
      return (data.items || []).some((x) => x.name === name && (x.ready === true || x.sourceReady === true));
    } catch (_) {
      return false;
    }
  }

  async function obsPublishing() {
    return pathPublishing('conferencia');
  }

  async function cameraPublishing() {
    return pathPublishing('camera');
  }

  async function viewerCount() {
    if (!(await testPort(9997))) return 0;
    try {
      const data = await fetchJson('http://127.0.0.1:9997/v3/webrtcsessions/list');
      return (data.items || []).filter((x) => x.path === 'conferencia' && x.state !== 'publish').length;
    } catch (_) {
      return 0;
    }
  }

  async function startYoutube(key, options = {}) {
    key = String(key || '').trim();
    if (!key) throw new Error('Cole a chave de transmissão do YouTube.');
    await startMediaMtx();
    if (!(await obsPublishing())) {
      throw new Error('O OBS ainda não está publicando. Clique em “Iniciar transmissão” no OBS e tente novamente.');
    }
    const ffmpeg = findFfmpeg();
    if (!ffmpeg) throw new Error('FFmpeg não encontrado. Use “Preparar computador” primeiro.');
    stopYoutube();

    const bitrate = options.bitrate || '4000k';
    const args = [
      '-hide_banner', '-loglevel', 'warning',
      '-rtsp_transport', 'tcp',
      '-i', 'rtsp://127.0.0.1:8554/conferencia',
      '-map', '0:v:0', '-map', '0:a:0',
      '-c:v', 'libx264',
      '-preset', 'veryfast',
      '-profile:v', 'high',
      '-pix_fmt', 'yuv420p',
      '-r', '30',
      '-g', '60',
      '-keyint_min', '60',
      '-sc_threshold', '0',
      '-b:v', bitrate,
      '-maxrate', bitrate,
      '-bufsize', '8000k',
      '-c:a', 'aac',
      '-b:a', '128k',
      '-ar', '48000',
      '-f', 'flv',
      `rtmps://a.rtmps.youtube.com/live2/${key}`,
    ];

    const out = fs.openSync(youtubeLog, 'a');
    youtubeProc = spawn(ffmpeg, args, {
      windowsHide: true,
      stdio: ['ignore', out, out],
    });
    youtubeProc.on('exit', (code) => {
      logLine(youtubeLog, `FFmpeg YouTube finalizado com código ${code}`);
      youtubeProc = null;
    });

    await new Promise((r) => setTimeout(r, 1300));
    if (!youtubeProc || youtubeProc.exitCode !== null) {
      let tail = '';
      try { tail = fs.readFileSync(youtubeLog, 'utf8').split(/\r?\n/).slice(-16).join('\n'); } catch (_) {}
      throw new Error(`O FFmpeg não conseguiu iniciar o envio ao YouTube.\n\n${tail}`);
    }
    return true;
  }

  function stopYoutube() {
    if (youtubeProc && youtubeProc.exitCode === null) {
      try { youtubeProc.kill(); } catch (_) {}
    }
    youtubeProc = null;
  }

  function stopAll() {
    stopYoutube();
    if (mediaProc && mediaProc.exitCode === null) {
      try { mediaProc.kill(); } catch (_) {}
    }
    mediaProc = null;
  }

  async function getState() {
    const ffmpeg = findFfmpeg();
    const obsPath = findObs();
    const mediaOnline = await testPort(8889);
    const ip = getLocalIp();
    const publishing = mediaOnline ? await obsPublishing() : false;
    const mobileCamera = mediaOnline ? await cameraPublishing() : false;

    return {
      tools: { mediamtx: fs.existsSync(mediaExe), ffmpeg: Boolean(ffmpeg) },
      mediaOnline,
      obsInstalled: Boolean(obsPath),
      obsPath,
      obsPublishing: publishing,
      cameraPublishing: mobileCamera,
      youtubeSending: Boolean(youtubeProc && youtubeProc.exitCode === null),
      localIp: ip,
      localUrl: `http://${ip}:8889/conferencia`,
      whipUrl: 'http://localhost:8889/conferencia/whip',
      cameraRtspUrl: 'rtsp://localhost:8554/camera',
      cameraSrtPort: 8890,
      cameraStreamId: 'publish:camera',
      viewers: mediaOnline ? await viewerCount() : 0,
      logsDir: paths.logs,
    };
  }

  return {
    prepare,
    startMediaMtx,
    startYoutube,
    stopYoutube,
    stopAll,
    getState,
  };
}

module.exports = { createRuntime };
