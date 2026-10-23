# Roadmap

## 0.2.x — concluído

- App Windows em Electron
- Instalador NSIS `.exe`
- MediaMTX local
- FFmpeg + YouTube
- QR Code
- Diagnóstico
- Atualização automática via GitHub Releases

## 0.3.x — concluído até aqui

- Aplicativo complementar Estaca Cast Camera para Android
- Descoberta automática do computador na rede local via UDP + tentativa unicast
- Fallback de pareamento por QR
- Leitor de QR integrado ao próprio aplicativo Android
- Envio da câmera do Android por SRT para o MediaMTX
- Standby AMOLED automático durante a transmissão
- Fonte `rtsp://localhost:8554/camera` pronta para uso no OBS
- Integração com OBS WebSocket para criar/reutilizar automaticamente a fonte `Estaca Cast Camera` na cena atual
- Regras de firewall para WebRTC, SRT e descoberta da câmera
- Status da câmera no aplicativo desktop
- Botão para baixar o APK diretamente no Estaca Cast
- Publicação automática de `EstacaCast-Camera.apk` junto da Release do Windows
- Assinatura Android persistente para atualização por cima das versões futuras
- CI Android validando a compilação do APK
- Identidade visual/ícones para Windows e Android

## 0.3.x — próximos passos

- Importação de perfil e coleção de cenas do OBS
- Backup antes de aplicar pacote OBS
- Seletor de perfil de qualidade do YouTube
- Melhor diagnóstico de rede e bitrate
- Atualização independente de MediaMTX e FFmpeg
- Melhorias na interface e nos controles da câmera Android
- Opções de qualidade/resolução/bitrate da câmera móvel
- Tratamento mais amigável quando a rede bloqueia comunicação entre dispositivos
- Ajuste automático de tamanho/posição da câmera criada no OBS

## 1.0

- Instalador e onboarding final
- Pacote de conferência pronto para distribuição a outros operadores
- Fluxo completo de primeira configuração sem passos técnicos manuais
