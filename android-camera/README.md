# Estaca Cast Camera

Aplicativo Android complementar ao Estaca Cast Desktop.

Objetivo: localizar automaticamente o Estaca Cast na rede local e publicar a câmera do celular por SRT no caminho `camera`, sem exigir configuração manual de IP, porta ou stream ID.

Fluxo previsto:

`Android -> SRT/UDP 8890 -> MediaMTX -> rtsp://localhost:8554/camera -> OBS`

A primeira versão é um MVP de validação e usa build debug assinado pelo Android SDK para facilitar testes internos.
