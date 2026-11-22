# SwipeCull

Swipe through the photos and videos in a folder:

- **Swipe left** (or tap the red button) → the file is moved into a "marked for deletion" folder
- **Swipe right** (or tap the green button) → file stays where it is, on to the next one
- **Undo** puts the last swipe back (moves the file back if it was marked)
- Tap a video to pause/resume. Videos start muted (🔇 button to unmute). A seek bar under the video lets you scrub.
- Optional **keep folder**: swipe-right files (by default only videos) are moved there instead of staying put.
- **Tags** (private to the app): tap "+ Tag" under the photo/video to make a tag, tap chips to put tags on/off the current file. Later open **Tagged files** (start screen or ⋮ menu), pick a tag and move every file with that tag to a folder in one go. From there you can also **Review** a tag's files (or all tagged files) with the normal swipe flow, using the same delete/keep folders. Tags can be renamed and reordered (▲ ▼) there; the chips follow that order.
- **Shuffle** switch (start screen, or ⋮ menu mid-session): random order instead of newest-first.
- ☆ button on the card: asks Android to mark the file as a gallery favorite (system confirmation dialog).

Newest files come first. Files you swipe right on are remembered and hidden next time you open
the same folder (use "Review the kept files again" to bring them back).

By default marked files go to a `_marked_for_delete` subfolder inside the folder you picked
(created on the first swipe left). You can pick a different destination folder on the start screen.
When you're done, delete the folder contents from the Files app, or use **Delete marked files…**
in the app's menu (only available for the default subfolder, with a confirmation).

No storage permissions are used: you grant access to one folder through Android's folder
picker. Android doesn't let apps open the storage root or the top-level Download folder, so pick
a subfolder (e.g. DCIM/Camera, Pictures/Screenshots).

## Build & install

1. Install Android Studio (current stable) and open this folder (`SwipeCull`). Let Gradle sync.
   If Studio offers to upgrade the Android Gradle Plugin, accepting is fine.
2. On the phone: Settings → About phone → Software information → tap *Build number* 7 times,
   then Settings → Developer options → enable *USB debugging* (or *Wireless debugging*).
3. Connect the phone, pick it in the device dropdown, press ▶ Run.

Or build an installable APK: *Build → Build APK(s)*, then copy
`app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow "install unknown apps"
for your file manager when prompted).

## Code map

- `MediaStorage.kt` – folder listing, move/copy/delete via the Storage Access Framework
- `SwipeViewModel.kt` – session state, swipe/undo logic, serialized file-operation queue
- `SwipeCard.kt` – draggable card with KEEP / DELETE overlays
- `Screens.kt` – setup, review, done screens; image (Coil) and video (Media3 ExoPlayer) display
