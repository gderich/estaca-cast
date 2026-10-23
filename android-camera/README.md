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
4. Instale o APK no Android e permita acesso à câmera.
5. Mantenha celular e computador na mesma rede local.
6. Abra o Estaca Cast Camera. Ele tenta encontrar o computador automaticamente.
7. Se não encontrar, no PC clique em **QR de pareamento**. O Estaca Cast inicia o MediaMTX automaticamente antes de exibir o QR.
8. No celular, abra a câmera ou Google Lens, leia o QR e toque no link `Estaca Cast`.
9. O aplicativo abrirá já com IP, porta SRT e Stream ID preenchidos internamente. Toque em **Conectar câmera**.
10. No OBS, adicione uma **Fonte de mídia**, desmarque **Arquivo local** e use `rtsp://localhost:8554/camera` como entrada.

> Não é mais necessário lembrar de iniciar o MediaMTX manualmente ao usar o QR de pareamento. A descoberta automática também tenta iniciar o receptor no PC quando encontra o Estaca Cast.

## Standby AMOLED

Depois que a conexão SRT é confirmada, o aplicativo espera **5 segundos** e entra automaticamente em standby:

- a interface fica completamente preta;
- o brilho da tela é reduzido ao mínimo;
- barras de navegação e status são ocultadas;
- a câmera e a transmissão continuam funcionando;
- em telas AMOLED, os pixels pretos ficam efetivamente apagados, reduzindo bastante o consumo da tela.

Toque uma vez na tela preta para restaurar a interface. Se a câmera continuar conectada, após mais 5 segundos ela volta ao standby automaticamente.

O aplicativo mantém a tela tecnicamente ativa durante o standby para evitar que o Android suspenda a câmera ou a conexão SRT.

## Assinatura e atualizações

As primeiras versões públicas (até 0.3.3) foram compiladas com a assinatura debug criada pelo runner do GitHub Actions. Como essa chave muda entre runners, o Android não permite instalar uma versão nova por cima da anterior.

O workflow de Release agora está preparado para usar uma **chave de assinatura persistente** armazenada somente em GitHub Secrets. As releases Android passam a usar `assembleRelease` e exigem os segredos:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

A primeira versão publicada com essa chave exigirá **uma última desinstalação** de qualquer APK antigo assinado em modo debug. A partir daí, todas as versões futuras assinadas com a mesma chave poderão ser instaladas como atualização normal, preservando o aplicativo e suas permissões/dados.

A chave privada de assinatura nunca deve ser adicionada ao repositório público. Mantenha um backup seguro do arquivo `.jks` e das credenciais.

## Aviso ao baixar ou instalar o APK

O Estaca Cast Camera é distribuído diretamente pelo GitHub e ainda não está publicado na Google Play. Por isso, Chrome, Android ou Play Protect podem mostrar mensagens como **“este tipo de arquivo pode ser prejudicial”** ou pedir confirmação antes de concluir o download/instalação.

Isso é esperado para aplicativos instalados fora da Play Store. Antes de instalar, confirme que o arquivo veio da Release oficial deste repositório. Cada Release publicada pelo GitHub também informa o SHA-256 do APK para verificação de integridade.

## Rede e hotspot

A descoberta automática usa UDP `37020` e a câmera publica por SRT em UDP `8890`.

A versão 0.3.3 tenta broadcast global, broadcast informado pelo Wi-Fi e os endereços de broadcast de todas as interfaces de rede do Android. Isso melhora o funcionamento quando há múltiplas interfaces ou quando o celular está atuando como hotspot.

Ainda assim, hotspots e algumas redes Wi-Fi podem bloquear ou limitar broadcast/isolamento entre clientes. Nesses casos, o **QR de pareamento é o fallback recomendado**, porque não depende da descoberta UDP: o QR contém o IP atual do PC, a porta SRT e o Stream ID e abre o Estaca Cast Camera via `estacacast://pair`.

Se o QR parear mas a câmera não conectar, confirme se o botão **Configurar firewall** foi executado no PC e se a porta UDP `8890` está liberada.

## Build

O projeto usa:

- Java 17
- Android Gradle Plugin 9.3.2
- Gradle 9.7.1
- RootEncoder 2.8.1

O CI de validação está em `.github/workflows/android-ci.yml` e pode continuar gerando builds debug de teste. As releases públicas são geradas por `.github/workflows/release.yml` com assinatura persistente e arquivo fixo `EstacaCast-Camera.apk`.
