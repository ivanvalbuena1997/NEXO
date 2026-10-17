# NEXO

App Android compañera de V.I.T.A. Lee de Health Connect los pasos y el sueño y, a partir de la 0.2, los guarda en Firestore con el formato que espera V.I.T.A.

- Kotlin, sin Android Studio: el APK lo compila GitHub Actions (`.github/workflows/apk.yml`) y se publica en **Releases**.
- La clave de firma no está en el repositorio: se lee de los Secrets `NEXO_KEYSTORE_B64` y `NEXO_KEYSTORE_PASSWORD`. Sin ellos, Actions solo comprueba que compila.
- Tipografías Orbitron y Rajdhani (licencia SIL OFL 1.1, en `licencias/`).

## Versiones

| Versión | Contenido |
|---|---|
| 0.1 | Solo lectura de Health Connect: pasos y sueño (con fases) de los últimos 7 días, en pantalla. |
| 0.2 | Inicio de sesión con Google y escritura manual en Firestore («Sincronizar ahora»). |
| 0.3 | Sincronización diaria a la hora elegida, en segundo plano. |
| 0.4 | Pulido y ayuda para los ajustes de batería. |
| 1.0 | Versión estable tras uso real. |
