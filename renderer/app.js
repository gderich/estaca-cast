const $ = (id) => document.getElementById(id);
let currentState = null;

function setStatus(el, text, kind = '') {
  el.textContent = text;
  el.classList.remove('good','warn');
  if (kind) el.classList.add(kind);
}

function renderState(s) {
  currentState = s;
  $('version').textContent = `v${s.version}`;
  setStatus($('statusTools'), s.tools.mediamtx && s.tools.ffmpeg ? 'Prontas' : 'Pendentes', s.tools.mediamtx && s.tools.ffmpeg ? 'good' : 'warn');
  setStatus($('statusMedia'), s.mediaOnline ? 'Online' : 'Parado', s.mediaOnline ? 'good' : '');
  setStatus($('statusObs'), s.obsPublishing ? 'Transmitindo' : (s.obsInstalled ? 'Pronto' : 'Não instalado'), s.obsPublishing ? 'good' : (s.obsInstalled ? '' : 'warn'));
  setStatus($('statusYoutube'), s.youtubeSending ? 'Enviando' : 'Desativado', s.youtubeSending ? 'good' : '');
  $('whipUrl').textContent = s.whipUrl;
  $('localUrl').textContent = s.localUrl;
  $('viewerCount').textContent = String(s.viewers || 0);
  $('cameraRtspUrl').textContent = s.cameraRtspUrl || 'rtsp://localhost:8554/camera';
  $('cameraStatus').textContent = s.cameraPublishing ? 'Conectada' : 'Desconectada';
  $('cameraStatus').className = s.cameraPublishing ? 'good' : '';

  const rows = [
    ['Versão', s.version],
    ['MediaMTX', s.mediaOnline ? 'Online' : 'Parado'],
    ['OBS instalado', s.obsInstalled ? 'Sim' : 'Não'],
    ['OBS publicando', s.obsPublishing ? 'Sim' : 'Não'],
    ['Câmera móvel', s.cameraPublishing ? 'Conectada' : 'Desconectada'],
    ['Fonte da câmera no OBS', s.cameraRtspUrl || 'rtsp://localhost:8554/camera'],
    ['SRT da câmera', `UDP ${s.cameraSrtPort || 8890}`],
    ['Descoberta da câmera', 'UDP 37020'],
    ['YouTube', s.youtubeSending ? 'FFmpeg enviando' : 'Parado'],
    ['IPv4 local', s.localIp],
    ['Player local', s.localUrl],
    ['WHIP do OBS', s.whipUrl],
    ['Logs', s.logsDir],
  ];
  $('diagGrid').innerHTML = rows.map(([k,v]) => `<div class="diag-item"><span>${k}</span><b>${String(v)}</b></div>`).join('');
}

async function refresh() {
  renderState(await window.estacaCast.getState());
}

function showQr({ title, hint, value }) {
  return window.estacaCast.createQr(value).then((src) => {
    $('qrTitle').textContent = title;
    $('qrHint').textContent = hint || '';
    $('qrImage').src = src;
    $('qrText').textContent = value;
    $('qrModal').classList.remove('hidden');
  });
}

for (const btn of document.querySelectorAll('.tab')) {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach((x) => x.classList.remove('active'));
    btn.classList.add('active');
    $(`tab-${btn.dataset.tab}`).classList.add('active');
  });
}

$('prepareBtn').onclick = async () => {
  $('setupText').textContent = 'Preparando…';
  const res = await window.estacaCast.prepareComputer();
  if (!res.ok) alert(res.error);
  else $('setupText').textContent = 'Tudo pronto. Você já pode iniciar o MediaMTX.';
  refresh();
};
$('firewallBtn').onclick = async () => {
  const btn = $('firewallBtn');
  const previous = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Configurando…';
  $('setupText').textContent = 'Aguardando confirmação de administrador do Windows…';

  try {
    const r = await window.estacaCast.configureFirewall();
    if (r.ok) {
      $('setupText').textContent = 'Firewall configurado e verificado.';
      alert(r.message || 'Firewall configurado com sucesso.');
    } else {
      $('setupText').textContent = 'Não foi possível configurar o firewall.';
      alert(r.error || 'Falha ao configurar o firewall.');
    }
  } catch (error) {
    $('setupText').textContent = 'Erro ao configurar o firewall.';
    alert(error?.message || String(error));
  } finally {
    btn.disabled = false;
    btn.textContent = previous;
  }
};
$('obsInstallBtn').onclick = () => window.estacaCast.installOrOpenObs();
$('openObsBtn').onclick = async () => { const r = await window.estacaCast.openObs(); if (!r.ok) alert(r.error); };
$('copyWhipBtn').onclick = () => window.estacaCast.copyText(currentState?.whipUrl || $('whipUrl').textContent);
$('startLocalBtn').onclick = async () => { const r = await window.estacaCast.startLocal(); if (!r.ok) alert(r.error); refresh(); };
$('stopAllBtn').onclick = async () => { await window.estacaCast.stopAll(); refresh(); };
$('copyLocalBtn').onclick = () => window.estacaCast.copyText(currentState?.localUrl || $('localUrl').textContent);
$('openPlayerBtn').onclick = () => window.estacaCast.openPlayer();
$('downloadAndroidBtn').onclick = () => window.estacaCast.downloadAndroid();
$('copyCameraBtn').onclick = () => window.estacaCast.copyText(currentState?.cameraRtspUrl || $('cameraRtspUrl').textContent);
$('addCameraObsBtn').onclick = async () => {
  const btn = $('addCameraObsBtn');
  const notice = $('cameraObsNotice');
  const previous = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Adicionando…';
  notice.textContent = 'Conectando ao OBS e preparando a fonte da câmera…';

  try {
    const r = await window.estacaCast.addCameraToObs();
    if (r.ok) {
      notice.textContent = r.message || 'Câmera adicionada ao OBS.';
    } else {
      notice.textContent = r.error || 'Não foi possível adicionar a câmera ao OBS.';
      alert(r.error || 'Não foi possível adicionar a câmera ao OBS.');
    }
  } catch (error) {
    notice.textContent = 'Erro ao adicionar a câmera ao OBS.';
    alert(error?.message || String(error));
  } finally {
    btn.disabled = false;
    btn.textContent = previous;
  }
};
$('qrBtn').onclick = async () => {
  const url = currentState?.localUrl || $('localUrl').textContent;
  await showQr({
    title: 'Abrir transmissão local',
    hint: 'Escaneie em outro aparelho conectado à mesma rede.',
    value: url,
  });
};
$('cameraQrBtn').onclick = async () => {
  let state = currentState || await window.estacaCast.getState();

  if (!state.mediaOnline) {
    const start = await window.estacaCast.startLocal();
    if (!start.ok) {
      alert(`Não foi possível iniciar o MediaMTX para receber a câmera.\n\n${start.error || 'Erro desconhecido.'}`);
      return;
    }
    state = await window.estacaCast.getState();
    renderState(state);
  }

  const name = `Estaca Cast — ${state.localIp || 'PC'}`;
  const pairUrl = `estacacast://pair?ip=${encodeURIComponent(state.localIp)}&port=${encodeURIComponent(state.cameraSrtPort || 8890)}&streamid=${encodeURIComponent(state.cameraStreamId || 'publish:camera')}&name=${encodeURIComponent(name)}`;
  await showQr({
    title: 'Parear câmera Android',
    hint: 'O receptor da câmera já está ativo. No Estaca Cast Camera, toque em LER QR e aponte para este código. Não é necessário digitar IP.',
    value: pairUrl,
  });
};
$('qrClose').onclick = () => $('qrModal').classList.add('hidden');
$('qrModal').onclick = (e) => { if (e.target === $('qrModal')) $('qrModal').classList.add('hidden'); };

$('youtubeStudioBtn').onclick = () => window.estacaCast.openYoutubeStudio();
$('startYoutubeBtn').onclick = async () => {
  const key = $('youtubeKey').value.trim();
  const r = await window.estacaCast.startYoutube({ key, remember: $('rememberKey').checked, bitrate: '4000k' });
  if (!r.ok) alert(r.error);
  else $('youtubeNotice').textContent = 'Enviando ao YouTube. Volte ao YouTube Studio, espere a prévia e clique em “Transmitir ao vivo” se necessário.';
  refresh();
};
$('stopYoutubeBtn').onclick = async () => { await window.estacaCast.stopYoutube(); refresh(); };
$('openLogsBtn').onclick = () => window.estacaCast.openLogs();
$('checkUpdateBtn').onclick = async () => {
  $('updateNotice').textContent = 'Verificando atualizações…';
  const r = await window.estacaCast.checkUpdates();
  if (!r.ok) $('updateNotice').textContent = r.error;
};
$('importObsBtn').onclick = async () => { const r = await window.estacaCast.importObsBackup(); if (r.error) alert(r.error); };

window.estacaCast.onState(renderState);
window.estacaCast.onSetupProgress((data) => {
  $('setupProgress').style.width = `${Math.round((data.percent || 0) * 100)}%`;
  $('setupText').textContent = data.message || 'Preparando…';
});
window.estacaCast.onUpdateStatus((data) => { $('updateNotice').textContent = data.message || data.status; });

(async () => {
  const saved = await window.estacaCast.getSavedYoutubeKey();
  if (saved) { $('youtubeKey').value = saved; $('rememberKey').checked = true; }
  await refresh();
})();
