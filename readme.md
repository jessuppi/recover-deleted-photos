# Recover Deleted Photos

## Changelog

### 0.9.1
- prevent double starts and double navigation from permission callbacks or rotation
- permission screen shows “Open Settings” when storage access is permanently denied
- least-privilege: request video access only when enabled via SCAN_VIDEO build flag

### 0.9.0
- added Android 13+ media permissions with legacy fallback; scans start only after access is granted
- added “permission needed,” “no media found,” and “scan error” screens with clear primary actions (grant, retry, home)
- introduced a dedicated state container and toggle between state and scan views
- during states, disabled scan/cancel; actions route back to scan or home via app nav path

### 0.8.3
- cancel button now actually stops the scan mid-process rather than only closing the screen
- cancel button now stops animations and returns home via the app nav path (not back actions)
- cancel responds faster during large scans thanks to per-item cancellation checks and periodic yielding
- canceling no longer shows a failure toast, avoiding confusion when stopping on purpose

### 0.8.2
- slower scan count-up with a brief pause before navigation; softer pulse animation

### 0.8.1
- fix: prevented scan-screen crash by launching the count animation on the main (UI) thread
- fix: initialized total count label to "0" in layout (no formatted placeholder at inflate)

### 0.8.0
- replaced the progress bar with a single animated total file count
- added a subtle pulse halo behind the count for visual feedback

### 0.7.8
- scan: “Found X files” now increments in lockstep with the visible progress bar
- scan: smoother, slower, truth-based bar (higher granularity + time-throttled updates)
- ui: removed right-edge sliver on some devices (explicit track/indicator colors, LTR, track thickness)

### 0.7.7
- progress bar now reaches 100% before navigating, with a brief 300ms pause at completion
- fixed progress bar track colors (removed blue sliver)

### 0.7.6
- added live “Found X files” under the progress bar
- progress bar reflects true percent (found/total) with throttled updates

### 0.7.5
- fixed navigation so Back from Results now goes directly to Home (no scanning flash or double-tap) replacing the incomplete 0.7.4 patch
- cleared results only when leaving Results for proper privacy without breaking scans

### 0.7.4
- clear scan results on exit/cancel to prevent bounce-back and improve privacy
- added a subtle background highlight for selected items in results

### 0.7.3
- added advanced sorting options: Date (Newest/Oldest), Size (Largest/Smallest), Name (A–Z/Z–A)
- automatically scrolls results to top when changing sort order
- polished layout spacing and spinner prompt for better UX

### 0.7.2
- added sorting filter dropdown (date, size, name)
- recover button now enables when items are checked (not just long-pressed)

### 0.7.1
- fixed missing LinearLayoutManager so scan results and thumbnails display again
- fixed click handling and selection updates in results list

### 0.7.0
- disabled edge-to-edge layout for cleaner toolbar appearance  
- added multi-select support and recover selected button  
- added sorting options for date, size, and name  

### 0.6.4
- fixed results screen showing blank by setting a layout manager
- added empty state when no media is found

### 0.6.3
- prevent scan screen from disappearing by guarding lifecycle and navigation in scan fragment
- handle errors during scan without closing the app

### 0.6.2
- fixed issue where the app returned to the start screen after scanning
- improved scan and navigation flow for smoother and more reliable behavior

### 0.6.1
- fixed crash after scan by moving file size formatting to ui
- updated results fragment for stability and compatibility

### 0.6.0
- added media permissions for Android 13+
- upgraded Material Components to 1.13.0 for DynamicLight support
- expanded Material theming structure and files
- added navigation with home, scan, and results screens
- replaced placeholder layout with fragments + viewbinding
- new coroutine-based media scanner and progress ui
- added coil for image thumbnails

### 0.5.2
- commented out filter chips include in activity_main.xml to prevent crash

### 0.5.1
- removed explicit styles in include_filter_chips.xml to prevent crash

### 0.5.0
- added Top App Bar using MaterialToolbar with app title
- added static filter chips row (Photos, Videos, Audio) for future filtering
- introduced activity_main.xml layout composing toolbar, chips, and content container
- updated MainActivity to load activity_main.xml

### 0.4.0
- migrated app to Material 3 theme for modern UI styling
- changed to automatic system dark mode support
- switched to XML-based layout (view_empty_state.xml) for proper theming
- added placeholder empty state screen with icon and text
- defined minimal theme colors and theme structure

### 0.3.0
- updated to target API level 35

### 0.2.0
- first signed release (AAB)
- updated package name to `top.thinapps.recoverdeletedphotos`
- improved MainActivity to center text and use app name string
- optimized resource and theme files (including launcher icon background)
- optimized GitHub Action workflow for signed releases
- removed unnecessary Gradle wrapper files from the repo

### 0.1.0
- initial test release (unsigned)
- basic project structure with GitHub Actions support
- app icon and minimal `MainActivity` with empty layout
  
