# Recover Deleted Photos

## Changelog

### 0.14.4
- set grid view as the default layout on scan results
- restored subtle selection overlay highlight in list mode for consistency
- corrected List thumbnail sizing (smaller 72dp center-crop)

### 0.14.3
- fixed invisible grid/list icon on light toolbar by applying runtime tint
- restored thumbnail rendering by assigning the item binding in list/grid ViewHolders

### 0.14.2
- fixed regression introduced in 0.14.1 where grid/list toggle disappeared and thumbnails failed to render  
- removed redundant overflow menu and restored proper layout toggle behavior  
- grid/list switch now uses dedicated top-right icon only (no dots or select/clear actions)  
- retained separate sort dropdown bar with original functionality  
- minor XML formatting cleanup for icon vectors

### 0.14.1
- decoupled the list/grid toggle from sorting so each works independently
- replaced the triple-dot overflow icon with standard list and grid icons
- fixed the menu item to always show the toggle icon using the proper namespace
- updated ResultsFragment to handle layout switching separately from sorting, including icon refresh and layout updates
- cleaned up adapter and menu handling code for consistency

### 0.14.0
- added list / grid view toggle on results screen for flexible browsing
- new grid layout with square thumbnails, overlaid checkboxes, and trash badges
- preserved all existing sort and selection actions in the top-right menu
- maintained current snackbar messages and recovery logic for consistent UX

### 0.13.4
- snackbar message now anchors above the recover button for cleaner visual alignment

### 0.13.3
- added temporary "Recovering..." state to button for clear progress feedback
- recovery button reverts to normal after completion while keeping existing snackbar behavior
- improved user experience during longer recovery operations

### 0.13.2
- removed unreliable “View Files” action after recovery to prevent file picker issues
- added clear recovery confirmation message showing the destination folder name

### 0.13.1
- added snackbar popup after successful recovery with “view files” button that opens recovered folder or first recovered file as fallback
- recover button now disables during copy process to prevent duplicate operations
- clears selections and unhighlights items after recovery

### 0.13.0
- recover button copies photos/videos to **Pictures/Recovered** and audio to **Music/Recovered** (no extra prompts)

### 0.12.0
- removed SAF / hidden /.nomedia scanning; MediaStore-only scan (faster, simpler)
- still includes trashed media via MediaStore; “Trash” badge retained
- removed “Hidden” labeling and all SAF code/permissions

### 0.11.1
- added small badges labeling trashed and hidden media in results
- minor internal cleanup in ResultsFragment adapter binding and layout handling

### 0.11.0
- added full-device scan combining MediaStore and SAF results with deduplication
- SAF crawl is optional with one-time user grant and is non-blocking
- includes trashed media on Android 11+ using QUERY_ARG_MATCH_TRASHED
- includes hidden and .nomedia folders via DocumentFile (skips inaccessible /Android and external SD cards)
- fixed MediaItem crash by removing context-based date formatting
- refined scan strings UI text and improved cancel handling and pulse cleanup

### 0.10.2
- removed media-type radios from the scan screen (now it lives only on Home)
- show type-specific header (“Total photos/videos/audio on device”) during scanning

### 0.10.1
- media-type selection moved to Home screen (Photos / Videos / Audio)
- scan now uses a single type nav arg from Home; no in-scan toggles
- removed unused SCAN_VIDEO build flag and related code cleanup

### 0.10.0
- added media-type picker on scan: Photos / Videos / Audio
- scanner now queries Images, Video, and Audio files with accurate totals
- permission prompts align with the selected media type (refined behavior)
- updated layout and strings for the new selector and clearer “no media” messaging

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
  
