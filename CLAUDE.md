# CLAUDE.md - MuslimEdu Attendance System Documentation

## Project Overview

This is a native Android Kotlin application for recording student attendance using RFID card scanning and face verification. It implements an offline-first architecture that syncs to a Laravel backend when online.

**Repository**: `manhajed/Attendance`  
**Branch**: `claude/new-session-o5rtac`  
**Status**: Gate-only, admin-sign-in-first, offline-capable (see below) - classroom attendance moved to the web app

## Offline gate-only mode (current architecture)

The app is **gate in/out attendance only**. Classroom attendance is done on
the web app.

**Entry flow (current): admin sign-in -> sync -> gate dashboard.** Nothing works until
a school admin (role `admin` only - no teachers, students or other roles)
signs in. Each fresh sign-in is followed by `InitialSyncScreen` (upload this
device's pending scans, then download the student list; the admin can retry
or continue if a step fails). After that the session is remembered: the
admin profile is cached with the token (`TokenManager`), so the app opens
straight to the gate **offline and across restarts**, and `/me` re-checks it
in the background - only an explicit server rejection signs the device out,
never a missing network. Signing out (Sync & Account, with a confirm that
warns about un-uploaded scans) returns to the sign-in screen.
`DeviceSettings.postLoginSyncPending` is persisted so an app killed mid-sync
resumes on the sync step. (An earlier iteration opened on the gate with no
sign-in at all; the user asked for sign-in first instead.) The old
classroom screens (teacher dashboard, class scan, class roster, roster
picker, old admin dashboard, Browse by Class, Leave preview) are **hidden,
not deleted** - `AppRoot` no longer routes to them, but the code is still in
the repo if it's ever needed again.

### How the device's data links to the online database
- **Student -> `code`.** The backend already resolves gate scans by the
  student's `code` across the whole school, so a gate scan is stored locally
  as `code + direction + date + time` (`GateScanEntity`, table `gate_scans`)
  and needs no server student id. RFID card -> student is kept on the device
  for offline lookup and mirrored to the server's card registry
  (`student_rfid_cards`, by `code`) once the backend patch is live. A
  student added by hand syncs fine as long as their real school code is
  used; a wrong code is rejected by the server at upload time and shows up
  on the Sync screen.
- **Account -> device school.** Scans belong to the device, not to a login.
  `DeviceSettings.schoolId` starts unbound (`0`). The first successful admin
  sign-in links the device to that admin's school (`DeviceBindingRepository`),
  moving everything recorded before that (students, gate scans, face
  templates, audit log) onto the real school id in one transaction. After
  that, sign-in from any other school is refused, so one school's offline
  scans can never be uploaded into another's.
- **Subject/class -> not needed at the gate.** The backend files gate scans
  under its `GATE_SUBJECT_ID` sentinel and looks up class/section from
  enrollment. Class attendance only gets gate data when a teacher syncs
  verified records into it (web > Take Attendance > Gate Records).

### Flow: RFID + face confirmation gate
RFID identifies the student -> the **existing** face check confirms it's
them -> only then is attendance recorded. Nothing here is a second face
system: step 2 is `LiveFaceCaptureView` (auto-capture) +
`FaceTemplateRepository.verify` (match against the template enrolled on
this device), the same pieces the old gate screen used.

- **Gate dashboard** (`GateDashboardScreen`, the home screen, no PIN):
  Coming In / Going Out buttons, sync status (pending count, last synced,
  Sync now), recent RFID records, "View all" -> `GateHistoryScreen` (by day,
  filters All / Coming In / Going Out / Face failed). Scanning never starts
  here.
- **RFID Coming In / Going Out** (`GateScanScreen` + `GateScanViewModel`,
  one direction per visit, own header + back handling): "Tap your RFID
  card/tag on the reader." -> card looked up on this device -> Step 1 card
  (name, Student ID = `code`, section, RFID Verified) -> Step 2 face check.
  - Match -> "Attendance Recorded Successfully" (name, ID, section,
    direction, RFID ✓ Face ✓, date, time), back to ready after 3s.
  - No match / no face / 30s without a face -> "Face Confirmation Failed -
    Attendance was not recorded", Try again / Cancel. Saved as a
    **rejected** row (never attendance).
  - Student has no face enrolled -> refused the same way (rejected row).
    The card alone never records attendance - that's what stops card
    sharing. Unknown card -> "Card not registered", nothing saved.
  - Same student + direction within 60s (a recorded one) -> "Already
    recorded", no face step, nothing new saved.
  - The view model outlives the screen, so it ignores the reader unless
    the screen is open (`enter()`/`exit()`) - a tap in the registration
    wizard must not record gate attendance. The wizard does the same the
    other way round (`StudentRegistrationViewModel.enter()`/`leave()`), so a
    card tapped at the gate is never registered to a student left open there.
  - Back with face-confirmed records not yet synced -> dialog "Unsaved
    Attendance Records": **Save & Sync** (upload now; if offline they stay
    Pending Sync and go up automatically), **Leave Without Syncing**,
    **Cancel**. Records are saved on the device the moment they're
    confirmed, so "leave" never discards anything - the button says
    "Without Syncing" for that reason (the request said "Without Saving").
- No simulation anywhere: `MockRfidReader`, Simulate Scan, typed student
  codes on the gate, typed card UIDs on Assign Card, and the three demo
  students with made-up cards (purged by `MIGRATION_7_8`) are gone. Card
  UIDs are compared in one form, trimmed + upper-case (`normalizeRfidUid`;
  the server's `StudentRfidCard::normalizeUid` does the same).
- **Records** (`gate_scans`, v8 via `MIGRATION_7_8`): student code/id,
  section, card UID, `rfid_verified`, `verified_by_face`, score, `outcome`
  (`recorded` | `rejected`), reason, and a per-row `event_id` UUID. Only
  `recorded` + RFID + face = verified attendance (`isVerifiedAttendance`).
- **Sync** (`GateSyncManager`): 1) card registrations
  (`RfidCardSyncManager` -> `admin_student_rfid_set`), 2) attendance ->
  `admin_gate_attendance_scan` **oldest first**, stopping at the first
  temporary failure so a later "out" never reaches the server before its
  "in" (404/422 -> failed and skipped; 401/403 -> stop; 405/501 -> "not set
  up on the server yet", kept pending; offline is never counted as an
  attempt), 3) failed face checks -> `admin_gate_rejected_scan`,
  best-effort, after attendance so they can never hold it up. The event id
  makes every upload idempotent on the server. Runs after each record, on
  Sync now / Save & Sync, after sign-in, every 15 min, and **as soon as the
  network returns** (`GateSyncScheduler`: a one-off `SyncWorker` with a
  CONNECTED constraint, queued on every record). The UI shows Pending Sync
  -> Synchronizing -> Synced.
- **Register Card & Face** (Admin > Register Card & Face, PIN-locked) is
  one wizard, not separate card and face screens (the user asked for it
  that way): **1 Student** (picker with search and each student's Card /
  Face status) -> **2 Card** -> **3 Face** -> **4 Done** (summary, "Register
  next student" / "Finish"). `StudentRegistrationScreen` +
  `StudentRegistrationViewModel`; the old `RfidEnrollmentScreen` /
  `FaceEnrollmentScreen` and their view models were removed.
  - Card: attaches a physically read card to an existing student - never
    creates one. A card registered to another student is refused (tap a
    different one, or deactivate it there first) and the step keeps
    listening; a student who already has a card can **Keep this card** or
    tap a new one and confirm "Replace card?" (the old one is deactivated).
    The card is saved the moment it's read, then the wizard moves straight
    on to the face while the upload to the server registry finishes (its
    result shows on the card summary).
  - Face: the gate's own live auto-capture (`LiveFaceCaptureView`) +
    `FaceTemplateRepository.enroll`. A student who already has a face can
    keep it or re-enroll; "Skip face for now" finishes without one (the
    Done step warns that the gate refuses them until a face is enrolled).
  - Students list: card number + sync state; the card icon opens Replace /
    Deactivate (Replace opens the wizard at the card step), the face icon
    opens the wizard at the face step (card step if there's no card yet).
    Back from a wizard opened there returns to Students.
  - Every card change goes to the server registry (pending until sent; a
    refusal shows its reason). The student download applies the server's
    cards (`rfid_managed: true`) but never overwrites a change on this
    device that hasn't been sent.
- **One face per student** - `FaceTemplateRepository.enroll` compares the
  new face with every other student's enrolled face in the school and
  refuses a match ("This face is already enrolled for <name> (<ID>)",
  `FaceEnrollResult.AlreadyEnrolled`, logged in the Audit Log). A match
  means a score at the gate's own match threshold (Face Settings, default
  0.75): exactly when the gate would accept the face as that other student.
  Runs only when `FaceRecognizer.canTellPeopleApart` is true - it is for
  the MobileFaceNet recognizer below; the old landmark placeholder scored
  any two faces ~0.99 and would have refused every student after the first.
- **Face recognition = MobileFaceNet** (`MobileFaceNetRecognizer`, replaced
  the landmark-ratio placeholder `MlKitFaceRecognizer`, which could not
  tell people apart). ML Kit finds the face + eyes/mouth corners ->
  `FaceAlignment` fits a similarity transform onto the standard ArcFace
  112x112 layout (sides picked by x, so mirrored frames align the same) ->
  RGB `(v - 127.5) / 128` -> `assets/mobilefacenet.tflite` (input fixed at
  batch 2, so the face is fed twice; output 192-d, L2-normalised) -> score
  `(1 + cosine) / 2` (`FaceAlignment.matchScore`, ~0.5 different people,
  0.9+ same person). Model: MIT (syaringan357/Android-MobileFaceNet-MTCNN-
  FaceAntiSpoofing), converted from sirius-ai/MobileFaceNet_TF (Apache-2.0);
  source, SHA-256 and both licences in `assets/licenses/mobilefacenet-NOTICE.txt`
  (the SHA is pinned by `FaceAlignmentTest`). The `.tflite` is stored
  uncompressed (`androidResources.noCompress`) because it is memory-mapped.
  - Checked in the sandbox with the same alignment/normalisation in Python
    (tflite-runtime, MediaPipe for the landmarks) on 2 people x 2 photos:
    same person 0.91-0.93, different people 0.46-0.55. **Not checked on a
    phone camera** - tune Face Settings if real students get refused.
  - Default match threshold is now **0.75** (cosine 0.5), saved under a new
    prefs key (`min_match_score_mobilefacenet`) so a value chosen for the
    old matcher is dropped.
  - Old faces: `MIGRATION_8_9` adds `face_templates.model` (`landmark` for
    existing rows, `mobilefacenet` for new). Every `FaceTemplateDao` lookup
    only sees `mobilefacenet` rows, so students enrolled before this show
    "no face" (and the gate refuses them) until re-enrolled in the wizard,
    which replaces the old row. Old rows are kept, not deleted.
  - Liveness is unchanged: still `LivenessDetector`'s eye-open heuristic,
    so a good photo/video of the student can still pass the camera step.
- Admin screens (Register Card & Face, Students, Face Settings, Audit
  Log, Sync & Account, Change PIN) sit behind a **device PIN**
  (`AdminPinManager`: salted PBKDF2 hash in Keystore-backed encrypted prefs,
  5 wrong tries -> 60s lockout, counted persistently). They relock when you
  return to the gate. **Forgot PIN** = a *fresh* admin sign-in (an already
  open session doesn't count - `AuthViewModel.lastLoginAt`) clears it.
- Sign-in is limited to role `admin` (the gate endpoints' `requireAdmin()`
  checks `role_id === 2`, so teachers and superadmins would only get 403s).
  The only in-app sign-in after that is the "forgot PIN" re-authentication,
  which skips the sync step.
- Real Room migrations (`MIGRATION_6_7`, `MIGRATION_7_8`), not the
  destructive fallback - installed devices hold card assignments and face
  templates that exist nowhere else.
- Face templates stay on the device that enrolled them (unchanged): each
  gate device enrolls its own faces.

### Backend + web changes (Laravel - not in this repo)
The user supplied their Laravel source (routes, app, database) and web
front end (`web/v2`, a static PWA). The patch for them is delivered as a
zip (`gate-backend-patch.zip`: files at their real paths, README, full
diff) and is **not deployed until the user uploads it** - check before
assuming it's live. It is cumulative (includes the earlier route fix).

- **Routes** (`routes/api.php`, all `Route::post`): `admin_gate_attendance_scan`,
  `admin_gate_attendance_today`, `admin_gate_students`,
  `admin_gate_rejected_scan`, `admin_student_rfid_set`,
  `admin_gate_student_overview`, `teacher_gate_records`,
  `teacher_gate_sync`. The first two existed in the controller but were
  never registered - every gate upload got 405 from the GET-only
  `Route::fallback()` in `web.php`.
- **Migrations**: `student_rfid_cards` (card registry; one active card per
  UID and per student enforced by unique indexes on NULL-when-inactive
  mirror columns; old cards kept as inactive/replaced|removed) and
  `gate_events` (every scan with RFID/face results, outcome, reason,
  unique `(school_id, device_event_id)` for idempotent uploads).
- **`admin_gate_attendance_scan`** accepts `time`, `rfid_uid`,
  `rfid_verified`, `face_confirmed`, `face_score`, `device_event_id`;
  writes the gate event + the daily `attendances` gate row
  (`markGateScan`: row-locked, events sorted by time, exact duplicates
  dropped, check-in = earliest "in"). A retried event id returns 200 with
  the stored result. `face_confirmed: false` here is logged as rejected,
  never attendance.
- **`admin_gate_students`** adds each student's active `rfid_uid` and
  `rfid_managed: true`.
- **Gate rows are no longer counted as class attendance**: a global scope
  on `Attendance` hides `GATE_SUBJECT_ID` rows (`Attendance::gateRecords()`
  reads them); raw `DB::table('attendances')` analytics filter them too.
- **Web admin > Gate Students** (`gate-students.php/.js`, tile next to
  Attendance): per student Student ID, name, section, registered RFID + status,
  time in/out, last scan, face status, attendance status; filters for
  search (name/ID), section, date, Coming In/Going Out, RFID status, face
  status, attendance status; detail sheet with the day's events and
  **Deactivate RFID card**.
- **Teacher > Take Attendance**: the camera "Scan QR / ID Card" method is
  replaced by **Gate Records (RFID + Face)** - pick class & date, see each
  student's verified Coming In/Going Out and class status, **Sync** marks
  present (gate time, source `gate`) only students with a verified "in"
  and no class record yet. Never overwrites a teacher's status, never
  duplicates, refuses a locked roster (423).
- Verified on the sandbox's PHP: `php -l` on every file; the gate service
  run against SQLite with real Eloquent (card registry, conflicts, replace,
  idempotent events, overview, teacher sync incl. no-duplicate and lock);
  both web pages driven in headless Chromium against a mocked API. Not run
  inside the full Laravel app or on MySQL.
- Cleanup left to the user: `app/Http/Controllers/AttendanceApi.php` is a
  byte-identical stray copy of the trait (wrong folder for its namespace),
  plus many backup files (`api.php1`, `ApiController.phpe`, `*.phpo`, ...).

**Not verified on a device**: the app compiles and its unit tests run in CI,
but the RFID reader, camera and migration need real hardware - especially
`MIGRATION_7_8` on a device that already has v7 data.

### Brand theme (from the logo)
- Palette in `ui/theme/Color.kt`: `BrandTeal` #369A8E is the logo's exact
  teal - used for the logo, gradients, big icons. It's only ~3.4:1 on white,
  so buttons/text use `BrandPrimary` #267A70 (same hue, 5.1:1). Background is
  a near-white #F5F8F8. Status accents are readable as text on white: blue
  `AccentBlue` #2F63C8 (Going Out), amber `AccentGold` (pending/warnings),
  coral `AccentRed` (errors), `AccentSlate` (info); In/success is the brand
  teal (`AccentSuccess`).
- Gate dashboard (redesigned from the user's mockup): own header (logo tile,
  large "Gate Attendance", admin name, round admin button - no app bar),
  soft tinted summary card with dotted stat labels, tinted Coming In
  (teal) / Going Out (blue) cards, sync card with a Synced / Sync now pill,
  empty-history illustration. Tints are the accent composited over the
  surface color, so they also work in the dark scheme.
- Tokens were renamed semantically (`BrandPurple*` -> `BrandPrimary*`,
  `AccentTeal*` -> `AccentSuccess*`); hidden classroom screens use the same
  tokens so they follow the theme too.
- `Theme.kt` sets the `surfaceContainer*` roles explicitly - Material3 1.2
  draws cards/dialogs/menus from them and otherwise falls back to the
  baseline lavender-grey. A real teal dark scheme replaced the template one.
  No dynamic (wallpaper) color.
- Launcher icon and `drawable-nodpi/brand_logo.png` are cut from the logo
  image the user supplied (teal mark on transparent, centred in the
  adaptive-icon safe zone); the previous icon had a leftover background
  smudge and an off-centre mark. App label is now "Gate Attendance".

## Development Setup

### Quick Start

```bash
# Clone and open in GitHub Codespace or locally
git clone https://github.com/manhajed/Attendance.git
cd Attendance

# Build
./gradlew build

# Run on device/emulator
./gradlew installDebug
```

### GitHub Codespace

Project is pre-configured for Codespace development with:
- `.devcontainer/devcontainer.json` for environment setup
- Pre-installed Java 17, Android SDK, Gradle
- GitHub Actions automatically builds on push

### Key Technologies

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Database**: Room + SQLite
- **API**: Retrofit + OkHttp
- **DI**: Hilt
- **ML**: ML Kit + TensorFlow Lite
- **Background**: WorkManager
- **Security**: Android Keystore + Tink

## Project Structure

```
com.muslimedu.attendance/
├── MainActivity.kt              # Entry point
├── App.kt                       # Application class with Hilt
│
├── ui/
│   ├── screens/                # Compose screens
│   │   ├── auth/
│   │   ├── scanner/
│   │   ├── history/
│   │   └── admin/
│   ├── components/             # Reusable Compose components
│   ├── navigation/             # Navigation graph
│   └── theme/                  # Theme, colors, typography
│
├── data/
│   ├── db/                     # Room database
│   │   ├── AppDatabase.kt
│   │   ├── entities/
│   │   └── dao/
│   ├── repository/             # Data access layer
│   ├── remote/                 # Retrofit API client
│   └── local/                  # Local preferences
│
├── domain/
│   ├── model/                  # Domain models
│   └── usecase/                # Business logic
│
├── rfid/
│   ├── RfidReader.kt          # Interface
│   ├── UsbRfidReader.kt       # USB implementation
│   └── MockRfidReader.kt      # Testing mock
│
├── face/
│   ├── FaceRecognizer.kt      # Interface
│   ├── MlKitFaceRecognizer.kt # ML Kit impl
│   └── LivenessDetector.kt
│
├── sync/
│   ├── SyncQueueManager.kt
│   ├── SyncWorker.kt          # WorkManager job
│   └── RetryStrategy.kt
│
├── security/
│   ├── TokenManager.kt
│   ├── EncryptionHelper.kt
│   ├── KeystoreManager.kt
│   └── AuditLogger.kt
│
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   ├── NetworkModule.kt
│   └── ...
│
└── util/
    ├── NetworkMonitor.kt
    ├── DateTimeUtil.kt
    └── Constants.kt
```

## Implementation Roadmap

### Phase 1: Foundation ✅ (Scaffolded)
- [x] Gradle project setup with dependencies
- [x] GitHub Actions build workflow
- [x] GitHub Codespace configuration
- [x] Basic Compose setup and MainActivity
- [x] Room database entities and DAOs (Student only so far, for RFID lookup)
- [x] Retrofit API client (login/me/refresh-token/logout, AuthInterceptor + ErrorInterceptor)
- [x] TokenManager (EncryptedSharedPreferences, Android Keystore-backed)
- [x] AuthViewModel + LoginScreen
- [x] Basic navigation (state-driven Login/Scan switch in AppRoot; no NavGraph yet - only 2 screens)
- [x] Login restricted to `teacher`/`admin`/`superadmin` (`AuthRepository.ALLOWED_APP_ROLES`
      - the backend's own login endpoint allows many more roles - `student`,
      `parent`, `accountant`, `librarian`, and others - that have no place in
      this app. Enforced in both `login()` (a disallowed role's token is
      never even saved) and `validateSession()` (covers a token saved by an
      older build, or a role changed on the backend since last login) - see
      "Login restricted to teacher/admin" further down for the full reasoning.)

### Phase 2: RFID Reader (2-3 days)
- [x] RfidReader interface + USB implementation (keyboard-emulation HID + raw USB HID/CCID)
- [x] MockRfidReader for testing (debug builds; "Simulate Scan" button)
- [x] Student roster sync from Laravel (`/teacher_attendance_classes` + `/teacher_attendance_roster`; auto-syncs the teacher's one class, or shows a picker if they have several; falls back to the sample-data cache if the sync fails and the user chooses "Continue Offline")
- [x] RFID → Student mapping (against the local/seeded cache)
- [x] RfidScanScreen UI

### Phase 3: Face Verification (2-3 days)
- [x] ML Kit face detection setup (real - detection only, see caveat below)
- [x] Face template encryption (Tink AES-256-GCM, Android Keystore-wrapped key,
      via EncryptionHelper - separate from TokenManager's EncryptedSharedPreferences)
- [x] Face enrollment flow (FaceEnrollmentScreen: pick a student from the synced
      roster, capture via an embedded live camera preview, extract + encrypt +
      store - see the CameraX caveat below for what replaced the system-camera
      capture this used to launch)
- [x] Real-time face verification (wired into the RFID flow: a match against a
      student *with* an enrolled template requires face verification before
      attendance is recorded; students with no template keep working RFID-only)
- [x] Liveness detection (basic heuristic from ML Kit's own eye-open probability
      + landmark count - not adversarially robust, see LivenessDetector)
- [x] FaceVerificationScreen UI (built inline in RfidScanScreen as
      FaceVerificationCard, not a separate screen file, since it's one state
      in the same scan flow rather than an independent destination)
- [x] Embedded auto-capture camera (LiveFaceCaptureView: CameraX preview bound
      directly into the enrollment and verification cards - no system camera
      app launch - that auto-captures once a fast, throwaway ML Kit pass sees
      a plausibly live face for 3 consecutive frames. No manual "Capture Now"
      button or manual "Skip - Mark Present Anyway" override - just the
      camera preview and a status line; a failed verification re-arms the
      same camera for another automatic attempt. See the caveat below for
      exactly what is and isn't verified here.)

**Superseded**: the placeholder below was replaced by a real MobileFaceNet
model - see "Face recognition = MobileFaceNet" near the top. Kept for history.

**Important caveat on the above**: ML Kit's Face Detection API does face
*detection* (bounding box, landmarks, eye/smile probabilities) - it has no
embedding output and is not a face-recognition API. Real 1:1 verification
needs a separate TensorFlow Lite embedding model (e.g. MobileFaceNet), and no
such model file exists in this project - there was no way to obtain/bundle one
in the environment this was built in. `MlKitFaceRecognizer.extractTemplate()`
is a geometric-landmark placeholder (normalized inter-landmark distances)
standing in for a real embedding, clearly flagged in its own doc comment. It
makes the full pipeline (enrollment, encrypted storage, verification, UI, the
RFID-flow integration) real and testable end to end, but it is not a real
biometric check: it is not rotation-invariant, is sensitive to expression, and
is trivially spoofable. Swap it for a real TFLite model behind the same
`FaceRecognizer` interface before relying on this for actual anti-fraud.

**CameraX embedded capture caveat**: enrollment and verification used to
launch the system camera app (`ActivityResultContracts.TakePicturePreview()`)
and wait for a manual shutter tap. `LiveFaceCaptureView` replaces that with an
in-app `PreviewView` + `ImageAnalysis` bound via `ProcessCameraProvider`: a
second, cheap ML Kit detector (`PERFORMANCE_MODE_FAST`, but **landmarks on** -
see below) runs on throttled frames (one every ~300ms) purely to decide *when*
a frame looks like a live face, gated on `LivenessDetector.score()` (the same
object Phase 3 already uses) reaching its top tier for 3 consecutive frames
before auto-capturing. That gate has no opinion on identity or the real
match/liveness score - the captured bitmap still goes through
`MlKitFaceRecognizer`'s own accurate detector via the existing enroll()/
verify() calls, unchanged.

**That fast detector must keep `LANDMARK_MODE_ALL`.** It originally didn't,
and that was a fatal bug: ML Kit defaults to `LANDMARK_MODE_NONE`, so
`Face.getAllLandmarks()` came back empty, so `LivenessDetector.score()` hit its
"fewer than 5 landmarks" floor of `0.3` on *every* frame, which is below the
`0.9` gate - auto-capture could never fire, on any device, in any lighting.
Face enrollment and the attendance flow's face-verification step both just sat
on "Position your face in the frame" forever. The two are coupled: this gate
scores exactly what that detector is configured to report, so changing either
means re-checking the other.

Because there is no manual shutter button any more (removed on request), a
gate that won't open is a dead end with no way out - so `LiveFaceCaptureView`
also keeps the best-scoring frame it has seen and, after
`AutoCaptureGate.FALLBACK_AFTER_MS` (6s) of seeing a face without clearing the
top tier, hands that one over anyway rather than hanging. It never hands over
a frame no face was found in. This is safe because the *accept* decision is
made downstream, not here: `FaceTemplateRepository.enroll()` re-scores the
captured frame with the accurate detector against the configured liveness
threshold (default `0.7`, and since `LivenessDetector` only returns
0.3/0.5/0.6/0.9, in practice that means "the accurate pass must also say
0.9"), and verification still has to match the stored template. A fallback
capture that can't clear that bar fails *visibly*, with a reason and a "Try
Again" button, instead of silently never capturing.

CameraX's YUV_420_888 frames are converted to a Bitmap by hand
(`CameraFrame.kt`'s `yuv420ToNv21`/`toUprightBitmap`), since `FaceRecognizer`
only speaks Bitmap. That conversion's row/pixel-stride arithmetic - the part
most likely to silently produce a garbled frame if wrong, since it wouldn't
crash, detection would just mysteriously never trigger on some devices - is
covered by `CameraFrameConversionTest`, which runs in CI. What is **not**
verified: the actual CameraX plumbing (`PreviewView`, `ImageAnalysis`,
`ProcessCameraProvider.bindToLifecycle`, the Compose `AndroidView` interop,
front-vs-back camera selection) needs a real device or emulator with a camera
to exercise, neither of which exists in the sandbox this was built in. That
code was written to the documented CameraX API shape and reviewed by hand, not
executed. Test on real hardware before depending on it for actual attendance
tracking, and watch for: a device with no front camera (there's a fallback to
`DEFAULT_BACK_CAMERA`, untested), and the 3-consecutive-frames/300ms-throttle
constants in `LiveFaceCaptureView.AutoCaptureGate` needing tuning against real
lighting conditions.

### Phase 4: Attendance Sync (2-3 days)
- [x] AttendanceRepository + DAO (folded sync-queue tracking columns directly
      into the attendance table rather than a separate generic sync_queue
      table - see AttendanceEntity's doc comment for why)
- [x] SyncQueueManager with exponential backoff (1/2/4/8/16/32/60/120s, matching spec)
- [x] IdempotencyHandler (UUID per record, sent unchanged on every retry)
- [x] WorkManager background sync job (SyncWorker, 15min floor + NetworkType.CONNECTED,
      Hilt-injected via HiltWorkerFactory - App is now a Configuration.Provider)
- [x] Conflict resolution (403/404/422 -> fail permanently, no retry; everything
      else -> exponential backoff up to 8 attempts)
- [x] Fixed: sample/demo students (`StudentRepository.seedSampleDataIfEmpty`)
      were never marked `isLocalOnly`, so their attendance was dutifully sent
      to the real backend and 404'd every time - a permanent "Failed" count
      that "Retry Failed Syncs" could never clear. See "'Failed: 1' on the
      Admin Dashboard that never clears" below.
- [x] Fixed: attendance recorded on-device but never reached the web
      database past the first student of each class period - see "Attendance
      recorded on-device but never appears on the web dashboard" below.
      `SyncQueueManager` now calls `/teacher_attendance_scan`, not
      `/teacher_attendance_submit`.
- [ ] AttendanceConfirmScreen (scans record automatically as "present" today;
      no manual status/remark entry yet - revisit if teachers need to mark
      late/excused/absent from this screen rather than just present-on-scan)
- [x] Sync status UI (a "Present - recorded" line on the match card; no live
      pending/syncing/failed indicator yet - see RfidScanScreen)
- [x] Auto-advance after a match (MatchedStudentCard auto-dismisses back to
      the Listening/ready-to-scan state ~2s after a successful scan, via a
      `LaunchedEffect` keyed on the student+uid - the "Scan Next" button is
      still there for a teacher who doesn't want to wait, but tapping it is
      no longer required between students)
- [x] Network status indicator (`NetworkMonitor`: a live "Online"/"Offline"
      badge - `NetworkStatusBadge`, shared between RfidScanScreen and
      AdminDashboardScreen - was listed in this doc's original project
      structure but never actually built until now; there was previously no
      connectivity signal anywhere in the app. Purely informational, same as
      the reader-connection badge next to it: this app is offline-first by
      design, a scan already saves locally and queues for sync regardless of
      connectivity, so nothing gates on this - it only sets expectations for
      whether a scan is likely to sync right away or sit pending. Uses
      `registerDefaultNetworkCallback` filtered to
      `NET_CAPABILITY_VALIDATED`, so a wifi network with no real internet
      behind it - a captive portal, a router with no upstream - correctly
      shows "Offline" rather than a connected-but-useless "Online".)

### Phase 5: Admin Features (2-3 days)
- [x] Admin authentication (role-gated, not a separate login - AppRoot checks
      the logged-in user's `role` field against "admin"/"superadmin" from
      `/me` and only then shows the admin icon; there's no admin-specific
      credential flow, since the backend's existing teacher login already
      carries a role)
- [x] RFID enrollment screen (RfidEnrollmentScreen + RfidEnrollmentViewModel:
      pick a student, tap a card (or type the UID manually / simulate with
      MockRfidReader), with collision detection against a card already
      assigned to someone else)
- [x] Face enrollment screen (from Phase 3, now reachable from the admin
      dashboard as well as the top app bar)
- [x] AdminDashboardScreen (student/face/RFID counts, today's sync status
      counts, "Retry Failed Syncs" button, "Sync Roster from Server" button,
      links to enrollment/settings/export)
- [x] Audit logging (AuditLogger + audit_logs table: login/logout, attendance
      recorded, manual override, face enrolled, RFID assigned - each entry
      requires an active session for its school_id, so pre-login failures
      aren't logged; not yet surfaced in any UI screen, just written)
- [x] Settings/configuration (SettingsScreen: face-verification match-score
      and liveness thresholds, persisted in plain SharedPreferences and read
      live by FaceTemplateRepository - previously hardcoded constants; the
      liveness threshold is now actually enforced during enrollment, where
      before it was computed and stored but never gated on)
- [x] Data export (ExportScreen: dumps every locally recorded attendance row
      to a CSV file under the app's external files dir, shared via
      FileProvider + a system share sheet - e.g. to email or Drive)
- [x] Student list + add-student (StudentListScreen, admin-only: shows every
      student in the local cache with an RFID/local-only indicator, "+" opens
      a dialog to add one. Added students are LOCAL-ONLY - there is no backend
      endpoint to create a student, only login/roster/attendance exist per the
      spec - so they get a negative `student_id` and never sync; the dialog
      says so. "Scan Card to Assign" and "Take Attendance" are also linked
      from here now, alongside the student list, for a one-screen admin flow:
      add a student -> assign their card -> take attendance.)
- [x] Per-student face registration from the list (each row in
      StudentListScreen has a face icon - green if a template is already on
      file, outline if not - that jumps straight into
      FaceEnrollmentScreen's capture step for that student via a
      `PresetFaceTarget`, skipping its picker. Confirmed local-only: see
      FaceTemplateRepository's class doc - it has no ApiService dependency at
      all, only Room + the Keystore-wrapped Tink cipher, and no endpoint in
      the spec accepts a face upload. During attendance, RfidViewModel
      already required face verification for any student with a template
      before this change - this only adds a faster way to enroll one from the
      list; it doesn't change what happens at scan time.)
- [x] Admin: Browse by Class (`AdminDirectoryScreen` + `AdminDirectoryViewModel`:
      Classes -> Sections -> Students -> one Student's detail, all four
      levels live from the network via three real, confirmed admin endpoints
      - `admin_classes_list`, `admin_sections_list`, `admin_section_students`
      - never cached locally. See "Admin: Browse by Class" further down for
      why this is a separate, read-only repository from `RosterRepository`
      and what it can and can't do compared to the teacher-scoped roster
      sync. Reachable from the Admin Dashboard's "Browse by Class" button,
      alongside the existing flat Student List, which is unchanged.)
- [x] Gate In/Out Attendance (`GateAttendanceScreen` + `GateAttendanceViewModel`
      + `GateAttendanceRepository`, admin-only, reachable from the Admin
      Dashboard's "Gate In/Out Attendance (Campus-wide)" button: scan/type
      any active student's code campus-wide, tag the scan In or Out, see a
      live "who's on/off campus today" list. Needed two new backend
      endpoints - see "Gate In/Out Attendance" further down for the full
      design and why this was previously investigated and found
      not-buildable until the user supplied their live backend source.)

- [x] Realigned to the real backend (previously the spec document; the two
      disagree in almost every response shape - see "Confirmed against the
      real backend" below for the full list, and for why the app's own
      photo cache and RFID-sync design work the way they do)

### Phase 7: Modern UI/UX Redesign - Phase 1 (Foundation + Core Screens)

Triggered by a reference mockup image plus a large (42-section) UI/UX spec
asking to bring the whole app up to a modern purple/gold/teal look. Given the
real size of that ask (dark theme, tablet nav rail, a full animation system,
bottom nav for two roles, an Attendance History screen, an Audit Log viewer,
accessibility pass) is realistically several more phases of work, this pass
scoped down to the structural gaps that mattered most and left the rest
explicitly deferred rather than half-building everything at once:

- **Theme foundation**: `Type.kt` now defines the full Material 3 type scale
  (previously only `bodyLarge`/`titleLarge`/`labelSmall` were customized -
  every other `MaterialTheme.typography.*` call across the app was silently
  falling back to stock Compose defaults). Every hardcoded hex color
  (`0xFFAA0000`/`0xFF2E7D32`/`0xFF00AA00`/`0xFFE65100`) in `RosterGateScreen`,
  `RfidEnrollmentScreen`, `StudentListScreen`, and `AdminDirectoryScreen` was
  replaced with the existing `AccentRed`/`AccentTeal`/`AccentGold` theme
  tokens - a pure token swap, no behavior change.
- **New shared components** (`ui/components/`): `SectionHeader`, `EmptyState`,
  `StatusPill`, `StatChip` (pulled out of `AdminDashboardScreen`),
  `ReaderStatusBadge` (pulled out of `RfidScanScreen`), `QuickActionCard`,
  `StepIndicator` - so the new screens below and the restyled existing ones
  share one look instead of each hand-rolling its own tile/badge/header.
- **Splash screen** (`ui/screens/SplashScreen.kt`): replaces the bare
  `CircularProgressIndicator` box AppRoot used to show during
  `AuthState.CheckingSession` with a branded moment.
- **Teacher Dashboard** (`ui/screens/dashboard/TeacherDashboardScreen.kt` +
  `TeacherDashboardViewModel`) - the single biggest structural gap: there was
  no teacher home screen at all before this. `RfidScanScreen` used to be
  shown directly the instant a roster synced, doubling as "home" with no
  summary of the day. Now `AppRoot`'s `Home` destination shows this
  dashboard once the roster is `Ready` (greeting, a real "Present" count
  from `AttendanceDao`, reader/network status, quick actions), and "Scan
  Attendance" pushes into a separate `ScanAttendance` destination that shows
  the unchanged `RfidScanScreen`. **No Absent/Late chips** - this app has no
  absent/late marking anywhere (`AttendanceEntity.status` is always forced to
  `"present"` by the scan flow), so rather than fabricate numbers with no
  backing data, the second stat is the honest complement: "Not Yet Scanned"
  = roster size minus present-today.
- **Class Roster screen** (`ui/screens/dashboard/ClassRosterScreen.kt` +
  `ClassRosterViewModel`) - a searchable read-only list of the active
  class's roster with each student's today's-scan status, via a new
  `StudentDao.findBySchoolAndSection()` query (no schema change).
- **Admin Dashboard restyle** - the flat vertical stack of ~9 buttons is now
  a sectioned 2-column icon quick-action grid (`ActionGrid`, a fixed
  `chunked(2)` layout rather than a wrapping `FlowRow`, to avoid an
  experimental-API opt-in). Same click handlers/ViewModel, layout-only
  change. The sync stat row is now tappable through to a new dedicated
  screen instead of only showing distinct failed-messages inline.
- **Sync Status screen** (`ui/screens/admin/SyncStatusScreen.kt` +
  `SyncStatusViewModel`) - Synced/Pending/Failed counts plus a real
  per-record failed list (student name, check-in time, the actual error
  message, retry count) via a new `AttendanceDao.getFailedOnDate()` query,
  with "Sync Now"/"Retry Failed" actions reusing the existing
  `SyncQueueManager`.
- **RFID/Face enrollment polish** - both screens now show a
  "1. Select Student · 2. ... · 3. Confirm" `StepIndicator`, and the shared
  `StudentPickerContent` (used by both) is now a scrollable list of photo
  cards instead of plain text buttons - both `RfidEnrollmentViewModel` and
  `FaceEnrollmentViewModel` gained a `StudentPhotoCache` dependency (already
  used elsewhere) to back this.
- **Student List filters** - `All`/`RFID`/`Face Enrolled`/`Local` filter
  chips plus a search field, both new (there was no search bar here before),
  filtering the already-loaded rows client-side - no new queries.
- **Settings regrouped** into a single card with a section header and icon
  instead of two bare sliders in a plain column - same `SettingsViewModel`
  bindings.

**Explicitly deferred to a later pass** (tracked here, not silently
dropped): an Attendance History screen, an Audit Log viewer screen (the data
is already being written by `AuditLogger` - confirmed still unused in any
UI), bottom navigation bars for the teacher/admin roles (deferred because it
reshapes the whole single-`Destination`-enum navigation model this app
currently uses, and deserves its own pass rather than being bolted on
alongside everything above), tablet responsiveness (nav rail, two-column
layouts - waiting on the bottom-nav shape to settle first), a real
brand-derived dark theme (`Theme.kt`'s `DarkColorScheme` is still the stock
Compose template default, not a deliberate dark palette), and a broader
animation/accessibility polish pass.

**Not verified by a local build**: this environment's outbound network
policy blocks `dl.google.com`, so the Android Gradle Plugin can't be
resolved here and no local Android SDK/AGP cache exists either - every
changed/new file was instead reviewed by hand (imports, function signatures,
brace/paren balance) rather than compiled. Verify with a real build (the
existing GitHub Actions workflow, or a local/Codespace `./gradlew
assembleDebug`) before relying on this.

### Phase 9: Modern UI/UX Redesign - Phase 2 (Bottom Nav + 3 New Features)

Built from a design canvas prototype (a complete visual blueprint audited
against this app's real features, roles, and workflows - see the canvas's
own coverage-audit table for the full feature-to-screen mapping). Most of
the canvas was already real screens from Phase 1 needing closer visual
alignment; four things here are genuinely new:

- **Bottom navigation** (`AppRoot.kt`): a `NavigationBar` in the `Scaffold`'s
  `bottomBar` slot, shown only at a small set of "top-level" destinations per
  role - Teacher: Home/Scan/Roster/Profile; Admin: Dashboard/Scan/Students/
  Settings. Deliberately does **not** touch the existing "back always
  returns to Home" model (`AppRoot`'s own doc comment already explains why
  that's intentional) - it's a faster way to reach four destinations, not a
  real back stack. No fake "History" tab - that screen doesn't exist in the
  real app and wasn't invented to fill a slot.
- **Profile screen** (`ui/screens/profile/ProfileScreen.kt`): the account
  behind the already-real logout action, finally shown somewhere - name,
  email, role, school ID, and a Logout button. Takes `AuthState.LoggedIn`'s
  already-known `UserDto` directly, no separate ViewModel.
- **Audit Log viewer** (`ui/screens/admin/AuditLogScreen.kt` +
  `AuditLogViewModel` + `AuditLogDao.getRecentForSchool()`): surfaces
  `AuditLogger`'s existing `audit_logs` table, which was being written
  (login/logout, attendance recorded, manual override, face enrolled, RFID
  assigned, student added) with no viewer anywhere before this. The
  existing `getRecent()` query had no `school_id` filter - a device that's
  had more than one account on it would have shown every school's entries
  mixed together, so this added a scoped variant rather than reusing it.
- **"Mark Present" manual override** (`ClassRosterScreen`/
  `ClassRosterViewModel`): `AttendanceRepository.recordScan()` already
  accepted a `manualOverride: Boolean` (sets `overrideBy`, logs
  `AuditLogger.ACTION_MANUAL_OVERRIDE`) - there was simply no UI anywhere
  that ever called it with `true`. Admin-only text button next to a
  not-yet-scanned roster row.
- **"Assign to Student" from Unknown Card** (`RfidScanScreen` ->
  `RfidEnrollmentScreen`): admin-only second action on the "Unknown Card"
  result, carrying the scanned UID into RFID Enrollment's student picker via
  a new `PresetRfidUid` (kept separate from the existing student-keyed
  `PresetRfidTarget`, so that flow is untouched) - picking a student then
  calls the already-existing `assignManually(student, uid)` directly,
  skipping the "tap the card" step since the card is already known.

Also restyled to match the canvas more closely: `TeacherDashboardScreen`'s
stat card is now a purple-gradient hero card instead of plain white, its
reader/network badges are now one combined status card with a leading dot
each; `RfidScanScreen`'s background is now full-bleed dark while scanning
(the teal/gold/red/purple state cards keep their own light containers,
unchanged, just sitting on a dark backdrop now).

**Explicitly still out of scope**, carried over unchanged from Phase 1's own
deferred list: a brand-derived dark theme (`Theme.kt`'s `DarkColorScheme` is
still the stock Compose template), tablet/nav-rail layouts, and a real
Attendance History screen (still no such feature in the real app, still not
invented to fill a mockup slot).

**Not verified by a local build** for the same reason as Phase 1 (this
environment can't reach `dl.google.com` for the Android Gradle Plugin) -
reviewed by hand instead; verify with a real build before relying on this.

### Phase 10: Hardware & Testing (3-5 days)
- [ ] Real RFID reader integration
- [ ] USB protocol optimization
- [ ] Stress testing (100+ scans)
- [ ] Battery impact assessment
- [ ] End-to-end testing
- [ ] Beta release

## API Integration

**Backend**: Laravel at `https://manhaje.com/apps/api`

### Key Endpoints

| Endpoint | Purpose |
|----------|---------|
| `POST /login` | Email + password → bearer token |
| `POST /me` | Validate token, fetch user |
| `POST /teacher_attendance_roster` | Get class roster |
| `POST /teacher_attendance_submit` | Batch submit attendance |
| `POST /teacher_attendance_scan` | Single student scan |
| `POST /admin_attendance_status_list` | Get status configs |
| `POST /admin_classes_list` | Admin: every class in the school |
| `POST /admin_sections_list` | Admin: a class's sections |
| `POST /admin_section_students` | Admin: a section's students |
| `POST /admin_gate_attendance_scan` | Admin: campus-wide gate in/out scan (any student) |
| `POST /admin_gate_attendance_today` | Admin: today's campus-wide gate activity list |

### Response Format

The block below is the spec document's claimed format. **It is not what the
real backend actually returns** - see "Confirmed against the real backend"
further down for the real shapes, endpoint by endpoint, read directly from
the Laravel controller source rather than inferred. `ApiEnvelopeTypeAdapterFactory`
exists specifically because of this gap: it tolerates a 2xx body that omits
`success` entirely, and reads the payload from the top level when there's no
`data` key, so both this claimed shape and the real one parse correctly.
Kept here only so a future reader knows what was originally assumed and why
the parsing code looks the way it does.

```json
{
  "success": true,
  "data": { /* payload */ },
  "meta": { "page": 1, "total": 100 },
  "message": "Success"
}
```

Token stored in Android Keystore, never in SharedPreferences.

## Database Schema

### Core Tables

**students**
- id (PK), school_id, student_id, name, code, email, photo_url, gender, section_id, rfid_card_number, last_synced_at, is_active, is_local_only, created_at, updated_at
  (`is_local_only` is app-only, not in the backend spec's schema - marks a row added via the on-device "Add Student" dialog, which has no backend counterpart to sync to)

**attendance**
- id (PK), school_id, section_id, subject_id, student_id, scan_date, status, check_in_time, rfid_uid, verified_by_rfid, verified_by_face, face_match_score, manual_override, sync_status, sync_attempts, last_sync_at, idempotency_key, server_attendance_id, error_message, created_at, updated_at

**face_templates**
- id (PK), school_id, student_id, encrypted_embedding (BLOB), encryption_version, enrolled_at, enrolled_by, liveness_score, is_active, created_at, updated_at

**sync_queue**
- id (PK), school_id, action_type, payload (JSON), status, retry_count, last_retry_at, next_retry_at, error_message, idempotency_key, server_response, created_at, updated_at

**audit_logs**
- id (PK), school_id, action, entity_type, entity_id, user_id, details, status, error_message, ip_address, device_id, created_at

### Indices
- `idx_students_school_rfid` on students(school_id, rfid_card_number)
- `idx_students_code` on students(code)
- `idx_attendance_date` on attendance(scan_date)
- `idx_attendance_status` on attendance(sync_status)
- `idx_sync_status` on sync_queue(status)
- `idx_sync_next_retry` on sync_queue(next_retry_at)

## Security Considerations

### Authentication
- Bearer token stored in Android Keystore (not SharedPreferences)
- Token validated on app startup
- Automatic token refresh when expired
- 2FA support (optional from backend)

### Data Encryption
- Face templates: AES-256 GCM encryption at rest
- RFID UIDs: AES-256 encryption
- Audit logs: Encrypted before storage
- All API calls: HTTPS only

### Biometric Privacy
- No photo storage (temporary only)
- Store only encrypted embeddings
- Delete failed verification attempts after 24 hours
- Complete audit trail of all verifications

### Code Security
- No hardcoded API keys/secrets
- ProGuard/R8 obfuscation on release
- Regular dependency scanning
- Certificate pinning for API

## Testing Strategy

### Unit Tests
- Database operations
- API client parsing
- Encryption/decryption
- Retry logic
- Date/time utilities

### Integration Tests
- API client with mocked responses
- Database migrations
- Offline queue and sync
- Token refresh flow

### UI Tests
- Compose component rendering
- Navigation flow
- User interactions
- Accessibility

### E2E Scenarios
- Offline scan → sync flow
- Network failure handling
- Duplicate detection
- Conflict resolution

## GitHub Actions Workflow

**Trigger**: Every push to any branch

**Jobs**:
1. **build**: Compiles APK (debug + release), uploads artifacts
2. **lint**: Runs linter, uploads reports

**Artifacts**:
- APK files (30 days retention)
- Test reports
- Lint results

## Gradle Commands

```bash
# Clean build
./gradlew clean build

# Run unit tests
./gradlew test

# Run instrumentation tests (requires emulator/device)
./gradlew connectedAndroidTest

# Check lint
./gradlew lint

# Build APK
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK

# Run app
./gradlew installDebug
adb shell am start -n com.muslimedu.attendance/.MainActivity
```

## Dependencies Management

All versions centralized in `gradle/libs.versions.toml`:
- AndroidX + Jetpack: Core, Lifecycle, Compose, Room, WorkManager, Hilt
- Networking: Retrofit, OkHttp, Gson
- ML: ML Kit Face Detection, TensorFlow Lite (runs the bundled MobileFaceNet model)
- Camera: CameraX (core, camera2, lifecycle, view) - embedded live capture, see `LiveFaceCaptureView`
- Security: Tink, Android Keystore
- Testing: JUnit, Espresso, Compose Test

To add a dependency:
1. Add version to `libs.versions.toml`
2. Create alias in `[libraries]` section
3. Reference in `app/build.gradle.kts`: `implementation(libs.your.alias)`

## Common Issues & Solutions

### Build fails on first run
- Ensure Java 17 is installed: `java -version`
- Clear Gradle cache: `./gradlew cleanBuildCache`
- Sync Gradle: `./gradlew sync`

### USB device not recognized
- Check device vendor/product IDs
- Update `device_filter.xml` with correct IDs
- Request USB permission at runtime (Android 6.0+)

### Can't type the admin PIN / typing and taps feel laggy
Two separate causes, both fixed:

1. **The PIN field couldn't be typed in.** `MainActivity.dispatchKeyEvent`
   hands every key event to `KeyboardEmulationRfidReader` (USB readers
   "type" the card UID), and it consumed *every* letter/digit key-down from
   any source. Gboard's number pad (`KeyboardType.NumberPassword`) sends
   each digit as a key event rather than committed text, so the reader ate
   them all. Now the reader ignores on-screen/virtual keyboard input
   (`isSoftKeyboardInput`: `VIRTUAL_KEYBOARD` device, `FLAG_SOFT_KEYBOARD`,
   or a virtual `InputDevice`), and the activity doesn't offer keys to the
   reader at all while a text field is focused
   (`currentFocus.onCheckIsTextEditor()`). The gate and card screens have no
   text fields, so the reader still works there.
2. **Lag everywhere** came from running the *debug* APK: the release build
   had no signing config, so CI's release APK was unsigned and couldn't be
   installed. Debuggable apps skip ART's optimizations and Compose's
   baseline profiles, and on a budget phone typing/taps visibly lag. The
   release build is now signed with the debug key (sideloading only - use a
   real key before any store) and CI uploads it as
   `apk-release-install-this`. Install that one on the gate phone.

   CI creates a fresh debug key on every run, so each new APK is signed
   differently and won't install over the previous one without an
   uninstall - which wipes the device's cards, faces and unsynced scans.
   A fixed signing key (stored as a GitHub secret) would fix that; not set
   up yet.

### Plugging in the reader throws you back to the dashboard
Symptom: on Assign RFID Card (now the wizard's card step, "Tap <name>'s card on the reader"), plugging
in the USB reader jumped straight to the gate dashboard, so the card could
never be assigned. A keyboard-emulation reader *is* a USB keyboard, and
attaching one is a `keyboard`/`keyboardHidden`/`navigation` configuration
change - `MainActivity` didn't declare those, so Android destroyed and
recreated it, and `AppRoot`'s screen state (plain `remember`) restarted at
`Screen.Gate`. Fixed: the activity declares
`configChanges="keyboard|keyboardHidden|navigation"` (Compose handles them,
nothing is recreated), and the current screen is `rememberSaveable` so any
other recreation (rotation, theme) keeps the admin where they were. The
"Tap card" step also shows the reader's status now.

### Scanning a real card does nothing
There is no simulated reader in any build any more (`MockRfidReader` and
Simulate Scan were removed) - only the keyboard-emulation and raw-USB
readers in `RfidReaderFactory`.

Things that used to produce a silent no-op, all fixed:
- Keyboard-emulation readers that type the UID as **hex** were ignored, because
  only digit keycodes were accepted. `KeyboardEmulationRfidReader` now takes
  any letter or digit via `unicodeChar`, ends on Enter **or** Tab, and starts a
  new UID after a 500ms gap so a partial read can't glue onto the next card.
- A reader plugged in *after* launch was never picked up: the raw-USB reader
  resolves its device once, when scanning starts. `RfidManager` now restarts
  the readers on a USB attach/detach broadcast.

The reader indicator on the scan screen is driven by `RfidManager.status`,
which reflects what is actually plugged into the USB port (filtered to HID /
vendor-specific / CCID devices, so a hub or charger doesn't light it up) rather
than whether a scan has arrived.

### Face recognition not working
- Ensure camera permission is granted
- Check ML Kit model downloaded
- Verify device supports ML Kit

### The face camera shows the ceiling and never captures
Symptom: "Enroll Face" (and the attendance flow's face-verification step, which
shares the same component) shows a live but badly-framed camera - you see the
wall or ceiling, with your face cropped to the very edge - and the status line
sits on "Position your face in the frame" forever. Nothing ever captures.

Two independent bugs, both in `LiveFaceCaptureView`, both fixed:

1. **The auto-capture gate could never open.** The fast ML Kit detector was
   built without `setLandmarkMode(LANDMARK_MODE_ALL)`, so every frame scored
   `LivenessDetector`'s no-landmarks floor of 0.3 against a 0.9 gate. Not a
   tuning problem, not lighting - arithmetically impossible on every device.
   See the "That fast detector must keep `LANDMARK_MODE_ALL`" note above.
   This had been masked by the manual "Capture Now" button until that button
   was removed, at which point the screen became a dead end.
2. **The preview was laid out at its own natural size, not the frame's.** The
   `PreviewView` was created with no `LayoutParams` and added via
   `AndroidView(modifier = Modifier.fillMaxWidth())` inside a 3:4 `Box` -
   width filled, height left to wrap content. What showed was an off-centre
   slice of the feed. Fixed with `MATCH_PARENT` layout params +
   `ScaleType.FILL_CENTER` on the view and `Modifier.matchParentSize()` on
   the `AndroidView`.

Also set explicitly while in here: `setTargetRotation()` on both the `Preview`
and `ImageAnalysis` use cases, from the view's own display rather than
whatever the display rotation happened to be when the use case was built - a
frame rotated the wrong way is a face ML Kit is much less likely to find.

Still device-untestable in this project's build sandbox (no emulator with a
camera), so if capture still misbehaves on real hardware, the things to reach
for first are `AutoCaptureGate`'s `REQUIRED_FRAMES`/`MIN_FRAME_INTERVAL_MS`/
`FALLBACK_AFTER_MS` constants, and the liveness threshold on the Settings
screen (default 0.7, which in practice demands the accurate detector also
report its top 0.9 tier - lower it if a dim classroom is failing enrollments).

### Sync failures
- Check network connectivity
- Verify API token is valid
- Review sync queue logs in database
- The Admin Dashboard now shows the *actual* error message(s) behind a
  nonzero "Failed" count, not just the count (`AttendanceDao.getFailedMessagesOnDate`,
  `AdminStats.failedMessages`) - read that first instead of guessing.

### "Failed: 1" on the Admin Dashboard that never clears, even after "Retry Failed Syncs"
Root cause: the built-in sample/demo roster (`StudentRepository.seedSampleDataIfEmpty` -
three fake students, "Mohammed Ahmed"/"Fatima Al-Zahra"/"Yusuf Ibrahim", used
automatically before any real `/teacher_attendance_roster` sync has ever
happened) was never marked `isLocalOnly = true`. `SyncQueueManager` only ever
skipped syncing a student it detected by `student_id <= 0` (the convention
for a student added via the admin "Add Student" dialog) - the sample data's
`student_id` is 1/2/3, a small *positive* int, so that check let it straight
through to the real backend. Since no real school has a student enrolled
under the fabricated code `"STU001"`, every attendance scan against this demo
data failed on the real server, permanently, every single time - "Retry
Failed Syncs" just re-sent the same doomed request and failed again.

Fixed two ways:
- The sample data is now seeded with `isLocalOnly = true`, and
  `SyncQueueManager.syncOne()` checks that flag directly (having already
  looked up the student to get its `code` for `/teacher_attendance_scan`)
  instead of inferring "not a real backend student" from the sign of
  `student_id` - a check that correctly caught the admin's added-locally
  students but never covered this equally-not-real demo data.
- Since `seedSampleDataIfEmpty()` only ever seeds once (`if (studentDao.count() > 0) return`),
  fixing the entity default doesn't retroactively fix an install where these
  rows were already seeded before this flag existed - like a device that had
  already hit this exact bug. `StudentDao.markLocalOnlyByCode()` runs
  unconditionally on every app start specifically to backfill that: an
  idempotent `UPDATE ... WHERE code IN ('STU001','STU002','STU003') AND
  is_local_only = 0`.

Also added a `403` case to `SyncQueueManager`'s error handling for
`teacher_attendance_scan`'s "You are not assigned to take attendance for this
class" response (`assertTeacherAssignedToSection` on the backend) - this
fires whenever an attendance row's `section_id`/`subject_id` don't match any
class the logged-in teacher is actually scheduled for, most commonly because
no roster was ever synced (`AttendanceRepository.recordScan` falls back to
`section_id` from the student's own cached row and `subject_id = 0` when
`SessionManager.activeClass` is null). This is now marked permanently failed
immediately with a message pointing at "Sync Roster from Server", instead of
silently burning through 8 retries with exponential backoff for something no
retry could ever fix.

### A student added on the backend never shows up in the app
Symptom: an admin adds a new student on the school's website, but that
student never appears in the app's roster/scan flow, or in the Student List -
not even after logging out and back in from inside the app.

Root cause: there was no way to re-sync the roster once the app's initial
sync had already completed. `RosterViewModel.loadClasses()` (which fetches
`/teacher_attendance_classes` and syncs `/teacher_attendance_roster` for the
chosen class into the local `StudentDao` cache) only ever ran once, from
`init {}` - and this ViewModel outlives navigating between screens, since
there's no back stack behind it. Once its state reached `Ready`, nothing in
the app ever called `loadClasses()` (or anything else that touches the
network) again for the rest of that process's lifetime. **Logging out and
back in doesn't help either** - `AuthViewModel.logout()` clears the session
but doesn't kill the process, so the same long-lived `RosterViewModel`
instance is still sitting there in `Ready`, having never been asked to
re-sync. The only thing that ever worked was force-closing the app.

`StudentListScreen` had a second, smaller version of the same bug:
`StudentListViewModel.refresh()` only ever ran from `init {}` too, so even a
roster sync that *did* happen elsewhere wouldn't be reflected there without
navigating away and letting the whole app (not just this screen) restart.

Fixed two ways:
- Added `RosterViewModel.resyncRoster()`, wired to a new "Sync Roster from
  Server" button on `AdminDashboardScreen` (`AppRoot` hoists the single
  `RosterViewModel` instance up to share it between the Scan screen and the
  dashboard, rather than each grabbing its own via `hiltViewModel()`). Skips
  the class picker and re-syncs whichever class is already active, so a
  multi-class teacher doesn't get bounced back to "Select a Class" just for
  tapping refresh; falls back to `loadClasses()` only if no class was ever
  made active (i.e. the teacher chose "Continue Offline").
- `StudentListScreen` now calls `viewModel.refresh()` from a `LaunchedEffect(Unit)`
  on every visit, not just the first, so it always reflects whatever's
  currently in `StudentDao` - including a roster sync that just ran from the
  dashboard.

Still worth knowing even after this fix: `RosterRepository.syncRoster` is a
merge, not a full reconciliation (see its own doc comment) - a student
*removed* from the backend roster is never removed from the local cache by a
sync. Only additions and field updates propagate.

### Attendance recorded on-device but never appears on the web dashboard
Symptom: the scan screen shows "Present - recorded" for every student with
no error at all, but checking the school's website afterward, only the
*first* student scanned in a class period actually shows up - everyone
after them is just missing, silently.

Root cause, found by reading `AttendanceApi::teacher_attendance_submit()` and
`AttendanceService::lockRoster()` directly (not documented anywhere in the
spec): that endpoint - which `SyncQueueManager` was calling once per scan,
since it accepts a `records` array and looked like the right fit - **locks
the whole `(section_id, subject_id, date)` roster the first time it's called
for that combination**, via an automatic `lockRoster()` call right after
every successful submit. Every subsequent call for the *same* combination -
i.e. every next student's scan in the same class period - hits the early
lock check and gets back HTTP 423 "This attendance has already been
submitted and locked" instead of saving anything. `SyncQueueManager` had no
case for 423, so it fell into the generic retry-with-backoff branch, retried
up to `RetryStrategy.MAX_ATTEMPTS`, then gave up and marked the row
permanently failed - visible only as a "Failed" count on the Admin
Dashboard, which a teacher mid-class has no reason to check. This endpoint
is designed for the web admin's one-shot "take the whole roster's attendance
and finalize it" form, not a stream of individual scans - locking on submit
is the *correct* behavior there.

The backend has a second, separate endpoint for exactly this app's actual
use case: `AttendanceApi::teacher_attendance_scan()`. Its own doc comment
says it outright - "one call per scan, not a batch" - and it calls
`AttendanceService::markAttendance()` directly with no lock check at all, so
any number of separate calls for the same roster each succeed independently.
Fixed by switching `SyncQueueManager`/`ApiService` to call
`/teacher_attendance_scan` (see `AttendanceScanDto.kt`) instead of
`/teacher_attendance_submit` (kept around, see `AttendanceSubmitDto.kt`, only
for a possible future "finalize the day's roster" action - the one case
where locking-on-submit is actually wanted).

Two real trade-offs from this switch, both because `teacher_attendance_scan`
takes a smaller set of fields than `teacher_attendance_submit` did:
- It resolves the student by `code`, not `student_id` - `SyncQueueManager`
  now looks up the matched student's `code` from `StudentDao` at sync time.
- `status` is always forced to `'present'` and `check_in_time` is always the
  *server's* clock at the moment the sync request arrives - there's no way
  to pass either explicitly. A scan made while offline and synced minutes
  (or hours) later gets stamped with the sync time on the backend, not the
  real scan time; the app's own local `AttendanceEntity.checkInTime` still
  keeps the true scan time, this only affects what the web dashboard shows
  after a delayed sync.
- `source` is hardcoded to `'qr'` server-side (no `source` field exists on
  this endpoint) - every RFID-triggered scan is recorded on the backend as
  if it were a QR scan. The app's own `verified_by_rfid`/`verified_by_face`
  columns still record the real method locally; this only affects the
  backend's own `source` column.

Covered by `RealBackendResponseShapeTest`'s
`teacher_attendance_scan returns one resolved student...` test, pinned to
the endpoint's real response shape (which, unlike submit's, actually
includes a real `attendance_id` - `serverAttendanceId` can now be populated
instead of always staying null).

### An API call fails even though the server accepted it
Symptom: the login screen showed the server's own "Login successful" message
as a red error and never navigated away.

The backend does not use the documented `{success, data, message}` wrapper as
consistently as the spec implies, so reflective parsing produced
`success = false` / `data = null` on a response that was actually fine.
`ApiEnvelopeTypeAdapterFactory` now parses every `ApiEnvelope<T>`: a 2xx body
counts as a success unless it explicitly says otherwise (`success` or `status`
saying false/error), and the payload is read from `data` when that key is
present and from the top-level object when it isn't. Covered by
`ApiEnvelopeParsingTest`, which runs in CI as part of `./gradlew build`.

Two related rules when adding DTOs:
- Gson leaves an absent field `null` no matter what Kotlin declares, so a
  payload field the app dereferences should be nullable and checked (see
  `LoginData.user`, `RosterData.students`) rather than trusted and crashed on.
- Requests carry `Accept: application/json`; without it Laravel renders errors
  as HTML instead of the JSON error envelope this app parses.

### "Student no longer in local cache" on demo/sample-data scans, even though the student is right there

Symptom: scanning one of the built-in sample students ("Mohammed Ahmed"/
"Fatima Al-Zahra"/"Yusuf Ibrahim") shows "Present - recorded" like any other
scan, but the Admin Dashboard's "Failed" count goes up with the message
"Student no longer in local cache" - even though that exact student is still
sitting right there in the Student List with their RFID card assigned.

Root cause: `StudentRepository.seedSampleDataIfEmpty()`'s three demo rows are
hardcoded to `schoolId = 1`, regardless of which real school the logged-in
admin actually belongs to. `AttendanceRepository.recordScan()` used to stamp
every new scan with `sessionManager.currentUser.value?.schoolId ?: student.schoolId`
- preferring the *session's* real school_id over the *scanned student's own*.
For a real roster-synced student those two already agree (their `schoolId`
was set from the session at sync time in the first place), so this was
invisible there - but for the demo data it isn't: the recorded attendance row
ended up filed under the real admin's school_id (say, 47), not the `1` the
demo student's own row actually lives under. `SyncQueueManager.syncOne()`
then looks the student up via `findBySchoolAndStudentId(record.schoolId,
record.studentId)` - keyed on that wrong school_id 47 - which no demo row is
ever filed under, so the lookup came back null and it gave up permanently,
never even reaching the `isLocalOnly` check that was specifically built to
catch demo data (see "'Failed: 1' on the Admin Dashboard that never clears"
below) - that check never gets a chance to run if the student lookup itself
fails first.

Fixed two ways:
- `AttendanceRepository.recordScan()` now always uses `student.schoolId`
  directly, dropping the session preference entirely - a no-op change for a
  real student (the two values already matched), and the actual fix for demo
  data (guarantees the attendance row's school_id always matches whatever
  school_id the looked-up student row carries, since that's the only thing
  this is ever looked up against again).
- `AttendanceDao.repairDemoAttendanceSchoolId()` - keyed on the three demo
  students' own hardcoded `rfid_uid` values (`04:1A:2B:3C`/`04:5D:6E:7F`/
  `09:AA:BB:CC`), not `student_id` alone, since a real school's first
  enrolled students could plausibly have server `student_id` 1/2/3 and this
  must never touch their real attendance data - corrects any already-stuck
  row's `school_id` back to `1` and resets it to pending. Runs unconditionally
  from `StudentRepository.seedSampleDataIfEmpty()` on every app start, same
  pattern as `StudentDao.markLocalOnlyByCode()` right above it - a no-op once
  already corrected, but repairs an install that hit this bug before the fix
  existed.

### Login restricted to teacher/admin

Symptom this closes: any account with valid credentials could log into this
app, regardless of role - the backend's own `/login` allows far more roles
than this app has any UI for (`ApiController::login()`'s `$allowedRoles`:
`superadmin`, `admin`, `teacher`, `accountant`, `librarian`, `parent`,
`student`, `alumni`, `warden`, `sponsor`, `registrar`, `platform_staff`).
A parent or librarian logging in would land straight on the RFID scan
screen with no permission model behind them at all - `AppRoot` only ever
branches on whether the role is admin-equivalent (for the Admin Dashboard
icon), never on whether the role belongs in this app to begin with.

Fixed in `AuthRepository`, not `AppRoot`: `ALLOWED_APP_ROLES = setOf("teacher",
"admin", "superadmin")`, checked in both `login()` (a disallowed role never
gets its token saved at all - not saved-then-discarded) and
`validateSession()` (catches a token saved by an older build before this
existed, or a role changed on the backend since the last login - both clear
the token and force back to the login screen). `superadmin` is allowed
alongside `admin` to match `AppRoot`'s existing `ADMIN_ROLES`, which already
treats the two the same for Admin Dashboard visibility - even though a
superadmin will still hit a real 403 on the three Browse-by-Class endpoints
specifically (see "Admin: Browse by Class" below), since the backend's
`requireAdmin()` checks `role_id === 2` and nothing else.

### Gate In/Out Attendance (campus-wide, admin-only)

Originally investigated and found not buildable without backend changes
(see git history for the full "Investigated and not built" writeup this
section replaces) - the real backend had no gate/check-in/check-out concept
anywhere, and every existing attendance endpoint gated on `requireTeacher()`
(`role_id === 3`), which rejects an admin account outright regardless of
target. Once the user supplied their live Laravel backend source directly,
two new endpoints were added there (same source files as the rest of
"Confirmed against the real backend" below - read before writing any code,
not guessed at):

- **`POST /admin_gate_attendance_scan`** - `code` (required), `direction`
  (`"in"`/`"out"`, required), `date` (optional, defaults to today). Gated on
  `requireAdmin()` (`role_id === 2`), matching the "only admin has that
  feature" requirement. Resolves the student by `code` across the **whole
  school** (`role_id = 7`, `status = 1`) - not scoped to one section's
  enrollment the way `teacher_attendance_scan` is - then looks up that
  student's current enrollment purely to satisfy the `attendances` table's
  NOT NULL `class_id`/`section_id` columns (gate attendance isn't really
  "about" a class, but the schema still requires one).
- **`POST /admin_gate_attendance_today`** - `date` (optional, defaults to
  today). Every gate scan recorded school-wide for that date - the "who's
  on/off campus" list, satisfying the "all student list" part of the
  request. Also gated on `requireAdmin()`.

**Storage, without a migration**: a gate scan is a normal row in the
existing `attendances` table, under a new sentinel
`Attendance::GATE_SUBJECT_ID = 999999999` (same trick as the existing
`HOMEROOM_SUBJECT_ID = 0`) - since the table's unique key is `(student_id,
date, subject_id)`, this keeps a gate scan as its own row per day that can
never collide with, or overwrite, that student's real class/homeroom
attendance for the same day. Written through a new
`AttendanceService::markGateScan()` - deliberately **not** a call to the
existing `markAttendance()` with a different subject_id, because gate
attendance has no "status" to validate (being scanned at all means
present-on-campus) and, critically, a same-day "out" scan must not blow
away the day's original "in" time the way `markAttendance()`'s plain
`update()` would. `check_in_time` holds only the day's *first* "in" scan;
every event (in, out, or a repeat of either) is appended to
`device_meta.gate_events`, so a student who leaves and returns more than
once keeps a full history, not just the latest direction.

**App side**: `GateAttendanceRepository` (network-only, no offline queue -
see its own doc comment for why this is deliberately not offline-first
like the RFID scan flow) + `GateAttendanceViewModel` +
`GateAttendanceScreen`, reachable from the Admin Dashboard's new "Gate
In/Out Attendance (Campus-wide)" button. Tapping a card resolves it locally
via the same `StudentRepository.findByRfid()` the RFID enrollment screen
uses purely to recover a `code` to send - a card not locally cached doesn't
block the screen, since an admin can always type a student's `code`
manually (gate attendance is meant to cover any student in the school, not
just those already RFID-enrolled on this particular device). An In/Out
toggle selects `direction` before each scan; the result card shows the
student's name, the recorded time, and (once set) their first check-in
time for the day. Below that, a live "Today's Gate Activity" list
(`/admin_gate_attendance_today`) shows every student scanned so far today
with their last direction and time.

Backend files changed (all in the Laravel repo the user supplied, not this
one): `app/Models/Attendance.php` (new constants), `app/Services/
AttendanceService.php` (`markGateScan()`, `gateAttendanceForDate()`),
`app/Http/Controllers/Traits/AttendanceApi.php` (the two new controller
methods), `routes/api.php` (the two new routes, next to the other
`admin_attendance_*` ones).

### Admin: Browse by Class

A large ask arrived to rebuild much of this app - role-based dashboards,
an online/offline mode selector, a first-time data download flow, and an
admin Classes -> Students -> Details browsing hierarchy, among others. Given
the size of that (each individual piece is comparable to an entire existing
Phase above), it was scoped down rather than attempted all at once: the user
picked "Admin: Classes -> Students -> Details" as the first piece, since it's
the one confirmed buildable against real, existing backend endpoints without
guessing. The others weren't abandoned, just not started - see the git
history around this entry for the full scoping conversation.

Three endpoints ground this, all read directly from `ApiController.php`
before writing any DTO (same discipline as the rest of "Confirmed against the
real backend" below, which is what caught the check_in_time and `/me`
wrapper bugs earlier):

- **`admin_classes_list`** is a full school-SIS "Classes" listing (grade
  level, campus, curriculum, semester, shift, capacity, ...) - a much bigger,
  more generic concept than anything this app previously touched.
  `AdminClassSummaryDto` only declares the four fields actually shown
  (`id`, `name`, `grade_level`, `current_enrollment`); no search/filter/sort
  UI was built for the dozen-plus optional query params this endpoint
  accepts. Called with `per_page: 100` (the server's own cap) and no other
  filter, so a school with more classes than that would need real pagination
  UI added later.
- **`admin_sections_list`** (filtered by `class_id`) is what actually maps to
  a class's "Grade 5A"-style groups - the same `Section` concept
  `/teacher_attendance_roster` already rosters by by `section_id`. If a class
  has exactly one section, `AdminDirectoryViewModel.selectClass()` skips
  straight to that section's students, matching the same "skip the picker
  when there's only one choice" convention `RosterViewModel.loadClasses()`
  already uses for a teacher's own classes.
- **`admin_section_students`** returns each student's `id`/`name`/`email`/
  `photo`/`phone`/`gender` - and critically, **no `code` field at all**,
  unlike `/teacher_attendance_roster`'s student rows. `code` is what
  `StudentEntity` requires (non-null, unique-indexed) and what
  `/teacher_attendance_scan` actually sends to identify a student
  server-side, so a student who only shows up via this admin directory has
  nothing valid this app could write into `StudentDao` for them.
  `AdminDirectoryRepository` is therefore deliberately read-only and never
  touches that table - the RFID/face actions in the student detail screen
  only light up for a student who's already locally cached from a real
  teacher roster sync (`row.localEntity != null`); otherwise the screen says
  so and points at "Sync Roster from Server" instead of silently failing or
  writing a broken row.

All three endpoints gate on the real backend's `requireAdmin()`, which checks
`role_id === 2` **specifically** - not "admin or superadmin" the way this
app's own `AppRoot` gate (`ADMIN_ROLES = setOf("admin", "superadmin")`) that
decides who even sees the Admin Dashboard does. A superadmin can open the
dashboard and tap "Browse by Class" in this app, then get a 403 "This action
is restricted to admin accounts" from the real server - confirmed from
`ApiController::requireAdmin()`'s source, not guessed. That 403 surfaces as
a normal, readable error message (the same `ErrorContent` used for every
other failure here), not a crash.

The whole Classes/Sections/Students/Detail flow is one composable
(`AdminDirectoryScreen`) with its own internal back handling, not four
separate `AppRoot` destinations - `AppRoot`'s TopAppBar back arrow only ever
returns to the Scan screen (deliberately, per its own doc comment, "one level
below the scan screen" is as far as that back stack goes today), so a real
four-level drill-down needs its own in-screen back affordance regardless of
how many `Destination` entries it lives behind. Follows the same convention
`RfidEnrollmentScreen`/`FaceEnrollmentScreen` already use for their own
internal Picker/Listening/Result steps.

Covered by three new `RealBackendResponseShapeTest` cases, each pinned to a
literal copy of that endpoint's real response body.

### Confirmed against the real backend (the spec document was wrong in several places)

The user supplied the actual Laravel source (`routes/api.php`, `app/Http/
Controllers/ApiController.php` + `Traits/AttendanceApi.php`, `app/Services/
AttendanceService.php`) for the first time. Every DTO/response shape below was
read directly from that code, not inferred - the earlier version of each was a
guess against the spec document, and the two disagree in every case listed
here. `RealBackendResponseShapeTest` pins each of these shapes down with a
literal copy of the real response body, and runs in CI.

- **`check_in_time` format was the single biggest bug**: the backend validates
  it as `date_format:H:i` (`"14:35"`, no seconds).
  `AttendanceRepository.recordScan()` was formatting `"HH:mm:ss"`. Every
  attendance submit was therefore failing Laravel's format validation with a
  422, which `SyncQueueManager` correctly marked permanently failed - meaning
  no scan had ever actually reached the real backend, regardless of how
  correct the rest of the sync pipeline was. Fixed by dropping the `:ss`.

- **There is no RFID field or endpoint on the backend at all.** The only
  student identifier the backend knows is `code` (matched via
  `/teacher_attendance_scan`, which resolves a QR/ID-card code string to a
  student and marks them present in one call - not by matching a `student_id`
  the app already resolved locally). `attendance_method_configs`
  (`AttendanceConfigController`) documents RFID/NFC/barcode as an intended
  *future* per-school capture method, but nothing currently reads or writes
  such a value. Consequently:
  - RFID card-to-student assignment (`StudentRepository.assignRfidCard`)
    stays exactly what it already was: local-only, matched against the
    locally-cached `code`, never sent anywhere.
  - The *attendance event* an RFID scan produces syncs through
    `/teacher_attendance_scan`, using the matched student's `code` (looked up
    from `StudentDao` at sync time) - **not** `/teacher_attendance_submit`,
    even though that one also exists and would seem like the obvious fit for
    "send this scan". Submit locks the whole roster on its first successful
    call for a given (section, subject, date), which broke sync for every
    student after the first one in a class period - see "Attendance recorded
    on-device but never appears on the web dashboard" for the full story of
    that bug and why `/teacher_attendance_scan` is the correct endpoint here.
  - `/teacher_attendance_scan` hardcodes `source` to `'qr'` server-side (no
    `source` field in its validation rules at all) - an RFID-triggered scan
    is therefore recorded on the backend as if it were a QR scan. The app's
    own `verified_by_rfid`/`verified_by_face` columns still record the real
    method locally. **If a real `rfid` source value is ever added to this
    endpoint on the backend, update `SyncQueueManager.syncOne()`'s request**
    - that's the one place this app would need to change.

- **`/teacher_attendance_classes`** returns `{"classes": [...]}` - a flat
  list, each entry carrying `section_id`, `section_name`, `class_id`,
  `class_name`, `subject_id`, `subject_name`, `role` ("homeroom" or
  "subject") directly - not `{"sections": [...]}` with a nested `subject`
  object as the spec implied. `subject_id` is never missing: a homeroom
  entry carries the school's homeroom sentinel (`0`), so there is no "this
  class has no subject" case to guard against.

- **`/teacher_attendance_roster`** returns `section_id`/`section_name`/
  `subject_id`/`date` at the top level (not nested under a `section` object),
  plus `students[]`, `summary` (a flat `{status_code: count}` map), and
  `locked`/`locked_at`/`locked_by_name` (a roster locks once submitted; an
  admin can unlock it - `admin_attendance_unlock` - but this app doesn't
  surface locked state in its UI yet). Each student row is `student_id`,
  `student_name` (not `name`), `code`, `photo` (a real absolute URL), `address`,
  `gender`, `age`, plus that day's already-recorded `status`/`check_in_time`/
  `remarks`/`attendance_id` if any - never an RFID field, see above.
  **Re-syncing a roster must carry forward any already-assigned
  `rfidCardNumber` and the existing row's id** for a student already in the
  local cache (`RosterRepository.syncRoster` does this via
  `StudentDao.findBySchoolAndStudentId`) - the server has nothing to send
  back for that column, so a naive upsert would silently erase every
  previously-assigned card the next time the teacher's roster synced.

- **`/teacher_attendance_submit`**'s response is `{message, summary, count,
  locked, locked_at}` - there is no `submitted`/`skipped`/`attendance_ids`.
  That `locked: true` is not incidental: this endpoint locks the roster on
  its first successful call for a (section, subject, date), which is why
  `SyncQueueManager` no longer calls it per-scan - see "Attendance recorded
  on-device but never appears on the web dashboard". `/teacher_attendance_scan`
  is what it calls instead, and its response *does* carry a real
  `attendance_id` (`AttendanceScanDto.kt`), so `serverAttendanceId` is
  actually populated now rather than always staying null. The backend never
  returns 409 for a duplicate either - confirmed from
  `AttendanceService::markAttendance`, which always upserts by `(student_id,
  date, subject_id)` - not something either endpoint's client code relies on.

- **`/me`'s response is `{"user": {...}}`** - a single wrapper key, not the
  user's fields directly at the top level (unlike `/login`, whose `user`/
  `token` really are top-level siblings). Parsing it as a bare `UserDto`
  (what an earlier version of this DTO did) doesn't throw - Gson just builds
  a `UserDto` with every field at its default (`id=0`, every string null)
  from an object that has none of those keys, so a session-restore silently
  "succeeded" into garbage. Fixed with a `MeData(user: UserDto)` wrapper DTO.

- **`UserDto` had a `school_name` field that never existed on the backend**
  (only `school_code` does) - always parsed to null, and nothing displayed
  it. Removed.

- **Student photos are cached locally by `StudentPhotoCache`, downloaded once
  per student and never uploaded anywhere** - there is no upload endpoint for
  one to reach even if the app tried. `RosterRepository.syncRoster()` kicks
  off a best-effort background download per roster row after every sync;
  `StudentPhotoCache` skips the network entirely once a copy is already
  cached under the app's private storage
  (`filesDir/student_photos/<schoolId>/<studentId>.jpg`), so it stays fully
  usable offline after the first sync. Wired into `StudentListScreen`'s row
  (a circular photo replaces the placeholder icon once cached).

- **`/refresh-token` is not a real route** - confirmed absent from
  `routes/api.php`. `ErrorInterceptor.attemptRefresh()` calling it always
  404s, which is handled gracefully already (falls through to clearing the
  token and returning the original 401), so this isn't a functional bug, just
  dead code kept in case a real refresh endpoint is added later.

## Performance Targets

| Operation | Target |
|-----------|--------|
| RFID scan → display | < 1s |
| Face capture + embedding | < 5s |
| Face verification | < 2s |
| Local save | < 500ms |
| App startup | < 30s |
| DB query (1000 rows) | < 100ms |
| Sync 50 records | < 10s |

## Git Workflow

```bash
# Create feature branch
git checkout -b feature/your-feature

# Make changes, commit with descriptive message
git commit -am "Add feature description"

# Push to origin
git push -u origin feature/your-feature

# Create Pull Request on GitHub
# Link to issues, add tests, request review

# After approval, merge and delete branch
git checkout main
git pull origin main
git branch -d feature/your-feature
```

## Resources

- [Spec Document](./RFID_ATTENDANCE_SYSTEM_COMPLETE_SPEC.md) - Complete system specification
- [Android Documentation](https://developer.android.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [Retrofit](https://square.github.io/retrofit/)
- [Hilt](https://dagger.dev/hilt/)
- [ML Kit](https://developers.google.com/ml-kit)

## Support & Questions

- GitHub Issues: https://github.com/manhajed/Attendance/issues
- Code Review: Create PR and tag maintainers
- Questions: Refer to specification document or Android docs

---

**Last Updated**: 2026-09-25  
**Created by**: Claude Code  
**Status**: Active Development
