# JARVIS Watch Bridge

Android bridge for LAXASFIT smartwatches + ChatGPT/OpenAI.

## Architecture

LAXASFIT watch -> Android Bluetooth LE / companion sync -> JARVIS Watch Bridge -> FastAPI backend -> OpenAI Responses API.

Health data uses two paths:
1. Direct BLE for standard GATT services exposed by the watch (Heart Rate, Battery, Device Info).
2. Android Health Connect for normalized heart-rate, steps, sleep and oxygen data when a companion app/provider writes those records.

The OpenAI API key is never embedded in the APK. Store it only on the backend as `OPENAI_API_KEY`.

## Features in this build
- BLE scan and connect to nearby watches.
- Enumerate GATT services and subscribe to standard Heart Rate Measurement when present.
- Health Connect repository scaffolding for steps, heart rate, sleep, oxygen saturation.
- Chat command screen backed by FastAPI/OpenAI.
- Android notifications designed to mirror to a paired watch.
- Foreground-capable architecture and Android 12+ Bluetooth permissions.
- GitHub Actions workflow for debug APK builds.

## Important limitation
LAXASFIT models may use proprietary BLE characteristics for health/history sync. This project deliberately does not invent UUIDs. When standard GATT is unavailable, use Health Connect (or a compatible bridge such as Gadgetbridge) until the exact model protocol is verified.

## Run backend
```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
export OPENAI_API_KEY='set-this-securely'
uvicorn main:app --host 0.0.0.0 --port 8000
```

## Android configuration
Set the backend URL in `android/app/build.gradle.kts` via `JARVIS_API_BASE_URL`, or use the default emulator value `http://10.0.2.2:8000/`.

Open `android/` in Android Studio and build/run. On first launch grant Bluetooth, notification, activity and Health Connect permissions.

## Health alerts
This app is for wellness/fitness information only. It must not diagnose conditions or replace medical care. Any alert should direct the user to professional or emergency care when symptoms or readings are concerning.
