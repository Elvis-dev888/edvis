# Offline Vosk Spanish Acoustic Model (`vosk-es`)

Para activar el Wake Word 100% offline (`"edvis"`, `"oye edvis"`), descarga el modelo ligero en español de Vosk (`vosk-model-small-es-0.42`) y extrae su contenido directamente en esta carpeta (`app/src/main/assets/models/vosk-es/`):

1. Descarga: https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip
2. Descomprime el archivo y coloca las subcarpetas (`am/`, `conf/`, `graph/`, `ivector/`) dentro de `app/src/main/assets/models/vosk-es/`.

> Nota: Si el modelo aún no ha sido copiado durante pruebas de desarrollo, `VoskWakeEngine.kt` activa automáticamente el modo de escucha continua (`ALWAYS_STREAM`) para que puedas probar el canal duplex con el servidor de inmediato.
