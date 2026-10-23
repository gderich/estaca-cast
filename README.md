# Estaca Cast

Aplicativo Windows para distribuir a saída do OBS em baixa latência na rede local usando MediaMTX, receber uma câmera Android por SRT e, opcionalmente, encaminhar a mesma transmissão para o YouTube com FFmpeg.

<p align="left">
  <a href="https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Setup.exe">
    <img alt="Baixar Estaca Cast para Windows" src="https://img.shields.io/badge/BAIXAR%20ESTACA%20CAST-WINDOWS-1769ff?style=for-the-badge&logo=windows11&logoColor=white">
  </a>
  <a href="https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Camera.apk">
    <img alt="Baixar Estaca Cast Camera para Android" src="https://img.shields.io/badge/BAIXAR%20C%C3%82MERA-ANDROID-3ddc84?style=for-the-badge&logo=android&logoColor=111111">
  </a>
  <a href="https://github.com/gderich/estaca-cast/releases/latest">
    <img alt="Release mais recente" src="https://img.shields.io/github/v/release/gderich/estaca-cast?display_name=tag&style=for-the-badge&color=2ea043">
  </a>
</p>

<p align="left">
  <a href="https://github.com/gderich/estaca-cast/actions/workflows/android-ci.yml">
    <img alt="Build Android" src="https://github.com/gderich/estaca-cast/actions/workflows/android-ci.yml/badge.svg">
  </a>
</p>

> Os botões de download sempre apontam para os arquivos da versão pública mais recente. O APK Android atual é uma versão de teste assinada com a chave de debug do Android SDK.

## O que ele faz

O Estaca Cast foi criado para simplificar um fluxo que normalmente exige vários programas, comandos e configurações manuais. A ideia é permitir que um operador consiga preparar o computador, configurar o OBS, distribuir o vídeo para TVs, notebooks e celulares na mesma rede, usar um celular Android como câmera sem fio e, se quiser, iniciar uma transmissão pública pelo YouTube usando uma única interface.

## Funcionalidades da versão 0.3.0

- Interface desktop via Electron, com textos UTF-8 corretos.
- Instalador Windows `.exe` via NSIS.
- Assistente de preparação do computador.
- Download e gerenciamento do MediaMTX.
- Detecção e instalação do FFmpeg.
- Orientação passo a passo para configurar WHIP no OBS.
- Distribuição local por WebRTC através do MediaMTX.
- Detecção automática do IPv4 local.
- Link e QR Code para receptores na mesma rede.
- Aplicativo Android complementar para câmera móvel.
- Descoberta automática do Estaca Cast na rede local via UDP.
- Envio da câmera Android por SRT para o MediaMTX.
- Botão no aplicativo desktop para baixar o APK mais recente.
- Encaminhamento opcional para YouTube via FFmpeg/RTMPS.
- Diagnóstico de MediaMTX, OBS, câmera e YouTube.
- Configuração automática das portas necessárias no Firewall do Windows.
- Atualização automática do aplicativo Windows pelo GitHub Releases.
- Estrutura reservada para importar perfis e coleções de cenas do OBS.

## Fluxo

```text
Android
  ↓ SRT / UDP 8890
MediaMTX (camera)
  ↓ RTSP
OBS
  ↓ WHIP
MediaMTX (conferencia)
  ├─ WebRTC → TVs / notebooks / celulares locais
  └─ RTSP → FFmpeg → YouTube
```

## Início rápido

1. Baixe e instale o **Estaca Cast para Windows** pelo botão no topo desta página.
2. Abra o Estaca Cast e clique em **Preparar computador**.
3. Clique em **Configurar firewall** e confirme a janela de administrador do Windows.
4. Configure o OBS para enviar por WHIP para `http://localhost:8889/conferencia/whip`.
5. Clique em **Iniciar local** no Estaca Cast.
6. Abra o link ou QR Code em TVs, notebooks e celulares conectados à mesma rede.
7. Para usar um celular como câmera, baixe o **Estaca Cast Camera**, abra o APK no Android e permita o acesso à câmera.

## Câmera Android

O Estaca Cast Camera foi feito para funcionar sem digitar IP, porta ou Stream ID manualmente.

Com o computador e o celular na mesma rede:

1. No Estaca Cast para Windows, clique em **Configurar firewall**.
2. Clique em **Iniciar local** para garantir que o MediaMTX esteja ativo.
3. No próprio Estaca Cast, use o botão **Baixar APK Android**, ou baixe pelo botão no topo deste README.
4. Instale o APK no Android e conceda permissão para usar a câmera.
5. Abra o Estaca Cast Camera. Ele procura automaticamente o computador na rede.
6. Conecte a câmera.
7. No OBS, adicione uma **Fonte de mídia**, desmarque **Arquivo local** e use `rtsp://localhost:8554/camera` como entrada.

### Portas usadas

| Função | Protocolo | Porta |
| --- | --- | ---: |
| Player WebRTC / sinalização | TCP | 8888, 8889 |
| WebRTC / ICE | UDP | 8189 |
| Câmera Android / SRT | UDP | 8890 |
| Descoberta automática da câmera | UDP | 37020 |

O botão **Configurar firewall** cria as regras necessárias no Firewall do Windows.

> A descoberta automática depende de o celular e o computador conseguirem se comunicar dentro da rede local. Redes com isolamento entre clientes/APs podem impedir a descoberta ou o vídeo mesmo quando ambos estão conectados ao mesmo Wi-Fi.

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

### Desktop

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

### Android

A aplicação Android fica em `android-camera/` e usa Java 17, Android Gradle Plugin 9.3.2 e Gradle 9.7.1. O build de validação é executado automaticamente por `.github/workflows/android-ci.yml`.

## Releases e atualização automática

O projeto usa GitHub Releases e `electron-updater`.

Ao publicar uma versão, o workflow `.github/workflows/release.yml` gera e anexa à mesma Release:

```text
EstacaCast-Setup.exe
EstacaCast-Camera.apk
```

Como os nomes dos assets são fixos, os botões no topo do README e o botão de download dentro do aplicativo continuam apontando sempre para a versão pública mais recente.

O aplicativo Windows consulta as Releases do repositório e pode baixar novas versões automaticamente.

## Dados locais

O Estaca Cast mantém ferramentas, configurações e logs em:

```text
%LOCALAPPDATA%\EstacaCast
```

Isso permite reaproveitar MediaMTX, FFmpeg e configurações entre atualizações do aplicativo.

## Próximos passos

Consulte [ROADMAP.md](ROADMAP.md).
