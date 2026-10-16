# Android client

Open this directory (`android/`) in Android Studio. The FastAPI backend remains
at the repository root (`app/`, `pyproject.toml`, `dev.ps1`); see the root README
for backend setup.

Android Studio can create your local SDK configuration in `local.properties`.
Machine-specific settings, build outputs and signing keys are excluded from Git.
The Gradle wrapper and version catalog are included.

The API base URL is configured in
`app/src/main/java/com/example/helloword/api/RetrofitClient.kt`.
Set it to the address of your running backend when using a different network.

Home's Recent Learning card reads `GET /video/history?limit=1` for the current
user's latest uploaded movie, independently of local upload drafts. It refreshes
whenever Home resumes, every 3 seconds while that movie is pending/processing,
and every 30 seconds otherwise; requests stop while Home is not resumed. Errors
show an explicit retry state and retry after 10 seconds, without displaying an
old local draft. The card shows the server's filename, upload date and status,
and loads the authenticated server cover. Completed movies open flashcards by
movie ID; failed movies open My Movies. Selecting a file without uploading it
does not create a Recent Learning entry. Upload drafts still serve the upload
screen and background notifications. `HomeLearningStateTest` covers server/local
disagreement, empty/error/retry states, status polling, returning after deletion,
account changes and the existing one-time completion navigation.

Two NETEM wordbooks are now available in My Books and the video wordbook picker:
the full 5530-entry list and the 2444-entry subset with mixed-exam frequency >= 40.
The backend must first run `python -m app.import_netem` in its virtual environment.
Each book supports browsing, whole-book matching and selected-word matching;
selection preferences and upload drafts retain the book ID. Source and data
license information are available from the word list screen.

Returning from the wordbook/word picker to Upload updates the existing READY
draft's vocabulary while retaining its selected video and metadata. The change
is persisted and emitted to the screen through VideoUploadStore. Submitted or
uncertain uploads keep their original scope; screen recreation does not reapply
old Intent parameters. UploadDraftVocabularyTest covers book changes, selected
word changes, persistence, recreation and protection of other task/account states.

From this directory, run `gradlew.bat testDebugUnitTest assembleDebug` on Windows
to run unit tests and build the debug APK with a configured Android SDK.

The AI assistant input now calls `POST /ai/chat/stream` and displays real SSE
text increments. Restart the backend after updating it, then rebuild/run this app.
Use Send to ask, the same button to stop, long press a message to copy, and the
top-right plus button to begin a new conversation. Flashcard word/sentence context
is included when present. Conversation history is limited to the current page;
screen rotation preserves received messages but cancels an in-progress reply.

Profile's flashcard initialization switches now persist per account on this
device. Meaning and sentence visibility are independent; the initial defaults
match the profile UI (meaning hidden, sentence shown). Opening a flashcard or
moving to the previous/next word reapplies those defaults. Tapping either content
area reveals/hides only that area for the current card, without changing the
saved switches. Missing sentences retain the existing unavailable message.

Flashcards also support server-backed word favorites via the star button in the
card header. Status is refreshed on each card and when returning to the screen;
in-flight writes cannot mark another word as saved. API and storage details are
documented in `../docs/favorites.md`.

The flashcard page scrolls as a whole with a visible vertical scrollbar, including
the player, word card and navigation buttons. The favorite button occupies its
own row. Meanings/sentences and their tap-to-show labels alternate visibility in
vertical layouts, rather than overlaying each other. Long text grows naturally;
jumping to a sentence also scrolls the player into view. FlashcardNavigationTest
checks large-font, long-content spacing and that bottom navigation stays reachable.

Flashcard recovery:
Local video document URIs are retained per account and movie, independently of
the latest upload draft. The existing latest draft is migrated automatically.
If its local URI cannot be opened, the player tries the authenticated
`GET /video/{movie_id}/file` copy (the backend must be reachable). The card also
offers a system document picker to rebind the original phone video and persist
read access. Pick the same original video so sentence timestamps still match.
If both copies are unavailable, words and saved sentences remain usable.

On a device with user data, install debug APK updates with `adb install -r`
and run instrumentation directly; do not uninstall or clear app data.
`FlashcardNavigationTest` covers defaults, measured card layout, and retained
per-movie file mappings. Its optional `existingMovieRendersAndPlays` smoke test
accepts `-e recoveryMovieId <id>` and checks real sentence visibility and playback
without editing the account or movie data.

My Movies supports pull-to-refresh at the top of the list, including the empty
state. There is no separate refresh button; Load More appears only when a cursor
is available. A failed refresh preserves the displayed rows. Each movie has a
Delete action with confirmation, calling authenticated `DELETE /video/{movie_id}`.
The backend deletes the owning user's movie and movie_words in one transaction;
the analysis worker checks the same locked movie row before saving results.
After success the app clears its URI association, matching upload session and
flashcard cache. Phone originals and backend uploaded files are retained.
MyMoviesTest exercises cancellation, failed/successful deletion, empty-state
refresh, refresh failure and pagination with a fake API; backend integration
tests use a disposable MySQL database and do not delete users' real movies.

On this workstation, the old JAVA_HOME points to a missing directory. To build
with the existing Android Studio JBR:

```powershell
$env:JAVA_HOME = 'D:\Android studio\jbr'
.\gradlew.bat assembleDebug
```

Validation: the debug APK builds successfully. The Gradle unit-test worker cannot
start on this workstation because its argument-file classpath includes the
non-ASCII user directory (`GradleWorkerMain` not found). All 35 compiled JVM tests,
including the SSE parser tests, passed using JUnitCore directly with that same
test classpath supplied as a command-line argument instead of an argument file.
After the NETEM wordbook integration, the same direct JUnit run passes 38 tests;
the backend suite including disposable-MySQL integration checks passes 182 tests.
