# CamerasController

Своя сеть камер из собственных устройств: компьютеры (Windows, Linux) и Android-телефоны становятся камерами, а смотреть их можно с Windows-клиента или Android-клиента — с голографическим интерфейсом, записью, обратной связью по микрофону и настройкой разрешения для каждой камеры.

**Без чужого сервера.** Видео идёт напрямую между вашими устройствами по WebRTC. Для «знакомства» устройств используется ваш собственный бесплатный Firebase-проект, для сложных сетей — ваш собственный TURN-сервер. Ни разработчик, ни другие пользователи не видят ваши камеры и даже не знают, что они существуют.

> 🇬🇧 *Your own camera network made of your own devices. Each user brings their own free Firebase project (signaling) and TURN relay; video flows peer-to-peer over WebRTC. Windows/Linux/Android agents, Windows/Android viewers. Setup guide is in Russian — [SETUP.md](SETUP.md).*

## Приложения

| Приложение | Платформа | Что делает |
|---|---|---|
| **CamerasController** (клиент) | Windows, Android | Смотрит камеры сети, пишет видео на диск, создаёт сеть и приглашения |
| **CamerasController Agent** | Windows, Linux, Android | Превращает устройство в камеру сети. Камера и микрофон включаются только во время просмотра |

Готовые сборки — в [Releases](../../releases/latest).

## Как это устроено

```
  Агент (камера)                      Клиент (просмотр)
  ─────────────                      ─────────────────
  getUserMedia ──► WebRTC ◄═══ P2P видео/звук ═══► WebRTC ──► экран / запись
        │                                                │
        └────────► ваш Firebase (сигналинг) ◄────────────┘
                   ваш TURN (если прямое соединение невозможно)
```

**Сеть** — это ваш Firebase-проект + случайный 128-битный ID сети + (по желанию) ваш TURN. Всё это упаковано в **код приглашения** (и QR). Новое устройство подключается, только если на нём вручную ввели этот код.

## Быстрый старт

1. Создайте бесплатный Firebase-проект — **[SETUP.md](SETUP.md)**, шаги 1–4 (~10 минут).
2. Установите клиент, выберите «Создать новую сеть», вставьте конфиг Firebase.
3. На каждом устройстве-камере установите агент и введите код приглашения (или отсканируйте QR).
4. Если камеры должны работать через мобильный интернет — настройте TURN: **[docs/TURN.md](docs/TURN.md)**.

## Честно о безопасности и этике

- Агент **никогда не работает скрытно**: иконка в трее (на Android — постоянное уведомление) видна всегда, во время просмотра она меняется и появляется уведомление; Android и Windows дополнительно показывают системный индикатор камеры.
- Подключение к сети — только вручную на самом устройстве, с подтверждением, что это ваше устройство или у вас есть согласие владельца и людей в кадре.
- Используйте CamerasController только для **своих** устройств и помещений. Скрытое наблюдение за людьми без их согласия незаконно в большинстве стран.

Подробности модели угроз — [docs/SECURITY.md](docs/SECURITY.md).

## Сборка из исходников

```
npm install
npm run typecheck
npm run build:agent && npm run dist:win -w packages/agent     # portable .exe агента
npm run build:agent && npm run dist:linux -w packages/agent   # AppImage агента
npm run build:client && npm run dist:win -w packages/client   # установщик клиента
```

Android: открыть `packages/android-agent` или `packages/android-client` в Android Studio (JDK 17/21) и собрать debug APK. `google-services.json` **не нужен** — Firebase настраивается в приложении.

Релизы собирает GitHub Actions ([.github/workflows/release.yml](.github/workflows/release.yml)) по тегу `v*`.

## Структура

```
packages/
  shared/          общий TypeScript: сигналинг, WebRTC, сеть/инвайты, экран настройки
  agent/           Electron-агент (Windows/Linux), трей
  client/          Electron-клиент (Windows), голографический пульт
  android-agent/   Android-агент (Kotlin, foreground-сервис)
  android-client/  Android-клиент (Kotlin, OpenGL)
  turn-worker/     Cloudflare Worker, выдающий временные TURN-ключи
firebase/database.rules.json   правила базы для вашего проекта
```

## Лицензия

[MIT](LICENSE) © DaniksGames5242
