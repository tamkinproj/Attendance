# MuslimEdu RFID Attendance System

A native Android Kotlin application for recording student attendance using RFID card scanning and face verification. Built with offline-first architecture to work without internet connectivity.

## Features

- **RFID Card Scanning**: Read 13.56 MHz RFID cards via USB OTG adapter
- **Face Verification**: ML Kit-based face recognition for identity verification
- **Offline-First**: Works completely offline with automatic sync when online
- **Attendance Recording**: Local database with sync queue for reliable data synchronization
- **Encrypted Storage**: AES-256 encryption for sensitive biometric data
- **Teacher Interface**: Jetpack Compose UI for attendance marking

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Database**: Room + SQLite
- **Networking**: Retrofit + OkHttp
- **Dependency Injection**: Hilt
- **Face Recognition**: ML Kit + TensorFlow Lite
- **Background Jobs**: WorkManager
- **Security**: Android Keystore + Tink

## Project Structure

```
├── .github/workflows/          # GitHub Actions CI/CD
├── .devcontainer/              # GitHub Codespace configuration
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/
│   │   │   └── com/muslimedu/attendance/
│   │   │       ├── MainActivity.kt
│   │   │       ├── App.kt
│   │   │       ├── rfid/
│   │   │       ├── face/
│   │   │       ├── ui/
│   │   │       └── ...
│   │   └── res/
│   │       ├── layout/
│   │       ├── values/
│   │       └── drawable/
│   ├── build.gradle.kts        # App dependencies
│   └── proguard-rules.pro
├── build.gradle.kts            # Root Gradle config
├── settings.gradle.kts         # Project settings
└── gradle/libs.versions.toml   # Dependency versions
```

## Getting Started

### Prerequisites

- Android SDK 26+ (API Level 26)
- Java 17 or higher
- Gradle 8.2+
- Git

### Local Development

1. **Clone the repository**:
   ```bash
   git clone https://github.com/manhajed/Attendance.git
   cd Attendance
   ```

2. **Open in Android Studio or VS Code**:
   - Android Studio: File → Open → Select project directory
   - VS Code: `code .`

3. **Build the project**:
   ```bash
   ./gradlew build
   ```

4. **Run on emulator or device**:
   ```bash
   ./gradlew installDebug
   ```

### GitHub Codespace Development

The project is configured for GitHub Codespace with pre-installed:
- Java 17
- Android SDK tools
- Gradle
- Git

Simply open in Codespace and:
```bash
./gradlew build
```

## GitHub Actions

Automated CI/CD pipeline runs on every push:
- **Build**: Compiles APK in debug and release modes
- **Test**: Runs unit and instrumentation tests
- **Lint**: Static analysis and code quality checks
- **Artifacts**: Generated APKs available for download

View build status: https://github.com/manhajed/Attendance/actions

## API Integration

Connects to Laravel backend at `https://manhaje.com/apps/api`:
- Authentication (JWT bearer tokens)
- Student roster sync
- Attendance submission
- Status configuration

Token stored securely in Android Keystore.

## Database Schema

### Core Tables
- `students` - Student records with RFID mappings
- `attendance` - Local attendance records
- `face_templates` - Encrypted face embeddings
- `sync_queue` - Offline-first sync queue
- `audit_logs` - Complete audit trail

See [CLAUDE.md](./CLAUDE.md) for implementation roadmap.

## Security

- **Authentication**: Bearer token in Android Keystore
- **Encryption**: AES-256 GCM for face templates and RFID UIDs
- **Privacy**: No photo storage, only encrypted embeddings
- **Network**: HTTPS only with certificate validation
- **Obfuscation**: ProGuard R8 on release builds

## Contributing

1. Create a feature branch: `git checkout -b feature/your-feature`
2. Commit changes: `git commit -am 'Add feature'`
3. Push to branch: `git push origin feature/your-feature`
4. Open a Pull Request

## License

Proprietary - MuslimEdu Platform

## Support

For issues or questions:
- GitHub Issues: https://github.com/manhajed/Attendance/issues
- Email: support@manhaje.com

## Implementation Phases

- **Phase 1**: Foundation (Database, API, Auth)
- **Phase 2**: RFID Reader Integration
- **Phase 3**: Face Verification
- **Phase 4**: Attendance Recording & Sync
- **Phase 5**: Admin Features & Polish
- **Phase 6**: Hardware Integration & Testing

See [CLAUDE.md](./CLAUDE.md) for detailed specification and implementation guide.
