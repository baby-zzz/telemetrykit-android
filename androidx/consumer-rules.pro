# Screen views are named after the resumed Activity's simple class name (TelemetryAndroid, activityViews), so keep the
# names of Activities readable in minified builds (names only; nothing is kept from being shrunk).
-keepnames class * extends android.app.Activity
