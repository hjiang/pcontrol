# After screenshots — manual capture pending

The Android UI polish is implemented and covered by Robolectric on API 26, but
this environment has no emulator or physical device. Capture matching after
screenshots on API 26 and API 37 for compact portrait/landscape and a wide
window, in light/dark mode at 100%/200% font scale. Record device/API, window
size, theme, and font scale for each image.

Required states: empty/partial/complete setup; server dialog with masked token
and validation error; update loading/success/error/action-required; blocked
screen with empty/long subject and multiple allowed sites.
