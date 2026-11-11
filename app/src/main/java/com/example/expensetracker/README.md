# Expense Tracker 💰

Personal finance manager for Android with receipt scanning and cloud sync.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)]()
[![Jetpack Compose](https://img.shields.io/badge/Compose-BOM-green)]()
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue)]()

## Features

- **Expense Management** - Add, edit, delete expenses with categories and budgets
- **Receipt OCR** - Scan receipts using CameraX + Google MLKit Text Recognition (on-device)
- **Smart Categorization** - Auto-suggest category via Firebase Vertex AI
- **Cloud Sync** - Offline-first with Room + Firestore sync via WorkManager
- **Secure Auth** - Firebase Auth with user data isolation
- **Adaptive UI** - Phone, foldable and tablet support (Material 3)

## Tech Stack

- **UI:** Jetpack Compose, Material 3, Adaptive Layout
- **Architecture:** MVVM + Hilt DI
- **Local:** Room Database
- **Cloud:** Firebase Auth, Firestore, Storage
- **Camera:** CameraX + MLKit Text Recognition (Play Services version)
- **AI:** Firebase Vertex AI
- **Performance:** Baseline Profiles, R8 optimization

## Screenshots

| Home | Add Expense | Scan Receipt |
|------|-------------|--------------|
| Add screenshot | Add screenshot | Add screenshot |

## Setup

1. Clone repo
2. Add your `google-services.json` in `app/` folder
    - Download from Firebase Console > Project Settings
    - This file is gitignored for security
3. Sync and Run

```bash
./gradlew assembleRelease