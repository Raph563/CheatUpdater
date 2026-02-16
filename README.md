# CheatUpdater

Repo: `https://github.com/Raph563/CheatUpdater/`

CheatUpdater contient:

1. Une application Android qui détecte/télécharge des APK de release, puis gère `installer / mettre à jour / désinstaller -> réinstaller`.
2. Un backend Docker qui orchestre `rvx-builder` pour patcher automatiquement ou manuellement des apps, préparer une diffusion, puis diffuser.

## Architecture

- `app/`:
  - App Android Kotlin/Compose.
  - Sources de mise à jour préremplies (GitHub + backend local).
  - Catégorie Debug avec:
    - `Tester connexion dépôt`
    - `Check application fonctionnel` (test connexion + récupération release/APK + diagnostic).
- `backend/`:
  - API FastAPI + interface web d’admin.
  - Scheduler de détection des nouveaux patchs RVX.
  - Pipeline: patch -> stage `ready` -> `Diffuser` ou `Annuler`.
- `rvx-builder/`:
  - Code RVX Builder utilisé par Docker.
- `docker-compose.yml`:
  - Lance `rvx-builder` + `cheatupdater-backend`.

## Démarrage rapide (backend)

```bash
docker compose up -d --build
```

UI:

- Backend admin: `http://localhost:8088`
- RVX Builder: `http://localhost:8000`

Volumes persistants:

- `rvx_data/`: artefacts RVX (jars/apk/etc.)
- `backend_data/`: état backend + APK staged/published

## Workflow backend

1. Ouvrir `http://localhost:8088`.
2. Cliquer `Rafraichir catalogue RVX`.
3. Sélectionner les apps à patcher (`Auto` si patch automatique souhaité).
4. `Sauver configuration`.
5. Lancer `Forcer patch maintenant` (ou attendre auto-détection).
6. Quand un stage est `ready`, cliquer:
   - `Diffuser` pour publier la release mobile courante
   - `Annuler` pour garder les APK sans diffusion.

Les APK sont conservés en stockage local même après annulation/diffusion.

## Endpoints mobile

- `GET /mobile/current`: release actuellement diffusée.
- `GET /mobile/apk/{releaseId}/{fileName}`: téléchargement APK diffusé.
- `GET /mobile/sources`: sources prédéfinies.
- `GET /mobile/debug/repository/{sourceId}`: test d’accessibilité dépôt/source.

## Android: sources prédéfinies

Sources incluses dans l’app:

1. `GitHub Releases (Raph563/CheatUpdater)`
2. `Backend Docker (local)`
3. `GitHub Debug (inotia00/rvx-builder)`

Note:

- Pour un émulateur Android: backend local via `http://10.0.2.2:8088/`.
- Pour un téléphone physique: remplacer l’URL backend par l’IP LAN du PC dans `app/src/main/java/com/raph563/cheatupdater/data/UpdateSources.kt`.

## Build Android

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

APK générés:

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

Pixel 6: utiliser `app-arm64-v8a-*.apk`.

## Broadcast Android (alerte forcée)

```bash
adb shell am broadcast \
  -a com.raph563.cheatupdater.ACTION_FORCE_UPDATE \
  --es reason "Mise a jour obligatoire"
```

