# CheatUpdater V1

Application Android (Kotlin + Compose) qui:

1. Lit la derniere release GitHub d'un repo.
2. Telecharge les assets `.apk`.
3. Propose `Installer` ou `Mettre a jour` selon l'etat local.
4. Si update/install echoue, lance la desinstallation de l'app concernee puis retente la reinstallation.
5. Notifie l'utilisateur quand une update est detectee.

## Declencheurs de verification

- Au demarrage de l'application.
- Au clic sur `Check mises a jour`.
- Au boot du telephone (receiver `BOOT_COMPLETED`).
- Tous les jours vers midi (WorkManager, periodic 24h).
- A la reception d'un broadcast force.

## Broadcast force de mise a jour

```bash
adb shell am broadcast \
  -a com.raph563.cheatupdater.ACTION_FORCE_UPDATE \
  --es reason "Mise a jour obligatoire pour garder le systeme stable"
```

## Permissions/parametres a activer sur le telephone

- Notifications
- Ignorer optimisation batterie
- Installation d'apps inconnues pour CheatUpdater
- Accessibilite (optionnel pour V1, service present)

## Build APK multi-ABI

Le projet genere:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`
- `universal`

Commandes:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Sortie attendue:

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

## Configuration GitHub

Dans l'app:

1. Renseigner `GitHub Owner` et `GitHub Repo`.
2. Ajouter un token (optionnel pour repo public, recommande pour repo prive/rate-limit).
3. `Sauver config`.
4. `Check mises a jour`.
