# Estaca Cast

Aplicativo Windows para distribuir a saída do OBS em baixa latência na rede local usando MediaMTX e, opcionalmente, encaminhar a mesma transmissão para o YouTube com FFmpeg.

## Objetivo

O Estaca Cast foi criado para simplificar um fluxo que normalmente exige vários programas, comandos e configurações manuais. A ideia é permitir que um operador consiga preparar o computador, configurar o OBS, distribuir o vídeo para TVs/notebooks/celulares na mesma rede e iniciar uma transmissão pública pelo YouTube usando uma interface única.

## Funcionalidades da versão 0.2.0

- Interface desktop nativa via Electron.
- Assistente de preparação do computador.
- Download e gerenciamento do MediaMTX.
- Detecção e uso do FFmpeg.
- Orientação passo a passo para configurar WHIP no OBS.
- Distribuição local por WebRTC através do MediaMTX.
- Detecção automática do IPv4 local.
- Link e QR Code para receptores na mesma rede.
- Encaminhamento opcional para YouTube via FFmpeg/RTMPS.
- Diagnóstico de MediaMTX, OBS e YouTube.
- Atualização automática pelo GitHub Releases.
- Instalador Windows via NSIS.

## Fluxo

```text
OBS
 ↓ WHIP
MediaMTX
 ├─ WebRTC → TVs / notebooks / celulares locais
 └─ RTSP → FFmpeg → YouTube
```

## OBS

No OBS, configure:

- Serviço: `WHIP`
- Servidor: `http://localhost:8889/conferencia/whip`
- Token/chave: vazio

Depois clique em **Iniciar transmissão** no OBS.

## Desenvolvimento

Requisitos:

- Node.js 22+
- npm

Instalar dependências:

```powershell
npm install
```

Executar em desenvolvimento:

```powershell
npm start
```

Gerar instalador Windows:

```powershell
npm run build:win
```

## Releases e atualização automática

O projeto usa GitHub Releases e `electron-updater`.

Ao enviar uma tag no formato:

```text
v0.2.0
```

o workflow `.github/workflows/release.yml` compila no Windows e publica automaticamente o instalador:

```text
EstacaCast-Setup-0.2.0.exe
```

O aplicativo consulta as Releases do repositório e pode baixar novas versões automaticamente.

## Dados locais

O Estaca Cast mantém ferramentas, configurações e logs em:

```text
%LOCALAPPDATA%\EstacaCast
```

Isso permite reaproveitar MediaMTX, FFmpeg e configurações entre atualizações do aplicativo.

## Próximos passos

Consulte [ROADMAP.md](ROADMAP.md).
