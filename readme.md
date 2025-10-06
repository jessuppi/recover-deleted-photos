# Recover Deleted Photos

## Changelog

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
  
