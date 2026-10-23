# Estaca Cast

<p align="left">
  <a href="https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Setup.exe">
    <img alt="Baixar Estaca Cast" src="https://img.shields.io/badge/⬇%20Baixar%20Estaca%20Cast-Windows-1769ff?style=for-the-badge&logo=windows11&logoColor=white">
  </a>
  <a href="https://github.com/gderich/estaca-cast/releases/latest">
    <img alt="Última versão" src="https://img.shields.io/github/v/release/gderich/estaca-cast?style=for-the-badge&label=versão">
  </a>
</p>

Aplicativo Windows para distribuir a saída do OBS em baixa latência na rede local usando MediaMTX e, opcionalmente, encaminhar a mesma transmissão para o YouTube com FFmpeg.

## Download

**[⬇ Baixar o instalador mais recente para Windows](https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Setup.exe)**

Depois de baixar, execute `EstacaCast-Setup.exe`. O instalador cria atalhos no Menu Iniciar e, se desejado, na Área de Trabalho.

> O botão de download acima sempre aponta para o instalador da Release mais recente.

## Objetivo

O Estaca Cast foi criado para simplificar um fluxo que normalmente exige vários programas, comandos e configurações manuais. A ideia é permitir que um operador consiga preparar o computador, configurar o OBS, distribuir o vídeo para TVs/notebooks/celulares na mesma rede e iniciar uma transmissão pública pelo YouTube usando uma interface única.

## Funcionalidades da versão 0.2.0

- Interface desktop via Electron, com textos UTF-8 corretos.
- Instalador Windows `.exe` via NSIS.
- Assistente de preparação do computador.
- Download e gerenciamento do MediaMTX.
- Detecção e instalação do FFmpeg.
- Orientação passo a passo para configurar WHIP no OBS.
- Distribuição local por WebRTC através do MediaMTX.
- Detecção automática do IPv4 local.
- Link e QR Code para receptores na mesma rede.
- Encaminhamento opcional para YouTube via FFmpeg/RTMPS.
- Diagnóstico de MediaMTX, OBS e YouTube.
- Atualização automática pelo GitHub Releases.
- Estrutura reservada para importar perfis e coleções de cenas do OBS.

## Fluxo

```text
OBS
 ↓ WHIP
MediaMTX
 ├─ WebRTC → TVs / notebooks / celulares locais
 └─ RTSP → FFmpeg → YouTube
```

## OBS

O próprio aplicativo mostra este procedimento:

- Abra `Configurações → Transmissão`.
- Serviço: `WHIP`.
- Servidor: `http://localhost:8889/conferencia/whip`.
- Token/chave: vazio.
- Clique em `Aplicar → OK`.
- Na tela principal do OBS, clique em **Iniciar transmissão**.

## YouTube

O aplicativo também explica como obter a chave de transmissão no YouTube Studio e envia a transmissão ao YouTube sem interromper o modo local.

O status **Enviando** no Estaca Cast significa que o FFmpeg está enviando dados. A confirmação final e a prévia aparecem no próprio YouTube Studio.

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

o workflow `.github/workflows/release.yml` compila no Windows e publica automaticamente:

```text
EstacaCast-Setup.exe
```

Como o nome do asset é fixo, o botão de download do topo continua funcionando em todas as versões.

O aplicativo consulta as Releases do repositório e pode baixar novas versões automaticamente.

## Dados locais

O Estaca Cast mantém ferramentas, configurações e logs em:

```text
%LOCALAPPDATA%\EstacaCast
```

Isso permite reaproveitar MediaMTX, FFmpeg e configurações entre atualizações do aplicativo.

## Próximos passos

Consulte [ROADMAP.md](ROADMAP.md).
