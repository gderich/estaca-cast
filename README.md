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

> Os botões de download sempre apontam para os arquivos da versão pública mais recente. As Releases Android usam assinatura persistente, permitindo atualização por cima das versões anteriores assinadas com a mesma chave.

## O que ele faz

O Estaca Cast foi criado para simplificar um fluxo que normalmente exige vários programas, comandos e configurações manuais. A ideia é permitir que um operador prepare o computador, configure o OBS, distribua o vídeo para TVs, notebooks e celulares na mesma rede, use um celular Android como câmera sem fio e, se quiser, inicie uma transmissão pública pelo YouTube usando uma única interface.

## Funcionalidades atuais

- Interface desktop via Electron e instalador NSIS `.exe`.
- Assistente de preparação do computador.
- Download e gerenciamento do MediaMTX e FFmpeg.
- OBS principal por WHIP.
- Distribuição local por WebRTC.
- Detecção automática do IPv4 local.
- QR Code para receptores locais.
- Aplicativo Android complementar para câmera móvel.
- Descoberta automática do Estaca Cast na rede local via UDP e fallback por QR.
- Leitor QR integrado no Estaca Cast Camera.
- Envio da câmera Android por SRT.
- **Foreground Service** no Android para continuar transmitindo com Home ou tela apagada.
- WakeLock + WiFiLock durante a transmissão móvel.
- Desligamento automático opcional da tela 5 s após a conexão.
- Verificação e download de atualização dentro do próprio app Android.
- Integração com OBS WebSocket para criar/reutilizar automaticamente a fonte `Estaca Cast Camera` na cena atual.
- Encaminhamento opcional para YouTube via FFmpeg/RTMPS.
- Diagnóstico de MediaMTX, OBS, câmera e YouTube.
- Configuração automática das portas no Firewall do Windows.
- Atualização automática do aplicativo Windows pelo GitHub Releases.

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

1. Baixe e instale o **Estaca Cast para Windows**.
2. Clique em **Preparar computador**.
3. Clique em **Configurar firewall** e confirme o UAC.
4. Configure o OBS para enviar por WHIP para `http://localhost:8889/conferencia/whip`.
5. Clique em **Iniciar local**.
6. Para usar um celular como câmera, instale o **Estaca Cast Camera** no Android.
7. O Android tenta localizar o PC automaticamente; se necessário, use **QR de pareamento** no PC + **LER QR** no celular.
8. Toque em **CONECTAR CÂMERA**.
9. No PC, clique em **Adicionar câmera ao OBS**.

## Câmera Android

O Estaca Cast Camera funciona sem digitar IP, porta ou Stream ID manualmente.

Com o computador e o celular na mesma rede:

1. No Estaca Cast para Windows, clique em **Configurar firewall**.
2. Instale o APK Android e conceda permissão para usar a câmera.
3. Abra o Estaca Cast Camera. Ele tenta localizar o computador automaticamente.
4. Se a descoberta falhar, no PC clique em **QR de pareamento** e no Android use **LER QR**.
5. Toque em **CONECTAR CÂMERA**.
6. Com o OBS aberto, clique em **Adicionar câmera ao OBS** no Estaca Cast.

A integração com o OBS usa o `obs-websocket` incluído no OBS 28 ou superior. O Estaca Cast lê localmente a porta e a senha da configuração do OBS e conecta apenas em `127.0.0.1`. Se o servidor estiver desativado, abra **OBS → Ferramentas → Configurações do servidor WebSocket** e habilite o servidor. O padrão do OBS WebSocket 5.x é a porta `4455`.

Se preferir configurar manualmente, ainda é possível adicionar uma **Fonte de mídia**, desmarcar **Arquivo local** e usar `rtsp://localhost:8554/camera`.

### Segundo plano e tela apagada

A partir da versão **0.3.8**, a transmissão móvel roda em um **Foreground Service de câmera**. Depois de conectar, é possível:

- apertar **Home**;
- apagar/bloquear a tela;
- voltar ao app depois;
- manter câmera + SRT ativos enquanto a notificação do Estaca Cast Camera estiver presente.

A notificação persistente também possui o botão **PARAR**.

Para bloquear a tela automaticamente após 5 segundos:

**Android → MENU → Ativar desligamento automático da tela**

O Android solicita uma permissão opcional de Administrador do dispositivo usando somente a política de bloqueio (`force-lock`). Sem essa autorização, o app usa o fallback AMOLED (preto + brilho mínimo) e a tela também pode ser apagada manualmente pelo botão Power sem interromper a transmissão.

### Atualização do Android

O Estaca Cast Camera verifica automaticamente a Release mais recente ao abrir.

Também existe:

**MENU → Verificar atualização**

Quando há nova versão, o APK é baixado pelo próprio Android e o instalador é aberto automaticamente. Por regra do Android, a confirmação final de instalação não pode ser silenciosa e continua dependendo do usuário. Na primeira vez, o sistema pode pedir autorização para o Estaca Cast Camera **instalar apps desconhecidos**.

Como a assinatura é persistente, versões novas atualizam por cima e preservam os dados do aplicativo.

### Portas usadas

| Função | Protocolo | Porta |
| --- | --- | ---: |
| Player WebRTC / sinalização | TCP | 8888, 8889 |
| WebRTC / ICE | UDP | 8189 |
| Câmera Android / SRT | UDP | 8890 |
| Descoberta automática da câmera | UDP | 37020 |
| OBS WebSocket local | TCP | 4455 por padrão |

O botão **Configurar firewall** cria as regras necessárias para os serviços do Estaca Cast. A porta do OBS WebSocket é usada somente em `localhost`.

> A descoberta automática depende de o celular e o computador conseguirem se comunicar dentro da rede local. Redes com isolamento entre clientes/APs podem impedir a descoberta ou o vídeo. Nesses casos, use o QR de pareamento.

## OBS

Para a transmissão principal:

- Abra `Configurações → Transmissão`.
- Serviço: `WHIP`.
- Servidor: `http://localhost:8889/conferencia/whip`.
- Token/chave: vazio.
- Clique em `Aplicar → OK`.
- Na tela principal do OBS, clique em **Iniciar transmissão**.

Para a câmera Android, o Estaca Cast pode controlar o OBS local pelo WebSocket e criar automaticamente a Fonte de mídia apontando para `rtsp://localhost:8554/camera`.

## YouTube

O aplicativo explica como obter a chave no YouTube Studio e envia a transmissão ao YouTube sem interromper o modo local.

## Desenvolvimento

### Desktop

Requisitos:

- Node.js 22+
- npm

```powershell
npm install
npm start
```

Gerar instalador Windows:

```powershell
npm run build:win
```

### Android

A aplicação Android fica em `android-camera/` e usa Java 17, Android Gradle Plugin 9.3.2 e Gradle 9.7.1.

## Releases

O workflow `.github/workflows/release.yml` publica na mesma Release:

```text
EstacaCast-Setup.exe
EstacaCast-Camera.apk
```

## Dados locais

```text
%LOCALAPPDATA%\EstacaCast
```

## Próximos passos

Consulte [ROADMAP.md](ROADMAP.md).
