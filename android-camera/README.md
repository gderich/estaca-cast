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
2. Clique em **Preparar computador** e **Configurar firewall**.
3. Instale o APK no Android e permita acesso à câmera.
4. Mantenha celular e computador na mesma rede local.
5. Abra o Estaca Cast Camera. Ele tenta encontrar o computador automaticamente.
6. Se não encontrar, no PC clique em **QR de pareamento**.
7. No celular, toque em **LER QR** e aponte para o QR do PC.
8. Toque em **CONECTAR CÂMERA**.
9. No Estaca Cast para Windows, use **Adicionar câmera ao OBS** para criar/reutilizar automaticamente a fonte `Estaca Cast Camera` na cena atual.

> Não é necessário abrir Google Lens. O leitor QR funciona dentro do próprio aplicativo.

## Transmissão em segundo plano

A partir da versão **0.3.8**, a transmissão SRT é executada por um **Foreground Service de câmera**. Depois que a conexão começa, a câmera deixa de depender da tela principal do aplicativo.

Isso permite:

- apertar **Home** sem interromper o vídeo;
- bloquear/apagar a tela e continuar transmitindo;
- voltar ao aplicativo sem reiniciar a transmissão;
- manter CPU e Wi-Fi ativos durante a transmissão com `WakeLock` e `WiFiLock`;
- parar a câmera diretamente pela notificação persistente do Android;
- reconectar automaticamente o SRT em quedas breves de rede.

Enquanto a transmissão estiver ativa, o leitor QR e a descoberta ficam bloqueados para evitar disputa pela câmera.

## Desligamento automático da tela

Por segurança, o Android não permite que um aplicativo comum apague fisicamente a tela sozinho. O Estaca Cast oferece uma permissão opcional e restrita de **Administrador do dispositivo**, usando apenas a política `force-lock`.

No aplicativo:

**MENU → Ativar desligamento automático da tela**

Depois de autorizar uma vez, quando a câmera conectar:

```text
Conectou ao Estaca Cast
        ↓
aguarda 5 segundos
        ↓
bloqueia/apaga a tela
        ↓
Foreground Service continua câmera + SRT
```

Se essa opção não estiver ativada, o aplicativo mantém o fallback de standby AMOLED: tela preta e brilho mínimo após 5 segundos. Também é possível apagar a tela manualmente com o botão Power; a transmissão continua.

## Atualizações dentro do aplicativo

A partir da versão **0.3.8**, o Estaca Cast Camera verifica automaticamente a Release mais recente no GitHub ao abrir.

Se houver nova versão:

- o app mostra **Atualização disponível**;
- o botão passa a indicar `MENU • UPDATE`;
- em **MENU → Verificar atualização**, é possível checar manualmente a qualquer momento;
- ao aceitar, o APK é baixado pelo próprio Android;
- quando o download termina, o instalador do Android é aberto automaticamente.

O Android não permite instalação silenciosa de APK para um aplicativo comum. Portanto, a confirmação final de instalação continua sendo feita pelo usuário. Na primeira atualização pelo próprio app, o Android também pode pedir autorização para **Instalar apps desconhecidos** para o Estaca Cast Camera.

Como as Releases usam a mesma chave de assinatura persistente, a instalação nova sobrescreve a anterior e preserva os dados do aplicativo.

## Assinatura

As primeiras versões públicas usaram assinatura debug variável. As Releases atuais usam uma **chave de assinatura persistente**, armazenada somente em GitHub Secrets e aplicada ao `assembleRelease`.

A chave privada nunca deve ser adicionada ao repositório público. Mantenha backup seguro do `.jks` e das credenciais.

## Rede e hotspot

A descoberta automática usa UDP `37020` e a câmera publica por SRT em UDP `8890`.

A descoberta tenta broadcast e também varredura unicast da sub-rede local. Isso melhora o funcionamento quando hotspots ou pontos de acesso não encaminham broadcast entre clientes.

Algumas redes ainda podem aplicar isolamento entre dispositivos. Nesses casos, use o **QR de pareamento integrado**, que contém IP atual do PC, porta SRT e Stream ID.

Se o QR parear mas a câmera não conectar, confirme se **Configurar firewall** foi executado no PC e se UDP `8890` está liberada.

## Build

O projeto usa:

- Java 17
- Android Gradle Plugin 9.3.2
- Gradle 9.7.1
- RootEncoder 2.8.1
- ZXing Android Embedded 4.3.0

O CI de validação está em `.github/workflows/android-ci.yml`. As Releases públicas são geradas por `.github/workflows/release.yml` com assinatura persistente e arquivo fixo `EstacaCast-Camera.apk`.
