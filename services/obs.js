const fs = require('fs');
const path = require('path');
const { OBSWebSocket } = require('obs-websocket-js');

function readObsWebSocketConfig() {
  const candidates = [];
  if (process.env.APPDATA) {
    candidates.push(path.join(process.env.APPDATA, 'obs-studio', 'plugin_config', 'obs-websocket', 'config.json'));
  }

  for (const configPath of candidates) {
    try {
      if (!fs.existsSync(configPath)) continue;
      const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      return {
        configPath,
        port: Number(cfg.server_port || 4455),
        password: String(cfg.server_password || ''),
        authRequired: cfg.auth_required !== false,
        enabled: cfg.server_enabled !== false,
      };
    } catch (_) {}
  }

  return {
    configPath: null,
    port: 4455,
    password: '',
    authRequired: false,
    enabled: null,
  };
}

function explainConnectionError(error, config) {
  const code = Number(error?.code || 0);
  const message = String(error?.message || error || '').trim();

  if (code === 4009 || /auth/i.test(message)) {
    return 'O OBS recusou a autenticação do WebSocket. Abra OBS → Ferramentas → Configurações do servidor WebSocket, confirme a senha e reinicie o OBS.';
  }

  if (/ECONNREFUSED|connect|socket|closed/i.test(message)) {
    const port = config.port || 4455;
    return `Não consegui conectar ao OBS na porta ${port}. Abra o OBS e confirme em Ferramentas → Configurações do servidor WebSocket que o servidor está ativado.`;
  }

  return message || 'Não foi possível controlar o OBS pelo WebSocket.';
}

async function addCameraToObs({ rtspUrl = 'rtsp://localhost:8554/camera' } = {}) {
  const config = readObsWebSocketConfig();

  if (config.enabled === false) {
    return {
      ok: false,
      error: 'O servidor WebSocket do OBS está desativado. No OBS, abra Ferramentas → Configurações do servidor WebSocket e marque “Ativar servidor WebSocket”.',
    };
  }

  const obs = new OBSWebSocket();
  try {
    await obs.connect(
      `ws://127.0.0.1:${config.port || 4455}`,
      config.authRequired ? (config.password || undefined) : undefined,
      { rpcVersion: 1 },
    );

    const scene = await obs.call('GetCurrentProgramScene');
    const sceneName = scene.sceneName || scene.currentProgramSceneName;
    if (!sceneName) {
      return { ok: false, error: 'O OBS não informou uma cena atual. Selecione uma cena no OBS e tente novamente.' };
    }

    const inputName = 'Estaca Cast Camera';
    const inputSettings = {
      is_local_file: false,
      input: rtspUrl,
      input_format: 'rtsp',
      // O RTSP viaja apenas dentro do próprio computador. Não precisamos de
      // buffer de rede grande aqui; removê-lo reduz o atraso percebido.
      buffering_mb: 0,
      reconnect_delay_sec: 2,
      hw_decode: true,
      clear_on_media_end: true,
      restart_on_activate: true,
      close_when_inactive: false,
      seekable: false,
      // TCP no trecho MediaMTX → OBS evita artefatos por datagramas perdidos.
      ffmpeg_options: 'rtsp_transport=tcp',
    };

    let inputExists = false;
    try {
      await obs.call('GetInputSettings', { inputName });
      inputExists = true;
    } catch (_) {}

    if (inputExists) {
      await obs.call('SetInputSettings', {
        inputName,
        inputSettings,
        overlay: true,
      });

      try {
        await obs.call('GetSceneItemId', { sceneName, sourceName: inputName });
      } catch (_) {
        await obs.call('CreateSceneItem', {
          sceneName,
          sourceName: inputName,
          sceneItemEnabled: true,
        });
      }
    } else {
      await obs.call('CreateInput', {
        sceneName,
        inputName,
        inputKind: 'ffmpeg_source',
        inputSettings,
        sceneItemEnabled: true,
      });
    }

    const item = await obs.call('GetSceneItemId', { sceneName, sourceName: inputName });

    return {
      ok: true,
      message: `Câmera adicionada à cena “${sceneName}” como “${inputName}”.`,
      sceneName,
      inputName,
      sceneItemId: item.sceneItemId,
      rtspUrl,
    };
  } catch (error) {
    return { ok: false, error: explainConnectionError(error, config) };
  } finally {
    try { await obs.disconnect(); } catch (_) {}
  }
}

module.exports = {
  addCameraToObs,
  readObsWebSocketConfig,
};
