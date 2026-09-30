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

- **Gate Schedule - set before the gate can be used** (Admin > Gate
  Schedule, `GateScheduleScreen`; `DeviceSettings.gateSchedule` =
  `GateScheduleConfig(perDay, inTimes, outTimes)`, null until set):
  **Morning only** (1 In + 1 Out), **Whole day** (2 + 2, out and back for
  lunch) or Custom (3-4), plus an **opening time for every Coming In and
  Going Out** (defaults: 6:00 / 11:00; whole day 6:00, 11:30, 12:30, 16:00;
  must run In 1 < Out 1 < In 2 ...; edited with the system time picker).
  Until it's set the dashboard shows a "Set up the gate first" card and
  Coming In / Going Out lead to it (via the admin PIN - `AppRoot.afterUnlock`
  - then back to the gate). Changes are in the Audit Log
  (`gate_schedule_set`). The user asked for this because schools differ
  (whole day 2 in / 2 out vs morning class 1 in / 1 out), and for Going Out
  to stay hidden until its time and then appear by itself.
  - Dashboard: a direction before its first opening time shows as a locked
    "Opens at 11:00 AM" card; `GateDashboardViewModel.now` ticks every 20s
    so it unlocks on its own.
  - Per student: scan N of a direction is refused before its time
    (`GateScanCheck.NotOpenYet`, e.g. back from lunch before Coming In 2
    opens).
  - **Late after** (the late-arrival flag, the user's next phase after
    parent texts): each Coming In has a "Late after" time or none
    (`GateScheduleConfig.lateAfter`, a switch + time under each Coming In
    on the Gate Schedule screen; must be from its opening and before its
    Going Out). A Coming In scanned after it is recorded as **Late** with
    whole minutes late (`GateSchedule.minutesLate`: late after 7:30 means
    7:30 is on time, 7:31 is 1 min). New schedules start with defaults
    (first Coming In +90 min: 6:00 -> 7:30; later ones +30 min: 12:30 ->
    1:00 PM). **A schedule saved before this has no late check at all**
    until the admin sets one - a default time the school never chose could
    flag students (and text parents) wrongly. Stored as
    `gate_late_after` ("07:30,-") in `DeviceSettings`.
  - **Not arrived alert** (the user's pick after the signing key): a
    card under the times on the same screen - on/off, "Not arrived by"
    time, "Text parents", school days (M T W T F S S circles). Unlike the
    rest of the screen it is **kept on the server** (the server sends those
    texts): loaded on open (`GateScheduleViewModel.loadAbsence`,
    `admin_gate_absence_settings`), saved after the schedule
    (`saveAbsence` -> `admin_gate_absence_settings_update`); offline or an
    old server shows why and the schedule still saves on the phone ("Gate
    schedule saved. The not-arrived alert wasn't saved: ..."). Default time
    = first Late after + 60 min, else first Coming In + 3 h (6:00 -> 9:00),
    always before Going Out 1 (`GateSchedule.defaultAbsenceCutoff`); it
    must be after Coming In 1 opens, after its Late after, and before Going
    Out 1 (`absenceCutoffProblem`). Parent SMS gets a fourth message, **Not
    arrived** ({time} = the cutoff; default "{school}: Ang inyong anak na si
    {student} ay hindi pa pumapasok sa paaralan hanggang {time} ({date})."),
    hidden until the server has `absent_template`.
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
  - **Gate schedule** (`GateSchedule.check`, before the face step, nothing
    saved when refused): scans alternate Coming In / Going Out, up to the
    admin's number per day each way. Same direction as the student's last
    scan today -> "Already Coming In ... next scan is Going Out" (this also
    covers a double tap - it replaced the old 60s duplicate window); one
    more than the day allows -> "No more Coming In today". Going Out is
    allowed with no Coming In first (a forgotten scan-in). Re-checked when
    saving (`recordConfirmed`). Per device: another gate device's scans
    aren't counted.
  - **Face step is full screen** (the user's mockup, in the app's theme):
    `LiveFaceCaptureView(fullScreen = true)` puts the camera under
    `FaceScanOverlay` - dimmed surroundings, dashed oval guide, corner
    brackets, and once a face is in view a monochrome face-mesh web with a
    sweeping scan line and a progress ring (a drawn pattern, not the
    detected landmarks). Student chip + Cancel at the top.
  - **Fully automatic, no buttons** (the user asked for no manual retry):
    a face that doesn't match is retried on the same running camera
    (`captureKey` re-arms the capture without rebinding CameraX) up to
    `MAX_FACE_ATTEMPTS` (3) within the 30s deadline; only when all fail is
    ONE rejected row saved. Every result (success, face failed, not
    enrolled, unknown card, schedule refusal) closes itself after
    `AUTO_CLOSE_MILLIS` (4s) with a countdown bar; a tap closes it sooner.
    Cancel on the camera is the only control. A match that finishes after
    Cancel records nothing.
  - Success card follows the mockup: tick, photo, name, "Coming In · 1 of 2
    today", Student ID / Section and today's Coming In / Going Out times.
    A late Coming In adds a gold "Late 22 min · after 7:30 AM" chip. The
    dashboard summary card shows "N late arrival(s) today", history has a
    **Late** filter and a Late chip on each late record.
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
  v11 (`MIGRATION_10_11`) adds `is_late`, `minutes_late`, `late_after`,
  decided at scan time in `GateAttendanceRepository.recordConfirmed` and
  uploaded as `late` / `minutes_late` / `late_after` (`late` is null when
  that Coming In had no late check - "not checked" differs from "on time").
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
- **Register Card, Face & Number** (Admin > Register Card, Face & Number,
  PIN-locked; screen title "Register Student") is one wizard, not separate
  screens (the user asked for it that way, then for the parent number as
  a step of it): **1 Student** (picker with search and each student's
  Card / Face / Parent no. ticks) -> **2 Card** -> **3 Face** -> **4 Parent
  no.** -> **5 Done** (one summary card with card, face and number, then
  "Register next student" / "Finish"). The step bar (`StepIndicator`) is
  numbered circles with labels under them - five steps didn't fit the old
  one-line label on a phone. **Each step is its own full page** (the user
  asked not to have card + wizard stacked on one page): the wizard draws
  its own header (back, "Register Student", "Step 2 of 5 · Card", the step
  circles; `AppRoot` counts it as owning its chrome), pages slide in with
  `AnimatedContent`, each shows a student chip and a big icon, and **every
  step moves on by itself** (the user asked for no buttons): a card read
  goes straight to the face and a new card replaces the old one without a
  confirm; the face is captured on the **gate's full-screen camera**
  (`LiveFaceCaptureView(fullScreen = true)` + `FaceScanOverlay`, only an X
  to skip) and a capture that doesn't take retries itself on the same
  camera; a complete valid number saves itself after
  `PHONE_SAVE_DELAY_MILLIS`; Done moves on after `DONE_MILLIS`. What a
  student already has (card, face, number) is kept after a `KEEP_MILLIS`
  countdown bar, with an optional "Keep it now" / "Re-enroll" link. The
  only real choice left is a face that's already another student's (Try
  again / Skip). The number page previews the text the parent will get.
  The old framed (non-full-screen) camera showed a black half on the
  user's phone - the wizard no longer uses it. `StudentRegistrationScreen` +
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
  - Face: the gate's own live auto-capture (`LiveFaceCaptureView`),
    **three angles** (the user asked for 2-3 angles, matched best-of, to
    cut false rejections while the MobileFaceNet threshold is untested on
    phones): **Straight**, then **One side**, then **Other side**
    (`FaceAngle`, `FaceAngles`). A banner on the camera says which angle,
    with a dot per angle. The camera only fires at the asked angle - the
    fast detector's head yaw (`headEulerAngleY`) is passed through
    `LiveFaceCaptureView(acceptYaw = ...)`: straight |yaw| <= 12, a side
    12-40 degrees, the other side the opposite sign of the first
    (deliberately not "left"/"right" - the front preview is mirrored).
    Each angle goes through `FaceTemplateRepository.captureAngle` (face
    found; liveness only on the straight one; not another student's face),
    and each side must still score >= `SAME_PERSON_MIN_SCORE` (0.62)
    against the straight capture, or it retries ("doesn't look like the
    same person"). A side not managed in `SIDE_ANGLE_TIMEOUT_MILLIS` (20s)
    is skipped; the X on the camera finishes with the angles taken so far
    (none -> skip face). All angles are saved together at the end
    (`saveEnrollment`, one transaction replacing every old row of that
    student). A student who already has a face can keep it or re-enroll
    (the page shows "N of 3 angles" and suggests re-enrolling when < 3);
    "Skip face for now" goes on without one (the Done step warns that the
    gate refuses them until a face is enrolled). Done shows "Face enrolled
    · N of 3 angles".
  - Parent no.: the parent's mobile number the gate texts go to. Optional
    ("Skip for now"; Done says the parent gets no texts). Checked as it's
    typed - a Philippine mobile, stored as `09XXXXXXXXX`
    (`normalizePhMobile`, same rule as the server's `PhMobileNumber`);
    blank removes a number the student had, and a student who has one can
    "Keep 0917 123 4567". Saved on the device at once
    (`StudentEntity.parentPhone`, `MIGRATION_9_10`) and uploaded to
    `admin_set_parent_phone` by `ParentPhoneSyncManager` (part of every
    gate sync, same pending/synced/failed cycle as cards). The number lives
    on the student's **parent account** on the server (`User.parent_id`),
    so a student with no parent account gets a warning and the server
    refuses the upload (shown in Students) until one is linked on the web -
    the next student download that reports a parent account re-queues it.
  - Students list: card number, parent number + sync state, and a gold
    "Face: N of 3 angles - re-register for better matching" line for a
    face with fewer than all angles (e.g. enrolled before this); three buttons
    per student - Card (Replace / Deactivate; Replace opens the wizard at
    the card step), Face (wizard at the face step, card step if there's no
    card yet), Parent no. (wizard at the number step). Filter "No parent
    no." lists who still gets no texts. Back from a wizard opened there
    returns to Students.
  - Every card change goes to the server registry (pending until sent; a
    refusal shows its reason). The student download applies the server's
    cards (`rfid_managed: true`) but never overwrites a change on this
    device that hasn't been sent. Parent numbers work the same way
    (`parent_phone_managed: true`, `has_parent_account`, `parent_phone`).
- **Parent SMS** (Admin > Parent SMS, `ParentSmsScreen` +
  `ParentSmsViewModel`): the wording of the text a parent gets on each
  scan - one for Coming In, one for Going Out, per school, kept on the
  server (it sends the texts), so this screen needs a connection. Default
  is Filipino, from the user's own example, **prefixed with the school's
  name** ("{school}: ..." - with the free phone gateway the sender shows
  only the SIM's number, and a SIM can't send under a name; a real sender
  name needs Semaphore's approved sender ID; with no school name the
  leftover ": " is dropped): "Ang inyong anak na si
  {student} ay pumasok sa paaralan ng {time} ({date})." / "... ay lumabas
  ng paaralan ng ...". Placeholders {student} (required) {time} ("7:42
  AM") {date} ("Sep 26, 2026") {code} {school}, inserted by chips at the
  cursor. Live preview as an SMS bubble with a real student's name, a
  character / SMS-part count (`SmsTemplate`, rendered exactly like the
  server's `GateSmsTemplate::render` - pinned by `ParentSmsTest`), "Use
  default". Also shows how many students have a parent number and whether
  the platform's SMS switch is on (a superadmin setting on the web - no
  texts go out while it's off). A third message, **Late Coming In**, is
  sent instead of the Coming In one for a late scan (default "... ay
  pumasok sa paaralan ng {time} ({date}) - huli ng {minutes_late}
  minuto.", extra placeholder {minutes_late}); hidden when the server
  doesn't have late messages yet.
- **One face per student** - `FaceTemplateRepository.captureAngle` compares
  each captured angle with every angle of every other student's face in the
  school (each student at their best angle, `bestPerStudent`) and refuses a
  match ("This face is already enrolled for <name> (<ID>)",
  `FaceCaptureResult.AlreadyEnrolled`, logged in the Audit Log; nothing of
  that enrollment is saved). A match
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
  - **Several angles per student** (`MIGRATION_11_12`): `face_templates.pose`
    (capture order, 0 = straight; a skipped side leaves no gap) and the
    unique index moves from (school, student) to (school, student, pose).
    Faces enrolled before this are pose 0 and keep working as one angle.
    `FaceTemplateRepository.verify` embeds the live frame once and takes
    the **best** score over all the student's angles
    (`FaceRecognizer.verifyFace(frame, templates)`), against the same match
    threshold. Best-of lowers false rejections for a turned head; it also
    gives an impostor up to three tries at the threshold instead of one -
    watch for that when tuning Face Settings on real phones. Face counts
    are students, not rows (`COUNT(DISTINCT student_id)`).
- **Gate device health** (the user's pick from the feature list: "last
  synced time, battery, and whether the RFID reader is connected, so an
  admin can see a gate that's gone offline without walking over to it").
  `DeviceHealthReporter` posts a snapshot to `admin_gate_device_heartbeat`:
  `DeviceSettings.deviceUid` (random per install), model, Android and app
  version, battery % + charging (`BatteryManager`), reader plugged in
  (`RfidManager.currentStatus()`, read from the USB port so it works with no
  screen open), network type, attendance waiting to upload + the oldest's
  time, refused uploads, last upload / last full sync
  (`DeviceSettings.lastSyncOkAt`, set when an upload run finishes with
  nothing stopped) / last scan, today's recorded + face-failed counts
  (`GateScanDao.health`), students / cards / faces on the phone, schedule
  set, camera permission, app on screen (`MainActivity` onStart/onStop),
  free storage and the phone's time. When: every 5 min while the process
  runs (`start()` from `App.onCreate`), after every `SyncWorker` run (every
  15 min even with the app closed), 3 s after the reader or charger is
  plugged/unplugged or the app opens/closes (`reportSoon`, debounced), and
  Sync & Account's "Report now". Throttled to one a minute unless forced;
  never queued (a missed report is replaced by the next); 404/405/501 =
  "not set up on the school server yet". The server answers with the name
  the admin gave the phone on the web and the **clock skew**: the gate
  dashboard shows a red "This phone's clock is 12 min fast" card (tap opens
  date & time settings) at 5+ min off (`DeviceHealth.clockWarning`) - scans
  are stamped with the phone's clock, so a wrong one means wrong times, Late
  flags and parent texts. Sync & Account has a "This gate device" card (web
  name, last report, live battery/reader/app version, Report now). CI builds
  are versioned `0.1.<GITHUB_RUN_NUMBER>` (versionCode = run number) so the
  web shows which build each gate runs; local builds are `0.1.0-local`.
- Admin screens (Register Card, Face & Number, Students, Parent SMS, Gate
  Schedule, Face Settings, Audit Log, Sync & Account, Change PIN) sit behind a **device PIN**
  (`AdminPinManager`: salted PBKDF2 hash in Keystore-backed encrypted prefs,
  5 wrong tries -> 60s lockout, counted persistently). They relock when you
  return to the gate. The PIN screen is an access-code keypad (the user's
  mockup): title, dots, round 1-9 / 0 / delete keys, no system keyboard.
  The keys size themselves to the space (`fitKeySize`, 44-76dp), and a
  short, wide screen (a phone in kiosk mode's forced landscape) puts the
  keypad beside the title - the fixed 76dp stack needed ~576dp of height,
  so on a phone on its side rows 4-6 squashed into pills and 7-9/0 fell off
  the screen (user's screenshot).
  New PINs are 4 digits, entered twice ("Create" then "Confirm"); the
  length is stored (`AdminPinManager.pinLength`) so entry checks itself on
  the last dot. An older 4-8 digit PIN with no stored length gets an OK key
  instead - never auto-tried at each length, which would burn lockout
  attempts. A wrong code shakes and clears the dots. **Forgot PIN** = a *fresh* admin sign-in (an already
  open session doesn't count - `AuthViewModel.lastLoginAt`) clears it.
- Sign-in is limited to role `admin` (the gate endpoints' `requireAdmin()`
  checks `role_id === 2`, so teachers and superadmins would only get 403s).
  The only in-app sign-in after that is the "forgot PIN" re-authentication,
  which skips the sync step. The web also decides who may use the gate
  (`GateAccess`, see "Gate access switches" below): sign-in is refused, and
  a restored session signed out at app start, when the school's `rfidGate`
  is off or a co-admin's `gateApp` is off - with the reason on the sign-in
  screen. Scans already on the phone are kept.
- Real Room migrations (`MIGRATION_6_7` ... `MIGRATION_12_13`), not the
  destructive fallback - installed devices hold card assignments and face
  templates that exist nowhere else.
- **Faces are shared between the school's gate phones** (the user said yes
  after being told faces are sensitive personal data under RA 10173 and the
  school should tell parents / get consent). Register once, recognised at
  every gate; a replacement phone gets every face back on its first sync.
  `FaceSyncManager`, step 4 of every gate sync (after attendance, never in
  its way), only while Face Settings > "Share faces with the school's other
  gate phones" is on (`SettingsRepository.shareFaces`, default on):
  - **Upload**: every registration gets a UUID `version` and
    `sync_status = pending` on all its angle rows (`face_templates.version /
    sync_status / sync_error`, `MIGRATION_12_13`, DB v13 - the state lives
    on the face rows, not the student row, because the student download
    rebuilds student rows). Sent to `admin_student_face_set` (code, model,
    version, device uid, each angle's numbers as base64 little-endian float32
    - `FaceCodec`, byte-identical to PHP `pack('g*')`, pinned by
    `FaceCodecTest`). 404/422 -> failed, 405/501 -> "not set up on the
    school server yet". `updateSyncState` only marks the version it sent, so
    a re-registration mid-upload stays pending. The wizard queues a sync
    right after saving a face. Faces registered before v13 get a version
    from their `enrolled_at` and are queued once by the migration.
  - **Download**: `admin_student_faces` with `since` =
    `DeviceSettings.faceDownloadSince` (the server's time of the last
    download) at most every 5 min, or forced right after a student list
    download (first sign-in sync and Sync & Account). A face replaces this
    phone's copy (`FaceTemplateRepository.replaceFace`, one transaction)
    unless it's the same version or this phone's own registration isn't on
    the server yet (pending/failed - the phone's wins, like cards). A face
    for a student not on this phone yet keeps `since` from moving on, so it
    comes again after the next student download. Other models and damaged
    numbers are ignored.
  - Downloaded faces take part in the one-face-per-student check when the
    next student is registered here; two phones registering the same face
    to different students at the same time isn't detected (no comparison on
    the server).
- **Backup file** (the user asked for it with the face-sharing answer: "export
  a file of all their faces and cards ... a backup for the other phone"):
  Sync & Account > Backup > **Export** / **Restore**. One file
  (`gate-backup-yyyy-MM-dd-HHmm.gatebak`, shared through the share sheet -
  Drive, email, Files) with every student's card, parent number and face
  (`BackupRepository`, `GateBackup`). Always password-protected
  (`GateBackupCodec`: "GATEBAK1" header, PBKDF2-HMAC-SHA256 120k iterations,
  AES-256-GCM with the header as AAD; min 6 characters, entered twice, can't
  be recovered). Restore (file picker + password) only into the same school
  (`school_id` in the file), only for students already on the phone
  (download the student list first - they're counted), skips a card
  another student holds here, and queues everything restored for upload
  like a change made on the phone (cards, numbers, faces pending). Works
  with no server. `GateBackupTest`: round trip, nothing readable without
  the password, wrong password, tampering, not-a-backup, newer version,
  fresh salt/IV per export. Audit log: `backup_exported` / `backup_restored`.
- **Kiosk Mode** (the user asked for a tablet-on-a-stand mode: dark theme,
  landscape, "game interactive... with animation", and hard to leave by
  accident - "some student will touch or back button and it will leave the
  app"). Admin > Kiosk Mode (`KioskModeScreen` + `KioskModeViewModel`), a
  single switch persisted as `DeviceSettings.kioskModeEnabled`.
  **Originally tablet-only** (the user's own follow-up: "kiosk mode only
  work on tablet then it's landscape not portrait") - the setting could be
  flipped on a phone (e.g. to prep a device before handing it off) but had
  no visible effect there, gated on `isTabletFormFactor()`
  (`util/DeviceFormFactor.kt`, `smallestScreenWidthDp >= 600`, the same
  line Android's own `sw600dp` resource qualifier draws, so it doesn't
  flip with rotation). **Follow-up ask: let a phone run kiosk mode too**
  ("kiosk mode make also that smartphone can acces kiosk mode not only the
  tab") - `isTabletFormFactor()` no longer gates kiosk mode anywhere
  (`MainActivity`'s `kioskEngaged`, `AppRoot`'s `kioskActive`,
  `KioskModeScreen`'s old "not a tablet" warning card, all removed); the
  exact same package (dark theme, forced landscape, screen pinning, the
  Back-button swallow, the pulse animation) now runs on a phone the same
  way it already did on a tablet, driven by the switch alone -
  `enabled` on any device engages it, for as long as the app is running,
  not scoped to one screen. `isTabletFormFactor()` still exists and is
  still used - just narrowed to the one thing that's genuinely
  tablet-specific, the Admin nav rail (`AppRoot`'s `showAdminRail`, see
  further down) - unaffected by this change.
  - **Dark theme, forced**: `MainActivity` passes `darkTheme = kioskEngaged`
    to `MuslimEduAttendanceTheme`, overriding whatever the device's own
    system setting says - the same deliberate dark palette Phase 7/9 built
    from the brand teal (see "Brand theme" below), not a separate kiosk-only
    palette.
  - **Landscape, forced**: `MainActivity.requestedOrientation` is set to
    `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` on entering kiosk mode,
    `SCREEN_ORIENTATION_UNSPECIFIED` on leaving - covers a cold start with
    the setting already on, and the admin flipping it while the app is
    running (a `LaunchedEffect` keyed on `enabled` alone now, read
    straight from `DeviceSettings` in `setContent` since this has to happen
    before any screen-specific ViewModel exists). A phone kiosk is forced
    into landscape the same as a tablet one - nothing here treats the two
    form factors differently, since the existing gate screens already
    adapt to whatever orientation they're given (no separate portrait/
    landscape layout branch to worry about).
  - **Can't leave, two layers**: (1) `startLockTask()` ("screen pinning") -
    stops Home and Recents from working. Needs no device-owner/MDM
    enrollment, any app can call it, but stock Android shows its own
    one-time "Screen pinning" explanation the first time, and the *only*
    way out without the admin PIN afterwards is the OS's own unpin gesture
    (hold Back + Recents, or swipe up and hold on gesture nav) - a
    phone-wide feature this app can't disable or replace, stated plainly on
    the Kiosk Mode screen rather than oversold as unbreakable. A real
    device-owner-provisioned kiosk wouldn't need that fallback gesture at
    all, but that needs a factory-reset provisioning step (`adb shell dpm
    set-device-owner` or an MDM/EMM enrolling the device) that no app can
    do to itself - noted as a known limitation, not built. Wrapped in
    `runCatching` (`MainActivity.enter()`/`exit()`, the `KioskController`
    interface it implements): a device that refuses pinning still gets the
    other two layers instead of crashing. (2) **Back button** - `AppRoot`
    already lets `GateScanScreen` handle its own Back (asks before leaving
    unsynced attendance, never exits the app either way, untouched here);
    the actual gap kiosk mode closes is `Screen.Gate` and `Screen.Register`,
    which own their chrome but registered no `BackHandler` at all - Back on
    the idle gate dashboard normally finishes the Activity today, which is
    exactly what a student touching Back at a kiosk stand must not do. A
    second `BackHandler(enabled = kioskActive && (Gate || Register))` that
    does nothing closes that gap, active only in kiosk mode - unchanged
    everywhere else, including on a phone.
  - **The one animated flourish** (`Modifier.kioskPulse()`,
    `ui/components/KioskPulse.kt`): a soft ring that breathes outward and
    fades behind the NFC icon on the gate dashboard's Coming In/Going Out
    cards and the RFID screen's "tap your card" icon - a quiet "tap here"
    invitation on an otherwise idle screen, not a status indicator (unlike
    `FaceScanOverlay`'s mesh/progress, nothing reads the pulse's state).
    Two rings a half-cycle apart (`InfiniteRepeatableSpec.initialStartOffset`)
    so one is always fading in as the other fades out. Threaded through as
    a plain `kiosk: Boolean` parameter on `GateDashboardScreen`/
    `GateScanScreen` (default `false`) - existing behavior for every
    non-kiosk call site (a phone, or a tablet with the setting off) is
    completely unchanged; this was scoped down from a full parallel "kiosk"
    screen (higher risk, unreviewable without a compiler - see below) to
    one small, provably-additive visual change to the two screens a
    student actually looks at.
  - **"Try it on this phone/tablet"**: `KioskModeScreen` has Pin now/Unpin
    buttons that call `KioskController.enter()`/`exit()` directly,
    independent of the persisted switch - screen pinning's first-time
    prompt (and whether a Settings toggle needs turning on first)
    genuinely varies by device/OEM, so the admin can try it once on the
    real device before relying on it at the stand, without touching the
    setting other students would see. The button/section label reads
    "this tablet" or "this phone" depending on `isTabletFormFactor()` -
    the only place left where that check affects Kiosk Mode's own screen,
    and purely a word choice, not a gate on functionality.
  - Audit log: `kiosk_mode_set` (`AuditLogger.ACTION_KIOSK_MODE_SET`), "on"/
    "off", logged from `KioskModeViewModel`.
  - `util/DeviceFormFactor.kt` splits the tablet check into a pure function
    (`isTabletFormFactor(smallestScreenWidthDp: Int)`, covered by
    `DeviceFormFactorTest`) and a one-line Context-reading wrapper, the same
    "pure logic tested, Android glue reviewed by hand" split this project's
    other tests already use (`GateScheduleTest`, `FaceAnglesTest`, ...).
  - **Not verified on a device, same reason as the rest of this project's
    UI work** (this sandbox can't resolve the Android Gradle Plugin - see
    "Not verified by a local build" under Phase 7/9) - reviewed by hand
    instead (import correctness, brace/paren balance, matching the
    documented `startLockTask`/`requestedOrientation`/`InfiniteRepeatableSpec`
    API shapes; the phone-kiosk follow-up removed an `isTabletFormFactor()`
    check in four places and nothing else, so the same caveat applies).
    Screen pinning in particular is worth trying on the actual kiosk device
    before relying on it at the stand (see "Try it on this phone/tablet"
    above) - OEM skins are known to handle the first-time pinning prompt
    differently from stock Android.
- **Attendance Summary** (Admin > Attendance Summary, `GateSummaryScreen` +
  `GateSummaryViewModel`): a week/month roll-up, replacing the old (pre-gate)
  "Attendance History screen" backlog item, which no longer applies now that
  classroom attendance is on the web app - `GateHistoryScreen` (day-by-day,
  filterable) already covers the equivalent gate-side history. Week
  (Monday-Sunday) or Month toggle, prev/next arrows capped at today ("Jump
  to today" appears once you've stepped away from the current period, same
  idea as the web's Gate Reports), stat tiles (Present/Late/Face failed/Days
  used), and a per-student list sorted worst-late-first.
  - **Built entirely from this device's own `gate_scans`, on purpose.**
    `GateAttendanceRepository`'s own doc comment already says gate records
    are per-device ("other gates' are on the web admin") - a school running
    more than one gate phone would see an incomplete picture here. The
    screen says so under the stat tiles, and points at the web's Gate
    Reports page for the combined, cross-device totals.
  - **Deliberately does not compute Absent** (`GateSummary`'s own doc
    comment) - unlike the web's `GateReportService`, one phone has no idea
    whether a day with nothing recorded means the student wasn't at school,
    or just scanned at a *different* gate device, or the school was closed
    that day. Present/Late/Face-failed are safe to show because each only
    ever adds a real recorded event; Absent would have to infer one from
    silence this phone can't fully see.
  - New `GateScanDao.observeForDateRange`/`GateAttendanceRepository
    .observeForDateRange` (inclusive `scan_date BETWEEN`), no schema change.
    `GateSummary` (pure: `periodRange`/`shiftAnchor`/`canStepForward`/
    `build`) is unit-tested (`GateSummaryTest`); the screen/ViewModel follow
    the same "pure logic tested, Android glue hand-reviewed" split as
    `GateHistoryScreen`/`GateHistoryViewModel`, and weren't run on a device
    for the same sandbox reason as the rest of this project's UI work (see
    Kiosk Mode's own note above).
- **Admin nav rail (tablet, non-kiosk)**: a persistent `NavigationRail`
  down the left side of every Admin tool - Dashboard, Register, Students,
  Attendance Summary, Parent SMS, Gate Schedule, Sync & Account, Face
  Settings, Audit Log, Change PIN, Kiosk Mode - so a tablet-holding admin
  can jump straight between tools instead of backing out to
  `GateAdminScreen`'s tile grid each time. Shown whenever
  `isTabletDevice && !kioskActive && shown.requiresUnlock` (`AppRoot.kt`'s
  `showAdminRail`) - never on a phone, never while Kiosk Mode is engaged
  (a kiosk stand has nobody standing at it to use a rail), and never over
  the Register wizard, which already draws its own full-screen chrome
  (step bar, back handling) the same way it does everywhere else - tapping
  the rail's own Register entry still opens it, just without the rail
  showing once inside.
  - **Purely additive**: `GateAdminScreen`'s tile grid is completely
    unchanged and still the content shown at `Screen.AdminHome` - the rail
    is a second way to reach the same destinations, not a replacement.
    Implemented by lifting the existing `when (shown) { ... }` route table
    into a `content: @Composable () -> Unit` lambda (verbatim, no case
    changed) and choosing, only in the `Box` that renders it, between
    calling it directly (unchanged behavior: a phone, or a tablet with
    kiosk on) or inside a `Row(NavigationRail, Box(weight = 1f) { content()
    })` - so every existing call site keeps working exactly as before, and
    the two-pane layout is the only new code path.
  - `AdminSection` (private enum: icon, label, target `Screen`) is the
    rail's own list, mirroring `GateAdminScreen`'s tiles one-for-one.
    `navigateFromRail()` mirrors each tile's own `onClick` (Register goes
    through the wizard's request-id plumbing via `openRegistration(null)`;
    Gate Schedule always sets `scheduleFromGate = false`, since the rail is
    only ever shown from inside Admin) so switching tools from the rail
    behaves identically to tapping the matching tile.
  - **Scoped down from the fuller "tablet responsiveness" ask** (nav rail
    *and* two-column master-detail layouts, both named in Phase 7/9's own
    deferred list). A genuine two-column split - e.g. a student list next
    to that student's detail, side by side - needs `StudentListScreen` and
    the registration wizard to both be redesigned around an
    optionally-embedded detail pane instead of the wizard's own full-screen
    step flow, which is a much larger, riskier change to get right without
    a compiler in this sandbox; not attempted here, and not silently
    dropped either - the rail (a real, self-contained tablet affordance
    that this pass could implement additively and safely) shipped instead,
    same "scope down, document what's deferred" discipline as every other
    large ask in this doc.
  - `NavigationRail`/`NavigationRailItem` are stable (non-experimental)
    Material3 API, present since well before this project's pinned
    `composeBom = "2024.06.00"`. Reviewed by hand, not compiled, for the
    same sandbox reason as the rest of this project's UI work (see Kiosk
    Mode's own note above) - worth confirming the rail actually renders
    and switches screens correctly on a real tablet before relying on it.

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

### Gate device health (Laravel + web - not in this repo)

Delivered as `gate-devices-patch.zip` (only the new/changed files, as the
user asked), same "not deployed until uploaded" status.
- **`gate_devices`** table (migration `2026_09_26_000005`, raw SQL
  `database/sql/gate-devices.sql`, which also records the migration as run;
  checked on MariaDB, run twice): one row per phone, unique
  `(school_id, device_uid)`, live state only - each heartbeat overwrites it.
- **`GateDevice`** model: `findForHeartbeat` - this install's row, else,
  because every update of this sideloaded app is a reinstall with a new
  device id, the most recently seen row of the **same model** in the school
  that has been quiet 10+ min is taken over (keeps the admin's name for it;
  two identical phones reinstalled together may swap names). `warnings()`:
  danger = offline (no report for 20 min), reader not connected, camera
  permission off, clock 5+ min off, battery <= 10% not charging; warning =
  battery <= 20% not charging, schedule not set, uploads waiting 30+ min,
  uploads refused, storage < 200 MB; info = not on the charger, app not on
  screen, older app build than another gate. `overviewForSchool` sorts
  attention first, then offline, then name; today's counts are hidden once
  the last report is from an earlier day.
- Endpoints (admin, own school; all 501 until the table exists):
  `admin_gate_device_heartbeat`, `admin_gate_devices`,
  `admin_gate_device_update` (name), `admin_gate_device_remove`.
- Web **Gate Devices** (`gate-devices.php/.js`, tile next to Gate Students):
  tabs All / Needs attention / Online / Offline with counts; a card per
  phone (status + "reported 3 min ago", battery, reader, uploads, today's
  scans, warnings); detail sheet with everything reported, rename, remove.
  Refreshes every minute while visible; "ago" uses the server's clock.
- **Superadmin view** (the user asked for it after "who can see gate
  devices?" - until then only school admins could): read-only
  `superadmin_gate_devices` (role_id 1) -> `GateDevice::overviewForPlatform`,
  every school's phones grouped by school (schools with phones needing
  attention first, then with phones offline, then name; school names from
  `schools.title`, "School #id" if missing) with platform totals. Same page,
  role-aware (`guardDashboard(['admin','superadmin'])`): a school picker
  (each option with its phone count and problems), a heading per school,
  back to the superadmin dashboard, and no rename/remove in the detail sheet
  (those stay with the school's admin). Tile in the superadmin dashboard's
  Operations section (`superadmin-dashboard.js`, next to Backend Status).
- Verified: `php -l`; 31 checks against SQLite with real Eloquent
  (`laraveltest/device_test.php`: skew, timezone conversion, takeover rules,
  every warning, offline threshold, overview order/counts, day rollover,
  build note, platform totals/order/names); the page in headless Chromium with a response generated by
  the real model (tabs, order, warnings, detail, rename/remove calls, empty
  and 501 states, dashboard tile) and as superadmin (school picker,
  headings, per-school tabs, read-only sheet, superadmin tile). Not run in
  the full Laravel app.

### Not arrived alert (Laravel + web - not in this repo)

Delivered as `gate-absence-update.zip` (only new/changed files; needs the
gate-devices patch first, since it relies on the heartbeat and
`gate_devices`). Migration `2026_09_26_000006` / `database/sql/gate-absence.sql`
(MariaDB-checked; the `absent_template` column add is last so a re-run only
errors on that line): `gate_absence_settings` (per school: `cutoff_time`
null = off, `text_parents`, `school_days` "1,2,3,4,5"),
`gate_absence_alerts` (unique `(student_id, date)` - one text per student
per day, also what stops two phones' reports double-texting),
`gate_no_school_days`, `gate_sms_templates.absent_template`.
- **`GateAbsenceService::run`** - no cron on this server (`Kernel` schedule
  is empty), so it runs after every gate phone heartbeat, in
  `app()->terminating` (after the response; the phone never waits). Texts
  only when: settings on with texts on; a school day (weekday in
  `school_days`, not marked no-school, and no published
  `holiday`/`eid`/`suspension` in `academic_calendar_events` covering the
  date - PH typhoon suspensions count); past the cutoff but within
  `GIVE_UP_HOURS` (3) - later is dropped, not sent late; and **every gate
  phone seen today is online, has reported since the cutoff, and has 0
  uploads waiting** (`readiness`, from `gate_devices`) - so a scan still on
  a phone can't turn into a false "hindi pa pumapasok". Who: active
  students with an **active RFID card** and **no gate event of any kind**
  that day (a face-failed scan means they were there). Alert row first,
  then the in-app/Messenger push + SMS (`texted` / `no_parent` /
  `no_phone` / `sms_off`); at most 100 per run, the rest next heartbeat.
- **Web Gate Students**: "No record" tab renamed **Not arrived**; a banner
  above the tabs says the cutoff, the count, and the state (before cutoff /
  texted N at 9:05 / on hold + why / no school + why / off / gave up) with
  **Mark as no school** / Undo (`admin_gate_no_school_day`; hidden on
  days that are already no school); each student gets a "Parent texted
  9:05" or "Not texted: no card / no parent account / no parent number /
  was at the gate / SMS is off" chip, also after they arrive late.
  `admin_gate_student_overview` carries `absence` (from
  `GateAbsenceService::status`) and each student's `absence`.
- Verified: `php -l`; 31 checks against SQLite with real Eloquent
  (`laraveltest/absence_test.php`: every hold-back rule, one text per day,
  concurrent row, SMS off, give-up, calendar suspension incl. multi-day,
  draft holiday ignored, no-school mark, web states); the SQL on MariaDB
  (run twice); the page in headless Chromium in every banner state. Not
  run in the full Laravel app, with a real queue, or a real SMS provider.
- "Server Error" on the app's Not arrived card (user's live server): the
  later full `routes/api.php` copies (security, reports) list the absence
  routes, but the code came only with `gate-absence-update.zip` /
  `face-sharing-update.zip` - an older `AttendanceApi.php` or a missing
  `GateAbsenceService.php` gives exactly "Server Error" with APP_DEBUG off
  (reproduced; a missing table gives a clear 501 instead). Fixed by
  `gate-server-catchup.zip`: the newest copy of all 37 gate server files
  (byte-identical to the fully tested test app) + `gate-all-in-one.sql`,
  one re-runnable script for every gate table/column (columns added via
  information_schema checks + prepared statements, works on MySQL and
  MariaDB; users.phone backfill only fills empty numbers). Tested on an
  empty, a half-updated and a fully updated database.
- Known limits: one cutoff per school (no per-section/afternoon shift);
  a face-failed scan still waiting on a phone isn't in `pending_uploads`
  (only attendance is), so that student could be texted; nothing is
  written to class attendance as absent - teachers still decide that.

### Shared faces (Laravel + web - not in this repo)

Delivered as `face-sharing-update.zip` (only new/changed files). Migration
`2026_09_26_000007` / `database/sql/student-faces.sql` (MariaDB, run twice):
`student_faces`, one row per student (unique `student_id`), `templates` =
`Crypt::encryptString` of the angles JSON (the app key; hidden from JSON),
`version`, `angles`, `model`, `device_uid`. `StudentFace::embeddingProblem`
refuses non-base64 or odd-sized numbers (max 4096 bytes an angle, 5 angles,
model `mobilefacenet` only). `admin_student_face_set` (same version again =
no change) and `admin_student_faces` (`since`, one second of overlap;
active students of the admin's own school only; returns `server_time`),
both 501 until the table exists. Gate Students' detail sheet shows "Face
registered: N of 3 angles" (`face_angles` / `face_sharing` in the
overview). Checked with 14 checks against SQLite + Laravel's real
Encrypter (`laraveltest/faces_test.php`); not run in the full app.

### Security update (Laravel + web - not in this repo)

From a security review of the Laravel/web copy the user supplied, delivered
as `security-update.zip` (needs `face-sharing-update.zip` first). Review
findings, proven where possible: uploads could be HTML/SVG pages on
manhaje.com that read the web app's localStorage token (shown in Chromium);
a self-registered school's admin had full admin API access (approval was
only checked in dashboard.js); an unauthenticated `/api/run-comment-column-fix-4q9wz`
route altered the DB; password resets didn't revoke tokens. The old blade
web controllers (any admin could edit/delete any user) are unreachable
while `RestrictToApi` stays - dormant, not fixed. The modern API scoped
every user/section lookup by school; no SQL injection found.
- `BlockUnsafeUploads` (global middleware): refuses html/svg/xml/js and
  server-script names/content on every upload (last extension, any
  script extension in the name, sniffed type, .htaccess/.user.ini).
- `.htaccess` for `public/assets/{uploads,csv_file,word_file,upload}`: no
  PHP, script/page files denied, `CSP: sandbox` + nosniff on everything
  but PDFs (Chrome won't show a sandboxed PDF); svg denied when
  mod_headers is missing. Tested on Apache 2.4 + mod_php incl. missing
  modules.
- `EnsureSchoolApproved` (`school.approved`, on the auth:sanctum group):
  pending/rejected schools reach only me/logout/user settings/password/
  translations.
- Password reset deletes the user's tokens; `AppServiceProvider` rejects
  tokens unused for 90 days (`Sanctum::authenticateAccessTokensUsing`,
  checked before `last_used_at` is updated - gate phones report every 5
  min so never idle). Guidance chat links no longer use inline onclick.

### Web app's own door (Laravel + web - not in this repo)

The user asked for the web version to have its own backend and not use the
API, same UI. Chosen design (asked, they picked it): the same Laravel app
answers the web app at `https://manhaje.com/apps/web/...` with a session
cookie (HttpOnly) + CSRF, instead of `/apps/api` + a token in
localStorage. Delivered as `web-door-update.zip` (needs the security
update). The phone and gate apps keep `/api` + tokens.
- `RouteServiceProvider` loads `routes/api.php` a second time under
  `web/` with `['web', 'throttle:api', EnsureWebSession]`, then
  `routes/webapp.php` overrides login/logout/logout-all and adds
  `csrf` + `revoke-old-token` (695 endpoints mirrored, nothing copied).
  The only named API route (`messenger.webhook`) gets `web.` on its /web
  copy so `route()` still points at /api and route:cache works.
  `RestrictToApi` allows `web/`.
- `WebAppAuthController::login` runs `ApiController::login` itself (same
  roles, 2FA, pre-registration answers, generic errors), deletes the
  token it made and logs in the `web` guard (remember-me 30 days).
  `EnsureWebSession` signs a session out when the password or remember
  token changed (reset / log out of all devices) or the account is
  disabled. `TwoFactorController::deviceSessionsList` handles the
  TransientToken of a session user.
- Web app: pages live in their own folder (e.g. manhaje.com/qq/), Laravel
  at /apps, so the base is `location.origin + '/apps/web'`. The cookie +
  CSRF code is a block at the top of `offline-data.js` (loaded first on
  every page; three pages that lacked it gain the script tag): adds
  `X-XSRF-TOKEN`, fetches `/web/csrf` first when needed, retries once on
  419, and ends/removes any old localStorage token. `getStoredToken()`
  returns a stand-in so no page code changed; `sw.js` never caches /web/.
- `.env`: `SESSION_DRIVER` must not be `database` - the app's `sessions`
  table is school years.
- Tested on a rebuilt copy of the Laravel app (Laravel 10, MariaDB, all
  updates, pages at /qq and Laravel at /apps): 22 curl checks + remember-
  me/logout-all, 18 Chromium checks through the real pages (sign-in,
  dashboard via /web only, JSON + multipart saves, stale CSRF recovery,
  old-token cleanup, offline queue replay, sign-out), public forgot-
  password and admin sign-in. Not tested on the real server/.htaccess.
- **429 "The server returned an error (429)" fix** (`rate-limit-fix.zip`,
  the user hit it on manhaje.com/v2/ browsing alone). Not load - Laravel's
  per-account limit (120/min) used up by two things, measured on the test
  copy: (1) `runAdminSetupGate` re-ran the 13-call setup checklist on every
  admin page even when cached complete, and the admin dashboard's ring
  (`fetchSetupChecklistProgress`) ran the same 13 again - 118 of 146
  requests over 7 page views; (2) gate phones sign in with the admin
  account, so their uploads shared the browser's bucket (same limiter
  name + `user:ID` key). Fix: `RouteServiceProvider` gives `/web` its own
  limiter `webapp` (signed in 600/min, signed out 120/min per IP - a
  separate count from `/api`), `api` signed in 300/min (signed out 120);
  login keeps `throttle:6,1`. `dashboard.js`: a complete setup is re-checked
  at most every 30 min (`SETUP_RECHECK_MS`, timestamp
  `muslimedu_admin_setup_gate_<id>_checked_at`; incomplete setups are still
  checked every page), the ring reuses the guard's answer
  (`setupKnownComplete` / `setupLiveCheck`); 7 page views now 42 requests.
  A 429 no longer dead-ends: the guard shows "Continuing in N s" from
  `Retry-After` and retries by itself (3 times max), `authedPost` waits out
  a <= 10 s limit once, longer ones give a plain message. Needs
  `php artisan optimize:clear` (a cached route list keeps `throttle:api`).
  Checked: 7 HTTP limit checks, 13 Chromium checks (`e2e/rate-limit.js`),
  the web door's 18 and Gate Reports' 46 still pass.

### Gate SMS notifications for parents (Laravel - not in this repo)

Delivered as `sms-gateway-patch.zip`, same "not deployed until uploaded"
status as the gate patch above. The user asked for parents to be texted
when their child scans Coming In/Going Out, and specifically asked whether
their Facebook account could be connected automatically - it can't:
Meta requires a parent to message the Page first (an opt-in the platform
enforces, not something code can skip), and even after that a Page can only
message them for 24h after their last message TO it - confirmed from the
backend's own `MessengerIntegrationController`/`MessengerWebhookController`
docblocks, which already document both limits. That existing Messenger
integration (one platform-wide Facebook Page, per-user PSID link via
`User.messenger_psid`, forwarded through `NotificationController::push()`)
was already there - not built this session - so Messenger stays what it
was: a free bonus channel for a parent who's connected and stays active,
never the reliable path.

- **Two SMS providers, picked per-install** (`SmsGatewaySetting.provider`,
  one global row like `MessengerIntegration`) - both reliable: no opt-in,
  no time window, just the parent's phone number. `SendSmsNotification`
  sends through whichever is active, each with its own phone-number shape
  (Semaphore: local `09...`; the Android gateway: E.164 `+63...` - both
  derived automatically from whatever an admin types in):
  - **Semaphore** (semaphore.co, ~PHP 0.35-0.56/text, no monthly fee,
    reaches all 4 PH networks) - paid, one HTTP POST with an API key, no
    OAuth. Globe Labs' telco API was considered for its free PHP 1,000
    sign-up credit but not used - its OAuth token lifecycle is a heavier
    integration for the same result.
  - **Android phone gateway** - genuinely free, the user's own follow-up
    ask after hearing Semaphore's real cost. An old Android phone with its
    own SIM becomes the sender, via the free, open-source "SMS Gateway for
    Android" app (github.com/capcom6/android-sms-gateway, docs.sms-gate.app).
    Its **Cloud mode** is what this points at - no VPS or port-forwarding,
    the phone connects outbound to the project's own public relay
    (`https://api.sms-gate.app/3rdparty/v1/messages`, Basic Auth with the
    device username/password the app shows once switched to Cloud mode).
    Confirmed by reading the project's actual docs, not assumed. Real
    trade-offs, stated plainly: depends on one physical phone staying
    charged and online with nothing here detecting if it goes offline, a
    carrier can throttle/flag a SIM sending a lot of automated texts, and
    every message passes through that shared public relay (their own
    "Private Server" self-hosting option avoids that, at the cost of
    running a Docker container - not wired up here).
  - Settings page shows both as selectable option cards; picking one
    reveals its own fields and setup instructions inline (the Android
    gateway's card explains installing the app and switching it to Cloud
    mode right there, rather than sending the admin elsewhere to find out).
- **The hook**: `AttendanceApi::admin_gate_attendance_scan()` calls
  `notifyParentOfGateScan()` right after a scan is confirmed and saved -
  never for a rejected/face-not-confirmed scan, never for a retried/
  duplicate upload (both return earlier). Resolves the parent via
  `User.parent_id` (the same column `BehaviorController` already uses for
  its own parent notifications), then both pushes the free in-app/Messenger
  notification and dispatches the SMS. The text is the school's own
  wording (`GateSmsTemplate`, table `gate_sms_templates`, one row per
  school, null = default), edited in the app's Admin > Parent SMS via
  `admin_gate_sms_templates` / `admin_gate_sms_templates_update` (admin,
  own school; each message must contain {student}; the show response also
  carries `sms_enabled`, the platform switch). Times go out as "7:42 AM",
  dates as "Sep 26, 2026". A late Coming In uses `late_template`.
- **Late arrivals (server)**: `gate_events.is_late` / `minutes_late` /
  `late_after` (migration `2026_09_26_000004`, `parent-sms.sql` part 4;
  `GateEvent::hasLateColumns()` skips them until the migration has run, so
  uploads never fail on a half-done deploy). The daily gate row is status
  `late` when the day's first "in" was late (`AttendanceService::
  firstGateInLate`). `summarizeDay` adds `late` / `minutes_late` /
  `late_after` (of the arrival = first verified in) and `late_count`;
  Gate Students has a **Late** tab (any late Coming In that day), a "Late N
  min" chip and Arrival / Late after rows. Teacher Gate Records shows the
  chip; its Sync marks a late arrival **Late** only in the homeroom
  attendance (subject 0, and only if the school's statuses include
  `late`) - a subject class later in the day gets Present with "(late N
  min)" in the remark, since the morning's lateness isn't lateness to that
  class. Checked against SQLite with real Eloquent (18 checks: out-of-order
  uploads, lunch-return late, homeroom vs subject sync, the late text) and
  the Gate Students page in headless Chromium.
- **`users.phone` is a new real column** - today a phone only exists ad hoc
  inside `user_information` JSON from one admission flow, so almost no
  parent has one anywhere queryable. New migration adds the column and
  backfills it from that JSON where present; everyone else needs one
  entered by hand.
- **Where an admin enters it**: opened the existing Gate Students student
  detail sheet (`gate-students.php/.js`) and added a "Parent contact" card -
  shows/edits the phone on file, or says plainly there's no linked parent
  account when `parent_id` is empty. New endpoint `admin_set_parent_phone`
  writes to the *parent's* phone column, never the student's. It stores
  one form, `09XXXXXXXXX` (`App\Support\PhMobileNumber`, also used by the
  SMS job), and refuses (422) anything that isn't a PH mobile - the old
  job-only check let landline-shaped numbers through. The app's Register
  wizard (step 4) uses the same endpoint, and `admin_gate_students` now
  sends each student's `has_parent_account` + `parent_phone` so the app
  shows them offline.
- New web page `sms-gateway-settings.php/.js` (superadmin, same layout as
  `messenger-settings.php`): API key, optional sender name (Semaphore caps
  it at 11 characters, needs their pre-approval), an on/off switch, and a
  "send yourself a test message" button before turning it on for real.
- **Two edits given as instructions in the zip's README, not shipped as
  full-file patches**: one line in `User.php`'s `$fillable` (`'phone'`) and
  one array item in `superadmin-dashboard.js`'s Operations section - both
  files are large, general-purpose files this session doesn't have a
  guaranteed-current copy of (unlike the gate-patch files, which come from
  this session's own earlier delivered zip), so overwriting them wholesale
  risked silently reverting unrelated changes.
- Real ongoing cost on Semaphore, since there's no way to make actual
  carrier SMS free through a commercial gateway: roughly
  `students x events/day x school days/month x PHP 0.35-0.56` - for 300
  students at 2 events/day, ~PHP 4,200-6,700/month. Globe Labs' PHP 1,000
  free sign-up credit covers testing, not ongoing volume. The Android
  gateway avoids this cost entirely at the trade-offs stated above.
- **Known limits**, same "say it plainly" discipline as the rest of this
  doc: a student needs both a linked parent account AND a phone on it -
  neither is guaranteed to exist yet, this patch only adds the column and
  the UI to fill it in. One gateway account for the whole platform, not
  per school (same choice already made for Messenger) - a true multi-school
  SaaS would want billing split per school instead. No SMS on a rejected/
  failed face check. Not gated by the existing `NotificationPreference`
  model - a parent can't opt out of just the SMS channel yet.
- Verified the same way as the gate patch: `php -l` on every new/edited PHP
  file; the two edited JS files (`gate-students.js`, the new
  `sms-gateway-settings.js`) passed `node -c` and were driven end-to-end in
  headless Chromium against a mocked API (both provider option cards
  swap fields correctly, save, send a test message, edit and save a
  parent's phone from the student detail sheet, and the "no parent account
  linked" message for a student with none). **Not run** against the real
  Laravel app, a real Semaphore or Android-gateway account, or a real
  phone.

**Not verified on a device**: the app compiles and its unit tests run in CI,
but the RFID reader, camera and migration need real hardware - especially
`MIGRATION_7_8` on a device that already has v7 data.

### Attendance reports (Laravel + web - not in this repo)

The user asked directly: "Attendance reports on the web: daily or monthly
per class, late and absent counts, export to Excel/PDF." Delivered as
`reports-update.zip` (needs the earlier gate/security/web-door updates).
A read-only web admin page, **Gate Reports**, next to Gate Devices - one
day or one month, optionally one class, built from the same `gate_events`
data every other gate page already reads (no new table, no migration).

- **`GateReportService::build(schoolId, period, value, sectionId)`** - the
  one place the definitions live, so this page, Gate Students and the
  "not arrived" alert can never disagree:
  - **Present**: the gate recorded the student (RFID + face) that day.
  - **Late**: part of Present - the day's first verified Coming In was
    after that Coming In's "Late after" time (same flag `GateSchedule` /
    `is_late` already compute at scan time - nothing recomputed here).
  - **Face failed**: at the gate, but every face check that day was
    rejected, so nothing was recorded - never counted as absent.
  - **Absent**: a *counted* school day with no scan of any kind.
  - **A day counts** only when it is a school day - the exact rules
    `GateAbsenceService::noSchoolReason` already uses (weekday in the
    school's `gate_absence_settings.school_days`, not a
    `gate_no_school_days` row, not a published holiday/Eid/suspension in
    `academic_calendar_events`; Monday-Friday when the absence tables
    don't exist yet) - **and** the gate actually recorded at least one
    scan that day. The second half matters: a day the gate phone was off
    or not yet started isn't "every student absent", it's a day nobody
    could have scanned - so it's shown as its own reason
    ("No gate scans that day (gate not used)") and left out of every
    count, the same way a holiday is. A scan that does land on an
    otherwise-excluded day (a Saturday make-up class, a day before the
    school even started counting) still shows on screen (lower-case p/l
    in the day grid) but never moves the totals.
  - **A student counts from the day they got an RFID card**
    (`student_rfid_cards.assigned_at`, or their first scan if the card
    registry doesn't reach that far back) - a student who never had a
    card is listed (so the admin can see who still needs one) but is
    never counted absent for days before or without one. "Today" always
    counts "so far" rather than flagging everyone not-yet-scanned as
    absent.
  - One school's students/classes/scans only - the same `school_id` scope
    every other admin endpoint uses; a class from another school is 404,
    not silently empty.
- **`SimpleXlsx`** - a small `.xlsx` writer built only on PHP's `ZipArchive`
  (no Composer package: `PhpSpreadsheet` isn't in this app's
  `composer.json` and the user's own hosting can't run `composer install`
  mid-session). Sheets of styled cells (bold/header/percent/late/absent/
  face-failed/off), column widths, frozen header rows, landscape +
  fit-to-page print setup. Falls back to a plain CSV automatically
  (`format=csv`, or whenever `ZipArchive` isn't compiled in) - still a
  correct file, just without the coloring; a cell that looks like a
  formula (a student name/code starting with `=`/`+`/`-`/`@`) is
  quote-prefixed so it can never execute as one when opened in Excel.
- **`GateReportController`** - `admin_gate_report` (JSON, for the page)
  and `admin_gate_report_export` (the file, `format: xlsx|csv`, defaults
  to xlsx). Both `requireAdmin()` (role_id 2, own school only), validate
  `period` (day/month), `date`/`month`, and an optional `section_id`
  checked against the admin's own school before running the report.
- **Web `gate-reports.php/.js`**: Day/Month toggle, a date or month
  picker with previous/next arrows (capped at today), a class dropdown,
  four stat cards, a by-class table (a month), a by-day table (a month,
  clicking a day jumps into that day's view), a day-by-day grid for one
  class in a month (like a DepEd SF2/class register), and a searchable,
  sortable student table. The chosen view is kept in the page's own URL
  (`?period=month&month=2026-09&section=11`) so a reload or a bookmark
  returns to the same report. Uses `authedPost`/the existing offline-data
  cache for the JSON report (so the last-seen report still shows while
  offline) but calls `fetch()` directly for the Excel download (a binary
  file isn't something the JSON-shaped offline cache/queue can hold, and
  downloading one certainly isn't a change to *queue* for later - it
  refuses cleanly with "You're offline" instead).
- **PDF is deliberately not a server-generated file** - it's this same
  page, printed by the browser ("PDF" button calls `window.print()` after
  swapping in print CSS and a page title so "Save as PDF" suggests a
  sensible filename). A `@media print` block hides the header/controls/
  search bar, shows a plain print header instead, and switches the page
  size to landscape only for the one view wide enough to need it (a
  month's day-by-day grid for one class).
- Verified: 54 checks of `GateReportService` itself against a seeded
  month (late arrivals, absences, a face-failure day, a marked-no-school
  day, a published holiday, a draft/unpublished holiday correctly
  ignored, a Saturday with no school, a day the gate wasn't used at all,
  a student who got their card mid-month, a student with no card, a
  second school's own separate data); 30 HTTP checks of both endpoints
  through `/api` (token) and `/web` (cookie + CSRF) - same file byte-for-
  byte either way, wrong role refused, another school's class 404s, bad
  dates/months 422, the CSV formula-injection guard; the generated
  `.xlsx` opened with Python's `openpyxl` *and* a real LibreOffice Calc
  (converted to PDF and rendered to images, not just parsed) to catch a
  file that's valid XML but wouldn't actually look right opened for
  real; 46 checks driven in real Chromium through the actual login and
  dashboard pages (the new dashboard tile, both view modes, the class
  picker, prev/next date arrows, the URL remembering the view, search/
  sort, clicking through from a class or day row, the Excel file
  downloading with the right name and content, the print layout in both
  orientations, the offline-cached fallback, no sideways scroll at phone
  width). Not run against the real Laravel app or a real phone-sized
  browser.

### Gate access switches (Laravel + web + app)

The user asked for the superadmin and the main admin to manage who can use
the RFID gate "like other features". Delivered as `rfid-access-update.zip`
(only new/changed files; needs the reports/catch-up `routes/api.php`).
- **SuperAdmin, per school**: a new school feature `rfidGate`
  (`School::GATE_FEATURES`), in Schools > Manage Features under "Gate
  Attendance (RFID + Face)". Default = **follows `attendance`** (a
  SuperAdmin override wins, then a package that names it), so nothing
  changed for any school when it shipped - a school with Attendance off
  never saw the gate tiles either. A school list chip shows "RFID Gate
  Attendance (off)".
- **Main (primary) admin, for co-admins**: four keys added to
  `School::ROLE_FEATURES['admin']` - `gateApp` (gate phones: sign-in and
  everything they upload), `gateStudents`, `gateDevices`, `gateReports` -
  in Dashboard Cards > Co-Admins, labelled Allowed / No access. Hidden
  there while the school's `rfidGate` is off. The tiles already went
  through `onAdmin()`, so they hide by themselves.
- **Enforced on the server**, unlike the other role cards (which are menu
  only): `EnsureGateAccess` middleware on every gate route in
  `routes/api.php` (`$gate . ':gateApp'` etc.; the card and parent-number
  routes take `gateApp,gateStudents` - either is enough - because both the
  app and Gate Students use them; teacher routes check only the school;
  `superadmin_gate_devices` is not wrapped). 403 with `reason`
  `gate_feature_off` / `gate_coadmin_off` and a sentence the app and the
  pages show as is. The primary admin, teachers and other roles skip the
  co-admin check.
- Web: `admin-dashboard.js` gate tiles on `rfidGate` (falls back to
  `attendance` on a server that doesn't send it), `teacher-attendance.js`
  hides the Gate Records method while it's off, `superadmin-schools.js`
  shows the switch only when the server knows the key.
- App: `UserDto` gained `is_primary_admin`, `school_features`,
  `role_features` (raw `JsonElement` - PHP sends an empty array as `[]`,
  which Gson can't read as a Map); `GateAccess.problem()` checked in
  `AuthRepository.login` and `validateSession`. Missing keys (older
  server) = allowed. `GateAccessTest`.
- **Found while testing**: nothing ever created
  `schools.role_feature_overrides` (read/written by School.php and
  RoleFeatureController, no migration in the supplied source), so on such
  a database every Dashboard Cards switch fails with "Could not save that
  change". Added migration `2026_09_27_000001` and
  `database/sql/gate-access.sql` (adds it, `feature_overrides` and
  `users.is_primary_admin` when missing; a school with admins but no main
  admin gets its lowest-id admin as main admin, the original migration's
  rule; re-runnable).
- Verified: 69 HTTP checks (`accesstest.sh`: every gate route for main
  admin / co-admin / teacher, each switch, /api and /web, messages,
  superadmin switch + reset + following Attendance), 34 Chromium checks
  (`e2e/gate-access.js`), the SQL on an empty and an existing database
  twice, and the Gate Reports 30 + 46 checks still pass. The test copy
  needed a stand-in `config/roles.php` (not in the supplied source, not
  shipped) for the `role:superadmin` routes to run.

### Offline web for every role (web only - not in this repo)

The user asked for the web app's offline feature to work for all role
accounts, not just admin. Delivered as `offline-all-roles-update.zip`
(13 files in `v2/`, no server or database change). What made it admin-only,
and the fix:
- `sync-status.js` (Offline & Sync) was guarded `['admin','teacher']` and
  linked only from the admin dashboard; uploading queued writes is manual
  and lived only there, so other roles' offline changes could never leave
  the device. Now `guardDashboard(null)`, back link `dashboardUrlForRole`,
  an Offline & Sync tile on every role dashboard (+ placeholder links,
  private menu, Account Settings row), and `pwa.js`'s top bar has
  **Upload now** (online + pending, signed-in pages only) / **Details**
  (offline + pending). Still never uploads by itself.
- `offline-data.js` read detection: any unrecognised read counted as a
  write, so offline it was queued and the page got a fake `{queued:true}`
  (every role's `academic_locale_bundle`, notification badge, feeds,
  polls, Quran tracker, scholarship search). New suffixes `_get _history
  _feed _poll _search _queue _bundle _unread_count _mine _my_children
  _form_config _translations _packages _templates _today _stats` + an
  exact list, checked against all 696 routes (no write matches;
  `_student(s)`/`_members` deliberately not suffixes). More NEVER entries
  (forgot_password, message_thread_start, guidance_inquiry_start/claim,
  messenger links, test email/SMS, exports).
- Download Now: `COMMON_DOWNLOAD` + `FULL_DOWNLOAD_BY_ROLE` for every role,
  each entry exactly what that role's pages send (recorded by crawling
  every role's menu in Chromium - the cache key is path + body).
- `flush()` keeps the change and stops on 401/419/429/5xx (used to drop
  it, so an expired session lost everything done offline).
- `sw.php` builds `PRECACHE` from the folder (pages, scripts, assets;
  skips sw.*, "(1)" copies, non-page .php). The hand list in sw.js had 146
  files; ~110 pages (student/teacher/Quran/scholarship/chat) stopped
  working offline after every deploy.
- Verified: 150 Chromium checks with the real service worker for student,
  teacher, cashier, registrar, alumni, parent, superadmin
  (`e2e/offline-roles.js`); web door 18 and Gate Reports 46 still pass.

### Qur'an Tracker wizard (web only - not in this repo)

The user asked for the web Qur'an Tracker to be simple and interactive,
"like a game wizard", with no new features, because some teachers are
old. Delivered as `quran-wizard-update.zip` (3 files in `v2/` + an Arabic
translations SQL; no server change).
- `quran-tracker.php/js` (teacher dashboard > Quran Progress, also admins)
  is now a 5-step wizard, one question per screen, big targets (26px
  questions, 150px tiles, 60-64px buttons): Student (tiles with where each
  student is) -> Lesson (New lesson / Review / Recite to me / Fix weak parts
  = new_hifz / murajaah / tasmee / revision) -> Ayahs (pre-filled: not
  started = Al-Fatihah 1-5, new lesson = next 5 ayahs after the position or
  the next surah, review = 1 to the position; big -/+ steppers clamped to
  the surah, full-screen searchable surah list) -> Result (4 big coloured
  buttons) -> Save (one-sentence summary; optional tap-to-count mistake
  chips, note + "parents can see" only once a note is typed, "another
  day?" link). Then a celebration (confetti for a pass, new position from
  the save response) with Next student / Another lesson. Phone back steps
  back (`history.pushState`), students done on the page get a Done badge.
  Offline saves show "Saved on this device" (offline-data queue).
  Right-to-left works (`inset-inline-end`, `text-align: start`, arrows
  flipped by `.qw-flip`).
- `quran-student.js`: Record Session / the hero card open the wizard with
  `?student_id=` (starts at step 2); the old sheet is gone.
- **Bug fixed on the way**: the old sheet sent `mistakes: [{type, count}]`,
  but `QuranTrackerController::recordSession` validates
  `mistakes.*.mistake_type`, so every session saved with a mistake got a
  422 (reproduced). The wizard sends `mistake_type`.
- Dropped from the teacher's page (still on the profile / admin Qur'an
  pages): the status summary chips and status/mode chips on the list.
- `quran-wizard-arabic.sql`: 87 `quran_wizard.*` keys + the Offline & Sync
  tile text in `academic_translations`. Starts with `SET NAMES utf8mb4`
  (a latin1 client stored mojibake without it) and deletes its own rows
  first: the unique key includes `school_id`, which is NULL for these
  platform rows, so `ON DUPLICATE KEY` never matches and a re-run
  duplicated every row (true of the app's other *_arabic_translations.sql
  files too).
- Verified: 51 Chromium checks at 390px (`e2e/quran-wizard.js`) as a
  teacher (every step, prefills, limits, surah search, mistakes/note in the
  DB, position only moving on a passed new lesson, back button, profile
  Record button, offline save + upload), an admin, and an Arabic teacher
  (rtl, translated, arrows flipped). The test school was made a markaz and
  given the teacher two classes for the run, then put back.

### Qur'an Tracker: days in the current mode (web only - not in this repo)

Follow-up ask: track what day / how many days it has been since a
student's mode last changed (e.g. moved to Tasmee' - reciting to the
teacher instead of a new lesson), so a student left reviewing or
reciting-to-teacher for weeks stands out from one who just got there.
Delivered as a small patch on top of `quran-wizard-update.zip` (needs it
first) - one migration, one model, one controller, three web files, plus
an optional Arabic SQL for the new strings only.

- **`quran_student_progress.mode_changed_at`** (new nullable timestamp,
  migration `2026_09_28_000001`) - stamped only when `mode` actually
  changes, in both places that set it: `advancePosition()` (every session
  save - `mode` follows the latest session type, see that method's own doc
  comment) and `updateProgress()` (an admin/teacher correction). Re-saving
  the *same* mode never moves it, which is the whole point - a student
  reciting Tasmee' every day for two weeks should show "14 days", not
  reset to "today" on each session. Existing rows (no history of when
  their mode last changed) are backfilled from `updated_at`/`created_at`
  by the migration, not left null.
- **`QuranTrackerController::shapeProgress()`** adds `mode_changed_at`
  (`Y-m-d`) and `days_in_mode` (whole days via `diffInDays`, 0 = today) to
  the same payload the dashboard, student profile and session-save
  response already return - no new endpoint.
- **Web**: `quran-tracker.js` (wizard's `whoHtml()`, shown on steps 2-5)
  and `quran-student.js` (the profile page's mode mini-chip) both show
  "Tasmee' · 9 days" / "New lesson · today" (`modeDaysText()` /
  `modeDaysLabel()`). A student whose mode is anything other than
  `new_hifz` (or `paused`, already flagged by its own status) for 14+ days
  (`QW_STUCK_DAYS`/`QS_STUCK_DAYS`) gets an amber "stuck" style - `new_hifz`
  is never flagged this way, since staying there a while memorizing is
  normal. Both pages tolerate a null `days_in_mode` (an old cached payload
  from before this shipped) by showing the mode label alone rather than
  crashing.
- `quran-mode-days-arabic.sql`: the 6 new `quran_wizard.mode_*` /
  `quran_student.mode_*` keys only - run after (or instead of re-running)
  `quran-wizard-arabic.sql`; same safe-to-run-again DELETE-then-INSERT
  shape.
- Verified: a real-Eloquent check against the webtest DB (new row gets
  stamped immediately, a real mode change moves it forward, re-saving the
  same mode does not, a null old-style row doesn't break the shaper), an
  HTTP round trip through `quran_tracker_session_save` confirming the
  fields appear end-to-end, and 18 direct checks of the new JS helpers
  (`modeDaysText`/`modeIsStuck`/`modeDaysLabel`, singular/plural/null/no-
  mode text, the 13-vs-14-day stuck boundary, `new_hifz` never flagged,
  the rendered `whoHtml()` markup) - run without depending on the live
  page's admin setup-checklist gate, which was in an unrelated
  incomplete state in the sandbox at the time. Not driven through the full
  logged-in page in this pass; the JS is the same code the already-tested
  wizard page loads unchanged otherwise.

### Scholarship staff review queues (web only - not in this repo)

Follow-up ask, after the Qur'an wizard: two more staff review screens made
"interactive like a game" the same way - a review-queue shape, same
audience (staff working through a list one decision at a time) as Take
Attendance's swipe cards. Delivered as `scholarship-review-queues-update.zip`
(3 web files only - no server or database change, every server call this
uses already existed).

- **Document Review** (`scholarship-document-review.php/js`, Document
  Review's `pending` filter only - the actionable queue): replaces the
  list + review sheet with a swipeable card stack, identical drag/edge
  mechanics to `teacher-attendance.js`'s manual roster (`.att-swipe-*`
  classes reused as-is, nothing new in `dashboard.css`) - one document at
  a time, right = Approve, left = Reject, up = Request Revision (a fixed
  3-way map, `DOC_SWIPE_DIRS`, not attendance's configurable status list -
  a document only ever has these 3 outcomes). The one real difference
  from attendance's batch flow: **every swipe calls
  `reviewScholarshipDocument()` immediately** - there's no "Save" step,
  each document is its own independent record - and a failed call snaps
  the card back instead of advancing (`attachQueueSwipeHandlers`'s `busy`
  flag blocks a second gesture mid-request). Big buttons under the edges
  do the same thing as a drag, for anyone who'd rather tap. Clearing the
  queue shows "All caught up!". Browsing already-decided documents
  (Approved/Rejected/Revision Requested tabs) is untouched - still the
  original plain list + review sheet, since there's nothing left to
  decide there.
- **Scholarship Applications** (`scholarship-applications.php/js`): a
  decision here isn't reducible to a 3-way swipe - 5 possible statuses, a
  per-item checklist that should actually be checked before deciding, and
  an optional Assign - so this is a **step wizard**, same shape as the
  Qur'an Tracker (its own `.sa-*` CSS block in `scholarship-applications.php`,
  copied and trimmed from `quran-tracker.php`'s `.qw-*` block since each
  page's `<style>` is scoped to that page only - no shared file, no
  collision risk). A new "Review Queue (N)" button above the list snapshots
  whatever's currently filtered/searched (`currentlyFiltered()`, the same
  logic the plain list already used) and opens a full-screen overlay:
  **Review** (the checklist, `reviewScholarshipChecklistItem()` per toggle,
  a live "N of M required items checked" line) -> **Decide** (4 big
  colored buttons, `DECISION_OPTIONS`, filtered to exclude the current
  status - tapping one reveals an optional note and enables Confirm,
  `advanceScholarshipApplicationStatus()`) -> **Done** (confetti only on
  Approved, "Next application" or "Back to list"). The on-screen back
  arrow steps back one wizard step at a time, or closes the overlay from
  step 1 - deliberately **not** wired to the phone/browser back button
  (this page already owns the URL query string for a program-filter deep
  link from Gate Reports; a second consumer of `history` risked fighting
  that, so the overlay's own header arrow is the only way back). The
  original list, filters, search, and "tap a row for a quick look" sheet
  are all unchanged underneath - the queue is additive.
- **Two real bugs found and fixed while testing against seeded data, not
  something this ask required but sitting directly in the code being
  touched**:
  - `scholarship-applications.js` called a `fetchScholarshipAccess()` that
    doesn't exist anywhere in `dashboard.js` - a straight `ReferenceError`
    thrown synchronously inside the `guardDashboard` callback, which
    silently aborted page boot before `reload()` ever ran (the list, the
    filters, the new queue button - none of it rendered, on every load,
    since this page shipped). Fixed to call the real listing,
    `fetchSuperAdminStaff()` (same shape `superadmin-staff.js` already
    uses).
  - The application detail payload's real keys are `checklist_items` /
    `status_history` (confirmed by reading the actual
    `admin_scholarship_application_show` response, not assumed) - both
    `renderDetailContent()` and the new Review step were reading
    `checklistItems`/`statusHistory` (camelCase), so the Checklist and
    Status History sections of the existing detail sheet have **always**
    shown "No checklist items."/"No status changes yet." for every real
    application, since this page shipped. Fixed in both places.
- Verified: 24 checks in real Chromium against the webtest app, signed in
  as the primary SuperAdmin (`super@test.local` - the only role besides
  platform_staff these pages are gated on, and, usefully, the one role
  **not** subject to `runAdminSetupGate` - that only fires for
  `user.role === 'admin'`, so this ran clean despite this sandbox's
  unrelated incomplete-setup fixture state that blocked the earlier
  Qur'an-tracker pass): the whole Document Review queue (each of
  right/left/up commits, removes the card, and lands the right status in
  `user_documents`; the non-pending tabs still show the plain list); the
  whole Applications wizard (a checklist toggle persists server-side, the
  progress line updates, Decide's Confirm only enables once a status is
  picked, Done names the right status and confetti fires only for
  Approved, "Next application" advances the snapshot queue, the back
  arrow steps back one screen then closes the overlay, and the real
  `scholarship_applications.status`/`scholarship_application_status_history`/
  `scholarship_application_checklist_items` rows all land correctly). Test
  data (a provider/program/requirements/3 applications/3 documents) seeded
  and cleaned up in the sandbox DB only. Not tested on the real server.

### Scholarship game UI (web only - not in this repo)

The user asked: "on Scholarship all features both admin student ui view
make it interactive like a game on chat support dont make it modal".
Delivered as `scholarship-game-update.zip` (60 files in `v2/` + README; no
server or database change). Every Scholarship / Taqdim / Translation /
University page, student and staff, rebuilt on a shared kit, and **no
modals anywhere in the scholarship area** - chats are pages/tabs, every
old bottom sheet or action sheet is an inline card.
- **Kit**: `scholarship-game.css/js` (`window.SG`: hero, tiles, ring, bar,
  segs/steps, journey, swipe `deck`, tabs, `confirmInline`,
  `openEditor` (inline form card replacing sheet forms), confetti, badge,
  `fitToViewport` for the inline chats). Pages load `?v=2`.
  `taqdim-hub.js` is the shared Taqdim/Translation admin landing.
- **Student**: Browse (swipe deck: right save / up details / left skip, or
  List), detail (deadline ring, mission list, eligibility quiz, sticky
  CTA), application quest (one step per screen, upload on pick, journey +
  confetti after submit), My Applications (journeys), Documents vault,
  Saved collection, Search (tabs with counts, "Try" chips,
  `?tab=universities`), Providers / provider page (Scholarships +
  Universities tabs, guidance tile), university page (courses expand
  inline), guidance start + guidance chat (link warning is an inline strip,
  built on the security-update copy), Taqdim/Translation request with an
  inline Support chat tab.
- **Staff**: Programs "studio" (readiness meter, tap-to-save status chips,
  3-step create wizard, per-program Details / Checklist / Universities
  tabs, hash-routed), Providers (live preview), Universities (profile
  meter), Courses (pick-one chips, `?university_id=`), Announcements
  (composer + preview, star to pin), Reports (animated scoreboard), Access
  (badge toggles + Save/Undo bar), Translation Requests (work queue, "Take
  the next request", full-page request with upload on pick, assign/status
  tiles, Completed locked until a translation exists), Taqdim/Translation
  hub, review queue (inline chat tab, `?application_id=` deep link),
  numbered requirement builder with reordering, draft review with inline
  Regenerate/Approve/Reject panels.
- **Real bugs fixed on the way**: guidance-chat.js's own `renderHeader()`
  replaced dashboard.js's, so the student guard crashed and the chat never
  opened; provider-detail.js wrote to a missing `#guidanceCtaWrap`, so
  every student saw "Could not load this provider."; university-detail
  read `academicPrograms` but the API sends `academic_programs` (no
  courses ever shown); Translation Requests read camelCase relations but
  the API sends `requested_by` / `assigned_to` (object) / `checklist_item`
  / `source_document` / `translated_document` ("Unknown student", no
  files); `fetchScholarshipAccess` / `updateScholarshipAccess` were never
  defined (added to dashboard.js - its only change on top of
  rate-limit-fix.zip); student application checklist key mismatch, broken
  document View links, Start button under the bottom nav.
- Heads-up given, not changed: three of the user's migrations generate
  index names over MySQL's 64-character limit (taqdim translation tables,
  universities/academic programs, saved scholarships) - fine if the tables
  already exist, fails a fresh `migrate`.
- Verified in Chromium at 390px on a freshly seeded rebuilt test app: 27
  student checks and 33 staff checks (SuperAdmin + school admin), plus the
  inline-chat / admin-queue / quest runs - no page errors, no modal
  overlays, no sideways scroll. Not tested on the real server.
  `scholarship-applications.js` / `scholarship-document-review.js` (earlier
  review-queue delivery) are unchanged apart from loading the kit.

### Taqdim support chat full screen (web only - not in this repo)

The user asked (with a screenshot of the boxed chat squeezed under the page
title and requirements list): "make the chat support chat box full screen
like messenger". Delivered as `taqdim-fullchat-update.zip` (6 files in
`v2/`, no server change; `taqdim-translation-chat.js` was missing from the
scholarship-game zip, so it's included now).
- `taqdim-translation-chat.js`: `renderChatInterface(name, { fullScreen,
  subtitle, attachments, waiting })` draws a Messenger-style view - top bar
  (back, avatar, name, subtitle, a Files button with a count that shows the
  submitted requirements on tap), messages, typing bar - and
  `mountFullScreen(panel, onBack)` takes over the screen: body class
  `taqdim-chat-full-open` hides the bottom nav and locks scrolling,
  `visualViewport` keeps the panel's height/top on the visible area so the
  typing bar stays above the phone keyboard, Escape = back. The box grows as
  you type, the keyboard stays up after sending, and the staff "Tip:
  [FILE:...]" line is folded behind a "?" button.
- Student page (`taqdim-translation-application.js`) and staff page
  (`taqdim-translation-applications.js`) render the chat in a
  `.taqdim-chat-fullscreen` panel (fixed, z-index 1200, centred 760px on a
  computer) instead of the old inline box. Opening it pushes a history entry
  (student `#chat`, staff `#app=N&tab=chat`), so the phone's Back button
  closes the chat and returns to the request; a deep link to the chat has
  nothing to pop, so its back arrow replaces the hash instead.
- Not a modal: a view of its own with no backdrop to dismiss.
- The user's screenshot of the student page also showed the staff-only Tip
  line and Assign/Change Status buttons behind the chat, which the current
  files never draw there - most likely a mix of old and new chat files on
  the live server; the README says to upload all six together.
- Verified: 33 Chromium checks (`e2e/taqdim-fullchat.js`) at 390px, 1280px
  and right-to-left - full screen, nav hidden, typing bar at the bottom and
  following a shrunken viewport, Files toggle, send, phone Back / back arrow
  / Escape / `#chat` link, staff tip, desktop centring. Not tested on the
  real server.

### Teacher pages as a game (web only - not in this repo)

The user asked: "can the teacher schedule and give grade we will make it
like games interactive like the scholarship?" Delivered as
`teacher-game-update.zip` (5 web files + the unchanged scholarship game kit
so it works on its own + optional `teacher-game-arabic.sql`, 73 keys; no
server or database change). Built on the same kit (`scholarship-game.js/
.css`) plus a new shared `teacher-game.css` (`.tg-*` grades, `.ts-*`
schedule).
- **Enter Grades** (`teacher-grades.js`, same GradebookApi endpoints):
  quarter tabs (remembered in localStorage) + class tiles with a ring of
  students graded that quarter -> one student per card (big mark, -/+,
  quick scores at 75-100% of the quarter's total, DepEd descriptor +
  percent as you type, optional comment, Q1-Q4 + average chips, a row of
  student bubbles to jump) -> "Quarter complete" (confetti, class average,
  top mark, passed at 75%+, descriptor spread). **Save & next saves each
  student immediately** (Enter too; tapping away from a changed card saves
  it) instead of one Save at the end; same record rules as before, a saved
  mark can't be cleared (the server ignores mark: null) so it's put back
  and the card stays. Offline saves show as pending (offline-data queue).
  Deep link `?section_id=&subject_id=[&quarter=]`; inside a class the hash
  is `#class=S:J&q=N` (pushed, so phone Back returns to the list). While
  grading, the page header is hidden (`body.tg-grading`).
- **My Schedule** (`student-schedule.js`, shared by teachers and students,
  same `/my_schedules`): hero = class in progress (ring of minutes left) /
  next class today (countdown) / done for today / no classes, ticking every
  30 s; Day view = day strip + the day as a path (done / now with progress
  / up next / later, free time between classes); teachers get Attendance
  and Grades (deep link into Enter Grades) on each class, students keep the
  Subject Status links; Week view = subject tiles + the old table.
- Found while testing: `teacher_gradebook_submit` writes `strtotime(...)`
  into `gradebooks.timestamp`, but the user's own fallback migration
  (`2022_07_25_000001_create_missing_core_tables`, only for fresh installs)
  makes that column a `timestamp`, so a student's first mark of a quarter
  fails with "Invalid datetime format" on such a database. Production
  most likely has an int column already (the legacy web marks screen writes
  the same number); the README gives the one-line ALTER in case. The test
  copy's column was changed to INT.
- A translations redraw (`onLocaleChange`) arriving while a class was
  still loading used to draw the class list over it; it now leaves a
  loading class alone and keeps anything typed on the card.
- Verified: 58 Chromium checks at 390px (`teacher-game/e2e-teacher-game.js`,
  seeded by `seed_teacher.php`: a teacher, Grade 5 with two sections, three
  subjects, 14 students, a published timetable, a Q1 exam out of 50),
  including database checks of every save, and a pass as an Arabic teacher
  with the SQL loaded (both pages rtl and translated). Not tested on the
  real server.
- **No-scroll + responsive follow-up** (the user: "make it that no scroll
  design also responsive same to scholarship"; the zip was rebuilt with the
  same name and replaces v1). Both pages are fixed app screens: `body.tg-app`
  (`html:has(body.tg-app)` + body at `100dvh`, overflow hidden, flex
  column), a compact static header, and `.tg-screen` (flex column, `max-width:
  var(--screen-max)` like the scholarship pages, bottom padding for the
  fixed nav). Only one list area may scroll, and only when there's more than
  fits: `.tg-classes`, `.ts-scroll` (kept on the class now/next by
  `keepCurrentInView()`), and the grading card as a last resort.
  - Phone: 2 tile columns, the card sized so Save & next sits above the
    nav; a 4-class day fits as two short lines per class (subject + status
    chip, then section/room + icon-only Attendance/Grades). Short screens
    (`max-height` 720 / 600) drop the hero's stats, the Q1-Q4 refs, the win
    icon and the day title first.
  - 640px+: 3 tile columns, action labels shown; 1024px+: 4 columns.
  - 900px+: while grading, `.tg-roster` (every student + mark, tap to jump)
    replaces the bubble row beside the card; the schedule's day strip
    becomes a 210px column with full names + "4 classes" (new keys
    `student_schedule.day_count` / `day_count_one` / `day_none`, Arabic
    SQL now 76 keys).
  - Phone on its side (`max-height: 500px` + `min-width: 560px`): hero +
    tabs left, tiles / the day right; the grading card is two columns and
    the bottom nav hides while grading; schedule classes are one row each
    with no free-time rows.
  - Verified: 0 page scroll and 0 list overflow with the seeded data at
    360x660, 390x844, 768x1024, 1280x800 and 740x360 (classes, grading
    card, quarter complete, schedule day), Save & next above the nav at
    every size; the e2e is now 88 checks, all passing.

### Admin Academics as a game (web only - not in this repo)

The user asked (with a screenshot of the admin dashboard's Academics
section): "on admin side build this interactive game ui also". Delivered
as `academics-game-update.zip`, 28 files: 8 pages' `.php/.js`, a new
kit, the scholarship kit (unchanged), `dashboard.js`, one server
controller, `academics-game-arabic.sql` (282 keys) and a README. The
pages are Classes & Sections, Class Schedule, Enrollment, Academic Setup,
Grading Systems, Subjects, Facilities and Attendance Config. Quran
Tracker, in the same section, is unchanged. They use the same endpoints
and rules as before, with no modals, sheets or `confirm()`.
- **Kit** `academics-game.js/.css` (`window.AG`, on top of `SG`):
  - A no-scroll app screen (`body.ag-app`) holding a list (hero, search,
    pills, tiles) and a side panel. On a phone the panel takes the
    screen; from 900px it is a 420px card beside the list.
  - The panel is keyed in the URL hash with pushState, so phone Back
    closes it.
  - `AG.wizard` runs quests: one question per screen, big choice tiles,
    steppers, toggles, auto-advance, step dots you can jump between when
    editing, and a win screen with confetti. `AG.form` is a one-screen
    form.
  - Delete uses an inline confirm.
  - `AG.setupNotice` is an inline "Setup completed · N/M, next step" card.
    It replaces `notifySetupItemSaved`'s modal sheet on these pages.
- **Pages**:
  - Classes: class tiles with a seats ring; the class panel lists its
    sections; a 6-step class quest; an inline section form.
  - Schedule: a day strip and the day as a path with free gaps. A 7-step
    quest picks day, time, section (new: the old page never sent
    `section_id`), subject, teacher and room, marking busy ones, then
    runs `admin_schedule_check_conflicts`.
  - Enrollment: stages as a journey you can drag to reorder, with a phone
    preview of the student instructions.
  - Academic Setup: year pills, term tiles with progress, a 3-step term
    quest.
  - Grading: 15 type tiles.
  - Subjects: colored tiles and a 5-step quest.
  - Facilities: a block per building with room tiles; a 4-step room
    quest.
  - Attendance Config: status stickers and method rows with an on/off
    switch; built-ins stay locked.
- **Bugs fixed on the way**:
  - Add Term always failed: the old page sent term_type `custom`, which
    the enum refuses, and allowed empty dates.
  - A schedule edit that changed day, teacher, room, subject or section
    got 404. `AcademicScheduleController::update()` looked the row up
    with the new form values; it now uses id + school only.
  - `dashboard.js` loaded the saved language only after the admin setup
    gate and the student enrollment gate, and both return early. So a
    school admin or a student never got their saved language, only an
    already-cached bundle. It is now started before those gates.
- Verified in Chromium on the rebuilt test app with 104 checks
  (`e2e-academics.js`):
  - Create, edit and delete on every page, checked in the database.
  - Drag reorder, Back and deep links.
  - No page scroll at 360x660, 390x844, 768x1024, 1280x800 and 740x360.
  - Arabic via the saved setting.
  - The teacher pages' 88 checks still pass.
- Not tested on the real server.

### Admin pages as a game, part 2 (Laravel + web - not in this repo)

The user picked "More admin pages as a game" from the what's-next list:
Students / Admissions, Attendance, Fees / Finance and Announcements /
Messages. **Then corrected the design** ("it's better attendance not
interactive game"; answered "Both" to: admin Attendance plain, teacher
Take Attendance unchanged, the other teacher UI a game). So Attendance is
NOT game-style anywhere, and the teacher home is (see below).
- **Package**: `admin-game-update.zip`, 27 files (replaces the first copy;
  its `attendance.php/js` are the ORIGINAL plain pages, shipped so a user
  who uploaded the first copy gets them back). It needs
  `academics-game-update.zip` first (the AG kit and its `dashboard.js`).
  - Web: five pages' `.php/.js`, a small kit add-on
    `admin-game.js/.css` (`window.AX`: avatars, class colours, money,
    dates, the "+" row) and `admin-dashboard.js`, which gets an
    Announcements tile (key `announcements`, not the academic
    `announcementReview`, so orphan schools keep it).
  - Server: `AttendanceApi.php` and the new
    `AdminStudentStatusController.php`.
  - `routes/api.php`: one new line (`admin_students_status`), also given
    in the README, which says to delete the three `admin_attendance_*`
    lines if the first copy put them there.
  - `admin-game-arabic.sql` (213 keys, checked against the user's own
    Arabic files so none of their wording is overwritten),
    `teacher-home-arabic.sql` (19 `teacher_home.*` keys) and a README.
- **Students** (`students-list`):
  - Photo tiles grouped by class; filters All / Active / Inactive / Not
    placed.
  - Panel with the profile, Edit as an editing quest, Report Card as an
    inline link (no popup-blocked tab), and Withdraw / Archive / Reactivate
    with a required reason in an AG.form.
  - Enrollment history. "+ Add" (was `notWiredYet`) goes to admission.
- **New Admission** (`admission`): a 9-step quest in a full-width
  panel (`.ax-solo`).
  - Steps: name, sign-in, contact, emergency, about, photo (same 200 KB
    compression), signature (kept as a data URL per stroke), class +
    section, check.
  - The walk-in hand-off from preregistrations still skips sign-in and
    photo and starts at the first open step.
  - The win screen replaces the old success modal. `?from=students|walkin`
    decides where X/Done go.
  - Steps are re-translated in place when the saved language arrives.
- **Attendance** (`attendance`): the original plain page (rate, status
  breakdown, daily trend, locked days with Unlock), by the user's choice.
  A first game-style version (class board, status "brushes", and the
  endpoints `admin_attendance_today/roster/mark`) was built and then
  removed; those endpoints no longer exist. Only the Unlock bug is fixed
  (below). The teacher's Take Attendance page (`teacher-attendance`, which
  already has swipe cards) is untouched.
- **Teacher home** (`teacher-dashboard.php/js`, game-style on the AG/AX
  kits): a top card with the greeting and one line from the timetable
  (`fetchMySchedule`: "4 classes today · now: Mathematics until 10:00
  AM" / next / all done) plus Today / This week counters; the round photo
  opens the profile panel (name, staff code, email, Edit Profile, Log Out
  with an inline confirm - `AG.confirm`); tiles in groups Classroom /
  Communication / Academics / Account (`tdSections`), filtered by
  `roleCardOn` as before; tiles with no web page yet are dimmed "Soon"
  buttons that show a toast; orphan-school and Quran-tracking variants
  kept. Uses `admin-game.css` `.ax-menu-tile` / `.ax-home`. Not a
  redesign of My Schedule / Enter Grades (already games).
- **Fees** (`fee-reports`):
  - Invoice tiles with a paid bar; the hero shows collected / to collect.
  - Record Payment quest: an amount capped at the balance, then the
    method (the cashier page's English values + Other), then a check.
  - New Invoice quest: student, what for, amount, check
    (`admin_fee_create`).
  - Tapping an invoice used to be `notWiredYet`.
- **Announcements** (`announcements`, new page, existing endpoints only):
  - School posts are the admin's own feed posts (`profile_feed`), with a
    quest (write, photos, who sees it, preview, `post_create`). Opening
    one lets you change its privacy (`post_update`) or delete it with an
    inline confirm.
  - Class posts come from `admin_announcement_review` and are read-only;
    the server has no admin edit/delete.
  - Messages: threads plus a Send quest (`message_user_search` with the
    `isOppositeGender` rule, then `message_chat_send`); the chat continues
    in `chat-box.php`.
- **Bugs found and fixed**:
  - `admin_children_list` never sends a status, so a withdrawn student
    showed Active, the Inactive filter was always empty and Reactivate was
    unreachable. Fixed by the new `admin_students_status`, merged by id; a
    separate endpoint so the mobile app's list is untouched.
  - `admin_attendance_unlock` queried `sections.school_id`, which the
    user's migrations never create, so every Unlock returned 500.
- Verified in Chromium on the rebuilt test app: 62 checks
  (`admin-game/e2e-admin-game.js`, fixture `seed_admin_game.php`) and 23
  for the teacher home (`admin-game/e2e-teacher-home.js`, fake clock
  Tuesday 9:10, links, Soon toast, profile + inline log out + real
  sign-out, 5 sizes, Arabic):
  - Every save checked in the database.
  - No page scroll at the 5 sizes.
  - Arabic via the saved setting.
  - The Academics 104 and teacher-game 88 checks still pass; the
    count-up wait in the Academics test was widened.
- Not tested on the real server.

### One back button (web only - not in this repo)

The user sent a phone screenshot of Classes & Sections with a panel open:
the page header's back arrow ("Classes & Sections") and the panel bar's
back arrow ("Class") stacked, and asked to "fix all back button ... make it
only one". Delivered as `one-back-button-update.zip` (kits + 13 game pages
+ 3 scholarship pages; upload after the academics / admin-game /
scholarship-game / taqdim-fullchat zips; no server change, no new texts).
- **AG kit**: `AG.openPane` sets `body.ag-pane-open` (cleared by
  `closeNow`); `academics-game.css` hides `#utilHeaderWrap` under 900px
  while it's set, so the panel's bar (back) or quest head (✕) is the only
  control. 900px+ was already one (the panel hides its own back there).
  New Admission (`.ax-solo`) hides the header at every size
  (`admin-game.css`, `:has`) - its ✕ leaves the same way. Panels that open
  on `SG.loading()` now carry `AG.bar('')` so a loading panel has a way
  out (classes-sections x2, class-schedule, subjects).
- **SG kit**: `SG.headerBack(fn|null)` - a capture-phase click listener on
  `.page-back-btn` runs `fn` instead of leaving the page while a sub-view
  is open. Used by `scholarship-programs` (program / #new -> list),
  `scholarship-translations` (request -> queue) and
  `taqdim-translation-applications` (request -> queue, also a deep link);
  their inner "‹ All …" links (`#spBack`, `#backToQueue`, `#tqBack`) are
  gone. Calls are guarded (`SG.headerBack && ...`) against a stale kit.
- Version tags bumped (`academics-game.*?v=2`, `admin-game.css?v=2`,
  `scholarship-game.js?v=3` on the 3 pages) so phones drop cached copies.
- Wizard footer "previous step" buttons are a different control and stay.
- Verified: 20 Chromium checks (`admin-game/e2e-one-back.js`: 11 game
  pages x list/panel/quest at 390x844, 740x360, 1280x800, admission,
  teacher profile, the 3 scholarship pages' back -> list -> leave, a deep
  link, Arabic); Academics 104, admin 62, teacher home 23, teacher pages 88
  still pass. Not tested on the real server.

### Schedule messages with details (Laravel - not in this repo)

The user sent a Messenger screenshot of "Schedule updated: A new class has
been added to your schedule." and asked for the message to include all the
details (day, time etc.). Delivered as `schedule-notice-update.zip` (one
file, `AcademicScheduleController.php`, on top of the academics zip's copy;
no DB/route change).
- `notifyTeacherOfSchedule` (fixed body text) replaced by
  `notifyScheduleChange($row, $before, $r)`: store() passes null, update()
  passes `clone $row` taken before `$row->update()`. Title "New class on
  your schedule" / "Class changed on your schedule" (+ `title_ar`). Body
  (`scheduleNoticeBody`): subject, "for <Class – Section>" (teacher) or
  "with <teacher>" (students), "every <Day> from 8:00 AM to 9:00 AM",
  "in <Room>, <Building>", then " Note: <remarks>"; on an edit each changed
  piece gets "(was ...)". Day 0 = Sunday (same as `SCHEDULE_INT_TO_DAY`).
- A teacher or section newly on the slot gets the "new class" version; an
  edit that changes none of `NOTICE_FIELDS` sends nothing.
- Per-recipient language from `user_settings.language` ('ar' -> Arabic
  title + body, subject `name_ar` when set; everyone else English) - two
  `NotificationController::push` calls. Messenger shows "title: body".
- Not changed: the teacher/section a class is taken away from, and a
  deleted class, still get no message (offered to the user).
- Verified through the real API on the test app (`schedule-notice/
  notice-test.sh`: create, assign teacher, move day/time/room, no-op save,
  subject + note, move section, Arabic student); Academics 104 still pass.

### Teacher Look switch: New / Classic (Laravel + web - not in this repo)

The user asked where best to put a switch back to the classic teacher UI;
recommended (and then asked to build): a Look switch in the game home's
profile panel, a "Try the new look" row on the classic home, covering the
home + Enter Grades + My Schedule, saved on the account. Delivered as
`teacher-classic-update.zip` (needs teacher-game, admin-game and
one-back-button zips first).
- **Classic pages** are the pre-game files under new names
  (`teacher-dashboard-classic`, `teacher-grades-classic`,
  `student-schedule-classic` .php/.js; from `admin-game/orig/v2` and
  `teacher-game/before`, = the user's supplied copies + the offline tile),
  plus `ui-style.js` and, on the classic home, a "Look" section with "Try
  the new look" (`teacher-dashboard.php?look=game`).
- **`ui-style.js`** (in `<head>` of all six pages): pairs each page with its
  classic copy; `?look=classic|game` stores a dirty choice; before sign-in
  the device copy (`localStorage muslimedu_teacher_look` = {uid, look,
  dirty}) picks the page at once (location.replace, query/hash kept); each
  page's guard callback calls `TeacherLook.sync(user, token)`: non-teachers
  reset the device copy to game and leave a classic page; a dirty choice is
  saved to the account; else the account's value wins (null = game, and a
  device-only choice is pushed up). `TeacherLook.set(user, look)` (the
  profile's Classic tile) stores dirty + navigates; the next page saves.
- **Server**: `UiStyleController` (`my_ui_style_get` - a `_get` read for
  offline-data - / `my_ui_style_save`, `style` in game|classic), column
  `user_settings.ui_style` (migration `2026_09_30_000001`,
  `database/sql/teacher-classic.sql`). Its own endpoints so
  `StudentPortalController::settingsShow/Save` and the `UserSetting` model
  are untouched (DB::table, insert when no row). 501 until the column
  exists; the web then keeps the choice on the device only.
- Found on the way: the first SQL guard (`INSERT ... SELECT MAX(batch) ...
  WHERE NOT EXISTS`) recorded the migration on every run - an aggregate
  always returns a row; now the MAX is a derived table. The older shipped
  SQL files use a non-aggregate form and are fine.
- `teacher-classic-arabic.sql`: 7 `teacher_home.look*/try_new_look*` keys.
- Verified: 27 Chromium checks (`classic/e2e-classic.js`: default, switch,
  DB, three pages + query kept, back, new device follows account, way
  back, shared-device student (no detour on later visits), no column/501,
  Arabic, fit at 4 sizes); API checks incl. invalid value and no settings
  row; SQL run 3 times; teacher home 23, teacher pages 88 and one-back 20
  still pass.

### "Game" renamed "Kiosk" (web only - not in this repo)

The user asked for the game style to be called Kiosk, "including the file
name". Delivered as `kiosk-rename-update.zip` (71 web files + the Look
switch's server files; supersedes `teacher-classic-update.zip`).
- **Files**: `scholarship-kiosk.js/.css`, `academics-kiosk.js/.css`,
  `admin-kiosk.js/.css`, `teacher-kiosk.css` (were `*-game.*`); all 42
  pages and 17 page scripts that load or name them updated (the JS hits
  were comments). Globals (`SG`/`AG`/`AX`), CSS classes, translation keys
  (`academics_game.*` etc.) and old zip names are unchanged - never shown,
  and renaming keys would mean re-running every Arabic SQL. The README says
  to delete the 7 old files after uploading.
- **Labels**: the Look switch is **Kiosk** ("Big tiles, one tap at a time",
  `teacher_home.look_kiosk*`) / Classic; the classic home says "Try the
  Kiosk look" (`teacher_home.try_kiosk_look`, `?look=kiosk`). The only
  visible "game" text before was the switch's "Big tiles, like a game".
- **Look value**: `kiosk` / `classic` (`ui-style.js`, `UiStyleController`);
  `game` - a saved value, localStorage record or `?look=game` link from the
  first version - is read as `kiosk` (`norm()` both sides; the API accepts
  `game` and saves `kiosk`). `teacher-classic-arabic.sql` now has
  كشك / "جرّب شكل الكشك" and deletes the 3 old keys.
- Verified: `kiosk/crawl.js` opens all 42 kit pages as their role - every
  js/css loads from `-kiosk` names, no 404, no page errors, SG/AG/AX
  present; a static scan of every page's script/link refs finds none
  missing; Look e2e 29 (adds the legacy `?look=game` + saved `game`
  cases); Academics 104, admin 62, teacher home 23, teacher 88, one-back
  20. Section names above ("... as a game") are kept as history.

### Take Attendance, Kiosk and no scroll (web only - not in this repo)

The user sent two screenshots of the teacher's Take Attendance page: step 1
with its plain rows, and step 3's swipe card on a page that scrolled. The
ask: "make it no scroll on this design also on first image make pick class
and date is interactive kiosk". Delivered as
`take-attendance-kiosk-update.zip`: 3 files in `v2/`, plus
`take-attendance-arabic.sql` with 31 keys. No server or DB change.
- **One screen.** `teacher-attendance.php` is `body.tg-app.ta-app`. Its
  root is `.ta-screen`: stepper, `.ta-body`, then the footer. The fixed
  `.ip-footer` became static inside it. The rules are at the end of
  `teacher-kiosk.css`; nothing above them changed. `.ta-scroll` (locked
  roster, gate list) and the class tiles scroll inside themselves.
- **Step 1** (`renderPickStep`, which replaced `renderPickerRows` and its
  option sheet):
  - "Which day?" is a `.ts-days` strip: the last six days (today =
    "Today"), plus an "Other" chip with the date input laid over it
    (`showPicker()`; a future date falls back to today).
  - "Which class?" is `.tg-class` tiles, sorted by that day's
    `fetchMySchedule` rows (`section_id:subject_id`). Each tile shows its
    start time, "Now" (today, in progress), or "Homeroom".
  - Tiles take the subject colour (from any day's timetable).
  - Tapping a tile ticks it and moves to step 2 after 260 ms. Back keeps
    the day and class, and Continue stays.
  - Day names reuse `student_schedule.day_short_*`. The picked date shows
    "Sep 10" in English and day/month otherwise. Times are wrapped in
    `<bdi>` so Arabic doesn't show "AM 9:00".
- **Step 2:** "How will you take it?" with two big `.method-tile`s (two
  columns from 640px) and a `.ta-picked` class/date pill.
- **Step 3:** `#attendanceContent.ta-att.ta-att-<method>`.
  - The swipe group (progress, card, skip, counts) is centred together.
  - The card stage is `flex: 0 1 330px` (min 150), so it grows on a big
    phone and gives way first on a small one; the avatar shrinks on short
    screens.
  - Phone on its side (`max-height: 500px` + `min-width: 560px`):
    - the step label hides;
    - step 1 puts the days (4x2) beside the tiles (3 columns);
    - swipe: card left, progress / note / counts right;
    - gate: summary card left, list right;
    - locked: banner left, list right.
  - From 900px, the `.ta-days` strip is kept a row; My Schedule turns its
    `.ts-days` into a column there.
- **Bug fixed:** the swipe card's note (pencil) and More buttons never got
  a tap. `onDown` called `setPointerCapture` on the card, so the click
  went to the card. It already did this before. Pointer-downs on a
  `button` are now left alone.
- `take-attendance-arabic.sql` holds the 7 new keys plus the 24 Gate
  Records keys that had no Arabic in the user's
  `MASTER_arabic_translations.sql`, in the same delete-then-insert shape.
- Take Attendance has one look; the Classic/Kiosk switch still covers only
  the home, Enter Grades and My Schedule.
- Verified: 78 Chromium checks (`take-att/e2e-take-att.js`, seeded by
  `seed_teacher.php`, fake clock Tuesday 7:50):
  - strip, sorting, chips, Other/future date, auto-advance, Back;
  - swipes saved as present / absent / late rows in `attendances`, and
    the lock;
  - no page scroll with buttons and labels on screen at 360x660, 390x844,
    768x1024, 1280x800 and 740x360 (pick, method, swipe + note, gate);
  - Arabic.
  - Teacher pages 88 and teacher home 23 still pass. Not tested on the
    real server.

### All new Arabic in one SQL (web only - not in this repo)

The user asked: "now generate all arabic trl in sql base what new".
Delivered as `all-new-arabic.sql`, 1,312 texts in `academic_translations`
(locale `ar`, `school_id` NULL).
- **What "new" means here:** a key the current web files use that the
  user's original `v2` copy never used. `scratchpad/arabic-all/scan.py`
  collects `t('key', 'English')` and `data-i18n*` keys from both copies.
  That finds 1,215 new keys:
  - 633 already had Arabic in the per-update files;
  - 582 had none: Gate Students, Gate Reports, gate tiles and switches,
    and the scholarship / Taqdim / Translation / university pages, which
    had never had Arabic.
- The 582 were written by hand (`ar1/ar2/ar3.py`), plus the dynamic keys
  `gate_reports.status_*` (6) and `scholarship_detail.gender_*` (2). The
  Gate Reports legend keeps the Latin P/L/A/F letters, because the grid
  shows them.
- It also carries every key from the 8 earlier Arabic files (quran-wizard,
  quran-mode-days, teacher-game, academics-game, admin-game, teacher-home,
  teacher-classic, take-attendance). That includes their dynamic and old
  keys, such as `student_schedule.day_short_*`, `fee_game.title_*` and
  `common.back`.
- It deletes the three pre-Kiosk `teacher_home.look_new*` /
  `try_new_look` rows.
- It never includes a key from the user's own Arabic SQL files, so their
  wording is untouched. It uses the same delete-then-insert shape, in
  multi-row INSERTs grouped by prefix.
- `build.py` checks that every `{placeholder}` matches the English and
  that every value has Arabic letters. The only exceptions are
  `Excel`, `PDF` and `{type} {n}`.
- **Follow-up ("generate the 967 old ones too"):** the same file now also
  carries 966 older texts on the pages the updates changed that had no
  Arabic in the user's files (`changed.py` -> `old_on_changed.json`,
  written in `o1..o4.py`; `some.key` is only a code-comment example, so
  it was skipped). The file is 2,278 texts. The chat tip keeps its
  `[FILE:...]` / `[CREDS:...]` / `[LOCK_NOTE:...]` / `[UNLOCK]` codes as
  typed. The ~700 remaining old gaps on untouched pages are still not
  included.
- Some headings are hard-coded English in the user's own page code (no
  `t()`, e.g. `student-dashboard.js` "My Learning" / "Identity &
  Documents"). SQL can't translate those; wrapping them in `t()` is a
  code change.
- Verified on the test DB:
  - run twice: 1,312 rows, each once, byte-identical to the source;
  - 11 pages opened in Arabic via the saved setting as admin, student and
    teacher (Gate Students, Gate Reports, admin home, Classes, Scholarship
    Browse / Documents / My Applications / Saved, student home, Take
    Attendance, teacher home): rtl, and no new or old key still showing
    its English (`arabic-all/check-pages.js`).

### Hard-coded English wrapped in t() (web only - not in this repo)

The user asked (my recommendation from the "what's next" list): "Fix the
hard-coded English headings ... 'My Learning' and 'Identity & Documents' on
the student dashboard are typed straight into your page code, so no SQL can
translate them." Delivered as `hardcoded-english-update.zip`: 36 files in
`v2/` (one new, `public-i18n.js`), `hardcoded-english-arabic.sql` (376 texts),
an updated `all-new-arabic.sql` (2,654 texts, contains the 376), a README and
the test. No server or database-structure change.
- **How they were found.** `scratchpad/i18n-fix/scan_all.js` (acorn) lists
  multi-word English string literals and HTML text that are not the 2nd
  argument of `t()`. Most hits were false positives (enum label maps that
  already call `t()`, CSS, internal errors, sample data); the real ones were
  converted, ~380 keys in `reg.json`.
- **Signed-in pages** use `t('key', 'English')` (or `data-i18n` on the PHP
  shell's leaf elements). Objects built at load time need getters because the
  locale bundle arrives later. Done: student dashboard group headings,
  dashboard.js shared cards (setup checklist, enrollment status, school
  profile, orphan reports), registrar/admin cards, Offline & Sync
  (`sync_status.*`), the offline bar (`pwa.*`), the cached-item names
  (`offline_labels.<english_slug>`, looked up by `labelText()` in
  offline-data.js), Subscription, Languages (superadmin), translation
  review/wizard, small ones (Surah N, Yesterday, Document, Face, alumni and
  placeholder dashboards, Private Team/Links, pre-registration errors).
  `pwa.js` and `offline-data.js` load where `t` may not exist, so they call it
  through a `typeof t === 'function'` guard.
- **`data-i18n-aria`** is new in `applyDomTranslations` (dashboard.js) for
  icon-only buttons. A `data-i18n` element must be a leaf whose text nothing
  else rewrites (JS that sets the text later, e.g. an error box or the chosen
  Quran section, must not carry it: a language change would put the original
  back - the section label drops its attribute when a section is picked).
- **Pages before sign-in.** `login.js`, `student-preregister.js` and
  `alumni-registration.js` already had their own `t()` and a small Arabic
  table (`LOGIN_AR_FALLBACK`, `AR_FALLBACK`), no server bundle needed: the new
  texts were added to those tables (and login.php's footer + "Get started"
  sentence got `data-i18n`). `forgot-password`, `reset-password` and
  `private-register` had no translation code: new **`public-i18n.js`**
  (`window.t`, fills `data-i18n`, `data-i18n-placeholder`, `data-i18n-aria`,
  sets rtl) reads the language the browser last used
  (`muslimedu_locale` + cached `muslimedu_locale_bundle`) and has its own
  Arabic table for those three pages, generated from `reg.json` by
  `build_hc.py` between `/*AR-BEGIN*/` and `/*AR-END*/`.
- **Left in English on purpose:** public marketing/legal pages, the certified
  translation templates (`translation-templates-*.js`), dashboard.js's
  "could not load" splash (runs before translations exist), `offline.html` /
  `offline-status.js`, values sent to the server, CSV headings, surah names.
- SQL: `build_hc.py` leaves out any key already in the user's own SQL files
  (6 keys, e.g. `common.cancel`, `admin_dashboard.your_school`) and checks
  `{placeholders}` match the English and every value has Arabic letters. Both
  files run twice on the test DB with one row per key.
- Verified in Chromium at 390px (`i18n-fix/e2e-i18n.js`, 31 checks, English
  and Arabic): student dashboard, Offline & Sync (student, teacher; cached
  item names; offline bar; Online/Offline banner), Subscription, Languages
  (superadmin), forgot/reset password (incl. the validation and "link sent"
  messages), private registration, sign-in footer - rtl, every text in
  Arabic, no English left, no page errors. Still passing: teacher pages 88,
  teacher home 23, Take Attendance 78, Look switch 29, one-back 20, admin 62,
  Arabic page check 11. Not tested on the real server.

### Kiosk pages: iOS large-title header (web only - not in this repo)

The user sent a screenshot of My Schedule (back button and title squeezed into
one row, 10px from the top) and asked for the Apple layout: the back button
alone on the top row, the title below it, with margins, on every Kiosk page.
Delivered as `kiosk-header-update.zip`: `teacher-kiosk.css`,
`academics-kiosk.css`, and the 16 Kiosk pages with only their stylesheet link
bumped (`teacher-kiosk.css?v=4`, `academics-kiosk.css?v=3`). No server change.
- `body.tg-app` / `body.ag-app` `.page-header` is `display: block` again: back
  button row (40px button, `max(8px, safe-area)` top padding), then
  `.page-title` 30px / line-height 1.1, 6px apart, on the screen's 16px side
  margin (the same layout as `renderUtilHeader`'s normal pages, just static -
  these screens don't scroll, so no frosted fade). Header 95px at 390x844.
  `max-height: 720px`: 36px button, 26px title (81px). Phone on its side
  (`max-height: 500px` + `min-width: 560px`): one compact row again, back +
  20px title - iOS collapses large titles in landscape too.
- Room for it: My Schedule hides `.ts-day-title` under 900px height (the day
  strip and hero already show the day); Enter Grades hides the hero's
  `.sg-bar` at 720px or less (Graded / To go already say it).
- The page never scrolls. A 4-class day fits at 390x844 and 1280x800; at
  360x660 it scrolls inside `.ts-scroll`, with the class in progress in view.
  `teacher-game/e2e-teacher-game.js` checks exactly that at 360x660.
- Verified: header measured on the Kiosk pages at 360x660, 390x844, 768x1024
  and 740x360 (no page scroll anywhere); teacher 88, Take Attendance 78,
  Academics 104, admin 62, one-back 20, teacher home 23, Look switch 29 all
  pass. Not tested on the real server.

### Kiosk pages: minimal, no shadows (web only - not in this repo)

The user asked to remove the "3D shadow and shadow" and make every Kiosk page
minimal. Delivered as `kiosk-flat-update.zip`: the four kit stylesheets and
the 43 pages that load them (stylesheet links bumped: `scholarship-kiosk.css?v=3`,
`academics-kiosk.css?v=4`, `admin-kiosk.css?v=4`, `teacher-kiosk.css?v=5`;
`student-schedule.php` and `scholarship-applications.php` also lose the
shadows in their inline styles). No server change.
- `scratchpad/flat-fix/flatten.py` (re-runnable from `flat-fix/before/`):
  removes every `box-shadow` from the kits (~110) and the two inline blocks,
  drops `box-shadow` from transitions, and turns the `:active`
  `translateY(2-3px)` "sink" (made for the 3D bottom edge) into
  `scale(.97)`. `sgPulse` / `taNow` (a growing halo) now pulse opacity.
- Shadows that marked a state became flat equivalents: Subjects colour
  swatches (`outline`, selected = 3px outline in the colour), Enter Grades
  `.tg-rrow.now` (2px inset outline), `.tg-mark:focus` (outline in the mark
  colour), the dragged enrollment stage (`.ag-jghost`, emerald border), and
  the avatar ring (`.ax-ava` 2px white border; none in `.ax-pick` /
  `.ax-post-top`; 3px in the panel head badge).
- A "Minimal: flat" block at the end of `scholarship-kiosk.css` and
  `teacher-kiosk.css` (Take Attendance loads only the latter):
  `*, *::before, *::after { box-shadow: none !important; text-shadow: none
  !important; }` - also flattens the shared dashboard.css parts on these
  pages and inline styles - plus a white back button with a 2px #E6ECEA
  border (the frosted glass one was only visible thanks to its shadow) and
  a `:focus-visible` outline. Gradients stay (brand colour, not a shadow).
- Rounded outlines need Chrome 94+ / Safari 16.4+; older browsers draw the
  swatch rings square.

### Kiosk dashboard: parallax greeting card (web only - not in this repo)

The user sent a screenshot of the teacher home's green greeting card and asked
for a parallax scroll effect on it, "in dashboard ui all applys if kiosk mode
ui". Delivered as `kiosk-parallax-update.zip`: `academics-kiosk.css`,
`academics-kiosk.js`, `teacher-dashboard.php` (`academics-kiosk.css?v=5`,
`academics-kiosk.js?v=3`). No server change.
- Opt-in: `.ag-main.ag-px` (only `teacher-dashboard.php` has it). `.ag-main`
  becomes the scroller and `.ag-top` (the card) and `.ag-list` (the tiles)
  scroll together; the tiles area is `position: relative; z-index: 2` with the
  page colour and rounded top corners, so it slides over the card.
- `AG.top()` (academics-kiosk.js) wires one passive scroll listener per
  `.ag-px` and writes `--py` (px scrolled) and `--pp` (0-1 through the card's
  height) on `.sg-hero`, in one rAF. CSS does the rest: card
  `translateY(--py * .5)` (so it moves at ~half speed), `scale(1 - .08 pp)`,
  `opacity(1 - .9 pp)`; the `::after` highlight, `.sg-hero-row` and
  `.sg-hero-stats` get their own factors (.25 / .12 / .2) for depth. Re-render
  of the card (schedule arriving) re-applies at once.
- Gotcha: `.sg-pop` (`animation: sgPop ... both`) keeps holding its final
  `transform` and `opacity`, which silently overrode the parallax. `.ag-px
  .sg-hero` uses `animation-fill-mode: backwards`.
- Not applied: phone on its side (card is beside the tiles, `max-height: 500px`
  + `min-width: 560px` restores the old layout) and `prefers-reduced-motion`.
  The other Kiosk pages keep their pinned card (their search / filters sit
  under it); the other role dashboards are not Kiosk-look pages (their
  `.hero-bg` still uses `wireParallax`, factor 0 = fade only).
- Verified: scrollTop 0 / 40 / 100 / 180 at 390x844, 360x660, 1280x800 (card
  at -10 / -40 / -80 where the tiles are at -40 / -90 / -170, opacity 1 ->
  .79 -> .48 -> .1, back to exactly 0 at the top, no page scroll, no errors);
  740x360 unchanged.

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

   **Fixed signing key (the fix for "every update wipes the phone").** CI
   used to sign with a fresh debug key each run, so each APK needed an
   uninstall first - wiping the phone's cards, faces and unsent scans. Now
   both APKs are signed with one permanent key: a 4096-bit RSA PKCS12
   keystore (alias `gate`, valid 100 years) generated for the user and
   **never committed** (the repo is public; `*.p12`/`*.jks`/`*.keystore` are
   git-ignored). CI reads it from two repository secrets,
   `GATE_KEYSTORE_BASE64` (the keystore, base64; line breaks/spaces from a
   paste are stripped) and `GATE_KEYSTORE_PASSWORD` (store = key password),
   writes it to `$RUNNER_TEMP` and passes `GATE_KEYSTORE_FILE` to Gradle
   (`gateKeystore` in `app/build.gradle.kts`, signing config `gate`). The
   workflow's `GATE_CERT_SHA256` is the key's public certificate fingerprint:
   "Check the APK signatures" (apksigner) fails the build if the secrets
   produce any other key, and without the secrets (a fork, or before they
   were added) the build falls back to the debug key and uploads
   `apk-release-NOT-FOR-GATE-PHONES` instead of `apk-release-install-this`,
   so a wrong-key APK can't be mistaken for the real one. Android also
   needs the new APK's versionCode >= the installed one's - it is the CI run
   number, which only grows. **Lose the keystore and the next update wipes
   the phones again** - the user was told to keep a private backup; GitHub
   secrets can't be read back. The user added both secrets on 2026-09-26.
   The switch itself needs one last uninstall
   per phone (the old APKs carry old random keys): upload everything first,
   then reinstall; cards and parent numbers come back with the student
   download, faces must be registered again that one time.

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

**Last Updated**: 2026-09-26  
**Created by**: Claude Code  
**Status**: Active Development
