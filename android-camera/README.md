# Estaca Cast Camera

Aplicativo Android complementar ao Estaca Cast Desktop.

<p align="left">
  <a href="https://github.com/gderich/estaca-cast/releases/latest/download/EstacaCast-Camera.apk">
    <img alt="Baixar APK" src="https://img.shields.io/badge/BAIXAR%20APK-ANDROID-3ddc84?style=for-the-badge&logo=android&logoColor=111111">
  </a>
  <a href="https://github.com/gderich/estaca-cast/actions/workflows/android-ci.yml">
    <img alt="Build Android" src="https://github.com/gderich/estaca-cast/actions/workflows/android-ci.yml/badge.svg">
  </a>
</p>

O objetivo é localizar automaticamente o Estaca Cast na rede local e publicar a câmera do celular por SRT no caminho `camera`, sem exigir configuração manual de IP, porta ou Stream ID.

## Fluxo

```text
Android
  ↓ SRT / UDP 8890
MediaMTX
  ↓
rtsp://localhost:8554/camera
  ↓
OBS
```

## Como usar

1. Instale e abra o Estaca Cast no Windows.
2. Clique em **Preparar computador**.
3. Clique em **Configurar firewall** e autorize como administrador.
4. Clique em **Iniciar local** para iniciar o MediaMTX.
5. Instale o APK no Android e permita acesso à câmera.
6. Mantenha celular e computador na mesma rede local.
7. Abra o Estaca Cast Camera. Ele tenta encontrar o computador automaticamente.
8. Se não encontrar, no PC clique em **QR de pareamento**. No celular, abra a câmera ou Google Lens, leia o QR e toque no link `Estaca Cast`.
9. O aplicativo abrirá já com IP, porta SRT e Stream ID preenchidos internamente. Toque em **Conectar câmera**.
10. No OBS, adicione uma **Fonte de mídia**, desmarque **Arquivo local** e use `rtsp://localhost:8554/camera` como entrada.

## Rede e hotspot

A descoberta automática usa UDP `37020` e a câmera publica por SRT em UDP `8890`.

A versão 0.1.1 tenta broadcast global, broadcast informado pelo Wi-Fi e os endereços de broadcast de todas as interfaces de rede do Android. Isso melhora o funcionamento quando há múltiplas interfaces ou quando o celular está atuando como hotspot.

Ainda assim, hotspots e algumas redes Wi-Fi podem bloquear ou limitar broadcast/isolamento entre clientes. Nesses casos, o **QR de pareamento é o fallback recomendado**, porque não depende da descoberta UDP: o QR contém o IP atual do PC, a porta SRT e o Stream ID e abre o Estaca Cast Camera via `estacacast://pair`.

Se o QR parear mas a câmera não conectar, confirme se o botão **Configurar firewall** foi executado no PC e se a porta UDP `8890` está liberada.

## Build

O projeto usa:

- Java 17
- Android Gradle Plugin 9.3.2
- Gradle 9.7.1
- RootEncoder 2.8.1

O CI está em `.github/workflows/android-ci.yml`. Builds aprovados geram o artefato `EstacaCast-Camera-debug`; releases públicas anexam o arquivo fixo `EstacaCast-Camera.apk`.

> A versão atual é um MVP de validação e usa assinatura debug do Android SDK para facilitar testes internos.
