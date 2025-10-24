# Recover Deleted Photos

## Changelog

### 0.17.6
- Up (back) arrow in the toolbar on both Scan and Results screens now executes the proper back-stack and cancellation logic
- replaced Snackbar notification for denied permissions on the Home screen with "Grant Access in Settings" CTA when access is needed

### 0.17.5
- "Cancelling..." state now uses a neutral gray background (`recover_button_disabled_bg`) to clearly differentiate it
- removed the continuous "breathing" alpha animation from the Cancel button during active scanning to reduce visual distraction
- implemented a pre-emptive API check on the Home screen to block functionality and display a "Not Supported" message if the device is running an OS older than Android 13
- reordered the media type selection to appear before the "Start Scan" button, enforcing a "Configuration → Action" flow

### 0.17.4
- removed the redundant `app:iconTint` attribute from `menu_results.xml`
- updated Snackbar action text on Home to the more explicit "Grant Access in Settings"
- Cancel button now temporarily displays as a solid contained button when showing "Cancelling..." state

### 0.17.3
- resolved (further) toolbar icon visibility issues in both light and dark modes
- restructured `themes.xml` to use Material 3 toolbar overlays in both light and dark modes
- applied unified `toolbarStyle` reference for proper tint and contrast across all themes
- removed redundant color attributes from `activity_main.xml` to rely fully on theme-driven styling

### 0.17.2
- resolved persistent issue where toolbar action menu icons (e.g., list/grid toggle) appeared white-on-white in light mode
- refined toolbar color handling in `activity_main.xml` for consistent tint behavior across light and dark modes
- cleaned up redundant and conflicting style attributes from `themes.xml`

### 0.17.1
- removed two obsolete layout files (`include_filter_chips.xml` and `view_empty_state.xml`) to simplify project structure
- corrected menu icon visibility in light mode by explicitly defining `actionMenuIconTint` and `actionMenuTextColor` in base and night themes

### 0.17.0
- centralized toolbar and navigation handling in `MainActivity` with a single shared `MaterialToolbar`
- removed fragment-level toolbar logic and deprecated `setHasOptionsMenu` usage in favor of a shared `withMenu()` helper
- unified title, up button, and menu behavior across all screens for simpler and more consistent navigation
- streamlined layouts: flat `activity_main.xml` with `MaterialToolbar` + `FragmentContainerView`; cleaner `menu_results.xml`

### 0.16.14
- added new `values-night/colors.xml` for dark mode colors
- updated toggle view icons to reference `@color/icon_list` and `@color/icon_grid`
- icons now auto-switch between light and dark colors without tint logic

### 0.16.13
- ScanFragment: changed pulse number color to md_on_surface (dark) for better contrast
- ScanFragment: removed green glow; added subtle final scale pulse highlight instead

### 0.16.12
- fixed pulse animation transparency to remove square background artifact
- fixed pulse number color to use md_blue_700 base with md_green_A400 glow during dwell

### 0.16.11
- ScanFragment: fixed pulse visuals by clipping to oval and adjusting gradient so no square outline shows
- ResultsFragment: fixed results screen navigation so toolbar up and system back go Home

### 0.16.10
- ScanFragment: fixed toolbar back arrow to safely trigger the same Cancel behavior without crashing
- added smooth “Cancelling...” feedback animation and short dwell before returning to Home screen

### 0.16.9
- ScanFragment: Hardened Cancel flow with lifecycle-aware navigation to avoid crashes
- ScanFragment: System back now mirrors Cancel (stops scan, clears results, and returns to Home)

### 0.16.8
- ScanFragment: Cancel button now reliably returns to Home (popBackStack with safe navigate fallback)
- polished scanning screen with gradient pulse ring, neon-green dwell glow, and breathing fade on Cancel button

### 0.16.7
- ScanFragment: now waits until RESUMED before navigating to results for safer transitions
- ScanFragment: clears all previous `vm.results` at start and on cancel to prevent cached data

### 0.16.6
- ScanFragment.kt: fixed results handoff so scan results now appear correctly
- ScanFragment.kt: adjusted final count animation to dwell while neon green before navigating

## 0.16.5
- ScanFragment.kt: restored slow in-progress counter via lightweight ticker (no animator churn)
- ScanFragment.kt: single smooth final animation to total + neon-green highlight

## 0.16.4
- ScanFragment.kt: stabilized progress updates (throttled text updates during scan to avoid animator churn/crashes)
- ScanFragment.kt: single smooth final count animation after scan completes
- UI: neon green final count (md_green_A400) for clearer “found files” highlight

### 0.16.3
- ScanFragment.kt: made MediaScanner calls safer to avoid scan errors or crashes

### 0.16.2
- fixed “Scan error” issue by validating permissions before launching scan
- added safe handling around MediaScanner to prevent false scan failures

### 0.16.1
- fixed crash by adding `androidx.interpolator` dependency for animation compatibility

### 0.16.0
- Android 13+ only: disabled automatic scan on Android 12 and below  
- added “Not supported” UI state with proper localized strings  
- ScanFragment.kt: replaced magic API number with `Build.VERSION_CODES.TIRAMISU`  
- ScanFragment.kt: simplified permission logic into a single launcher using scoped `READ_MEDIA_*` permissions  
- ScanFragment.kt: added safe binding helper to prevent async crashes  
- ScanFragment.kt: cleaned constants and reorganized helper methods for readability  
- ScanFragment.kt: replaced `launchWhenResumed` with `repeatOnLifecycle` for modern lifecycle handling  
- ScanFragment.kt: improved lifecycle handling by pausing animations onStop and resuming onStart  

### 0.15.8
- MainActivity.kt: cached `NavController` instance to avoid repeated lookups and reduce risk of null pointer errors
- MainActivity.kt: added null-safe `NavHostFragment` lookup with early return to prevent potential crashes if layout id changes
- ResultsFragment.kt: switched to explicit `android.view` imports (no wildcard) for clearer tooling and style consistency
- ResultsFragment.kt: replaced `java.lang.Math` with `kotlin.math` (`log10`, `pow`) in `formatSize()` for idiomatic Kotlin
- reformatted some files to standard Android Studio style and added concise inline comments

### 0.15.7
- MainActivity.kt: replaced `findNavController` lookup with robust `NavHostFragment` method to prevent startup crashes
- HomeFragment.kt: simplified permission handling using a single launcher and `pendingType` for smoother flow
- HomeFragment.kt: added settings action in Snackbar for users with denied permissions
- HomeFragment.kt: added null-safety and lifecycle guards to prevent crashes if view is destroyed

### 0.15.6
- MainActivity.kt: simplified navigation setup using `findNavController` and `AppBarConfiguration` for cleaner up button behavior
- MainActivity.kt: replaced persistent view binding property with a local variable for reduced boilerplate

### 0.15.5
- Recovery.kt: added `DATE_TAKEN` field for image/video recovery to preserve original timestamp
- Recovery.kt: specified `"w"` mode in `openOutputStream` for explicit write intent
- Recovery.kt: no logic or behavior changes elsewhere, maintains scoped storage compliance

### 0.15.4
- build.gradle: removed the kotlin dependency alignment block and now rely on kotlin bom only
- MediaItem.kt: replaced `DateFormat` with `SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)`

### 0.15.3
- fixed API < 26 crash with legacy query signature and unified `resolverQuery` helper
- improved performance with paging, throttled progress, `yield()`, and cancellation support
- filtered zero-size and MIME-less rows, excluded pending, included trashed on API 30+
- added try/catch for query errors and kept `scan()` behavior fully compatible

### 0.15.2
- fixed trash badge visibility in grid and list (shows for trashed files)
- set sort dropdown horizontal padding to 0dp for pixel-perfect alignment with tiles

### 0.15.1
- aligned sort bar and results grid with consistent 16dp horizontal padding
- restored "Recovering..." feedback text and button disable during recovery
- added hardcoded recover button colors (blue active, gray disabled, white text)

### 0.15.0
- `Recovery.kt` now forces non-null filenames using `ifBlank` fallback
- skips zero-byte files with `item.sizeBytes == 0L`
- expands MIME detection to cover modern formats (HEIF, AVIF, Opus, FLAC, etc)
- uses `Locale.ROOT` for consistent MIME and extension checks across locales
- improves I/O safety with stronger null and exception handling for streams
- `ResultsFragment.kt` simplifies null-safe bindings and sorting logic
- recovery process now crash-proof against null filenames and empty media on Android 10+

### 0.14.9
- set `android:allowBackup` to `false` to disable unnecessary data backups
- reused a single `DateFormat` instance for `dateReadable` to improve performance
- added `md_toolbar_background` and `md_toolbar_tint` to `colors.xml`
- updated `activity_main.xml` to use toolbar color references instead of hardcoded values
- updated `menu_results.xml` to tint toggle icon using `@color/md_toolbar_tint`

### 0.14.8
- replaced menu icon with Material view list icon and removed hardcoded fill colors
- added md_surface_variant (#F5F5F5) in colors.xml for sort bar background
- adjusted sort bar padding and height

### 0.14.7
- updated list view to show date on the first line and file size below it
- refined alignment and spacing of list rows for a cleaner look

### 0.14.6
- increased list view thumbnail size to 96×96 and reduced filename font size slightly
- adjusted padding around each result item for even spacing on all sides
- refined checkbox positioning and margins for cleaner alignment
- lightened selection overlay tint for a softer pastel visual tone
- restored visible trash badge styling with proper rounded red background

### 0.14.5
- restored List view layout with bigger thumbs, metadata stacked to the right, and checkbox floats on the right
- restored subtle selection overlay in List items for consistent feedback
- added a background color to the sort/filter bar for better visual separation

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
  
