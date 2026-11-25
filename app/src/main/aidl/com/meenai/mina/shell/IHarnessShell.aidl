package com.meenai.mina.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.view.Surface;

// A resident service running in the shell identity (uid 2000) process: a built-in assistant launched by
// wireless debugging.
interface IHarnessShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=Started 1=The user is using the App on the home screen (do not start, do not disturb the home
    // screen) 2=There is no entry to launch 3=Error
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // Cold start: End the process of the App first and then start it; the return value is the same as
    // startApp. It does not end when the user is using it on the home screen, and returns 1; the App
    // does not end when there are tasks outside Mina's screen, and changes to normal startup and returns
    // 4
    int restartApp(int displayId, String packageName) = 9;
    // The current PLAY_AUDIO mode of the package (allow/ignore/default...); cannot be read and returns
    // null
    String getAudioMode(String packageName) = 10;
    // Set PLAY_AUDIO back to the specified mode; used to restore according to the persistent record
    // after Mina exits abnormally
    boolean setAudioMode(String packageName, String mode) = 11;
    // The screen where the task is located; the task no longer exists and returns -1
    int displayOfTask(int taskId) = 12;
    // The package to which the top page of the task belongs; if the task does not exist, return null
    String packageOfTask(int taskId) = 13;
    // As a shell, set the clipboard to this plain text (without popping up the system's "Copied"
    // overlay) for pasting into the editing area where only real input is required. Content marked
    // sensitive (API 33+). Make a note of the original clipboard before setting it up for the first
    // time; leave the original clipboard untouched if it cannot be read. Returns 0=already set 1=cannot
    // read original clipboard (unchanged) 2=error
    int setClipText(String text) = 14;
    // Restore the clipboard before setClipText (clear it if it was originally empty); the clipboard has
    // been replaced by other content by the user, or cannot be read at the moment; if it is cleared, put
    // it back
    void restoreClip() = 15;
    // Open the link or Android settings page (when the action is empty, press VIEW to open the uri;
    // packageName limits which app should be opened). The return value is the same as startApp, and 5=No
    // App can be opened 6=Link is not allowed; the parsed App will not start when there is a task on the
    // home screen, return 1
    int startView(int displayId, String action, String uri, String packageName) = 17;
    // The Mina virtual screen where the task of this package is located (used for checking conflicts in
    // parallel tasks); if not on any screen of Mina, -1 is returned
    int displayOfPackage(String packageName) = 18;
    // setClipText The previous text of the user's clipboard (originally empty or an empty string if it
    // is not text); returns null if there is no one to be restored. It is used for Mina to encrypt the
    // data and restore it after the process is killed.
    String savedClipText() = 19;
    // When the clipboard is still ours put by Mina, it will be replaced with the user's original text
    // original (empty string will be cleared); it will not change if it has been replaced by the user.
    void restoreClipText(String original, String ours) = 20;
    // [uid, pid] of the process where the service is located; Mina uses this to confirm that it is in
    // the process to be handed over before accepting the Binder of the built-in assistant.
    int[] identity() = 21;
    // Undo the changes left by Mina's binding (restore clipboard, unmute, release all virtual screens),
    // the process will not exit; use the built-in assistant to unbind
    void releaseAll() = 22;
    // Connect to the debugging socket of Mina's own WebView as a shell (abstract name
    // webview_devtools_remote_<pid>, pid must be Mina's process), and return the connected socket; if
    // the name is irregular or cannot be connected, null will be returned. Mina is used when the direct
    // connection is blocked by the system.
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    // In the virtual screen context created in this task, execute the command as shell; return exitCode
    // and output.
    Bundle runSystemCommand(int displayId, String command) = 24;
    // The complete class name of the top Activity on the current virtual screen; returns null if there
    // is no task.
    String topActivity(int displayId) = 25;
    // Send the Ctrl+key to this screen (only A to select all, V to paste): There is no input box in the
    // tree, and you only know where the focus is when typing.
    boolean injectCtrlKey(int displayId, int keyCode) = 26;
    // `pm list users` output as is; on error, null
    String listUsers() = 27;
    // `pm list packages --user <userId>` output as is; on error, null
    String listPackages(int userId) = 28;
    // This package comes with the system (including updated system apps); if not found, returns false
    boolean isSystemPackage(String packageName) = 29;
    // Share a piece of text to the package (ACTION_SEND text/plain, EXTRA_TEXT) and start it on this
    // screen; the return value is the same as startApp, and 5 = the package does not receive a text
    // sharing page; the package does not start when there is a task on the main screen, and returns 1
    int startShare(int displayId, String packageName, String text) = 30;
    // Stare at the home screen from now on windowMs: Record all the tasks that have been changed at the
    // top of the main screen during this period (see mainArrivals); return to the top task of the main
    // screen at this moment, if there is no task, it will be -1. Agent is called before each action:
    // When the App opens another App from the background instead of its own page on the Mina screen, the
    // system puts it on the home screen, and Mina recognizes and moves it back accordingly.
    int watchMainScreen(int windowMs) = 31;
    // After sinceUptimeMs(SystemClock.uptimeMillis), the top task appears on the main screen, arranged
    // one after another; see MainScreenWatch.toBundle for the field. Error is null
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // The user to whom the task belongs (the clone or work profile is another user); if the task does
    // not exist, -1 is returned
    int userOfTask(int taskId) = 33;
    // Press a single touch of one finger on Mina's own screen: times[i] milliseconds (relative to
    // pressing) when the finger is at (xs[i], ys[i]); the first point is pressed, and the last point is
    // lifted. Inject one by one according to time, and return only after the injection is completed
    // (about 10 seconds at most); if it is not a Mina screen, the parameters are incorrect, or the
    // injection is not successful after pressing, it returns false.
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // System actions (setting alarm clocks, timers, opening alarm clock lists, opening prefilled new
    // schedules, see SystemIntents; other intents will not be started) are started on this screen. Start
    // as shell first; the page to be taken over requires permissions that Mina has (such as setting an
    // alarm clock) and the shell does not have it, instead issue asMina prepared by Mina (start as Mina,
    // the screen is still the same). The return value is the same as startView: 5=no page access, 6=not
    // included in the release list; the app to be taken over will not start when there are tasks on the
    // home screen, and returns 1
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMina) = 35;
    // Run a setup command (authorization, accessibility list, etc.; the same as the paired wireless
    // debugging run) as a shell, not limited to a certain screen; return exitCode and output. When the
    // assistant is connected to the computer via USB and has not been paired for wireless debugging,
    // Mina's commands can only be accessed here.
    Bundle runCommand(String command) = 36;
    // After Mina's process is killed, should the assistant pull it up (RestoreWish.flags: 1=pull it up
    // 2=write back to the accessibility service before pulling it up). The assistant cannot read Mina's
    // settings, and only remembers the last one handed in; Mina will hand it over again after each
    // handover and when her wishes change.
    void setRestoreWish(int flags) = 37;
    // The number of users who can open the app inside (avatars, work profiles, etc.; see AppCopies for
    // rules): users=int[] number, summary=a line of diagnostic information (number, tag, type, data
    // group, excluding name and installed content); users who cannot be read are null
    Bundle appCopyUsers() = 38;
    // Open the package in another userId and boot to this screen; only recognize the copies listed in
    // appCopyUsers, and return 8 for others. The return value is the same as startApp / restartApp. On
    // the home screen and Mina, only this share (package name + user) is occupied on the other screen:
    // the user does not block this share when using the original share on the home screen, and vice
    // versa. When cold, only the process in this user is ended, and the other one is left unchanged;
    // when userId is Mina's own user, it is equivalent to startApp / restartApp
    int startAppInUser(int displayId, String packageName, int userId, boolean cold) = 39;
    // The package is in the Mina virtual screen where the task in the user with userId is located; if it
    // is not on any screen in Mina, -1 is returned.
    int displayOfPackageInUser(String packageName, int userId) = 40;

    // Main screen device preparation is separate from the virtual screen button whitelist; credentials
    // are only passed in through Binder, not through the shell command line.
    boolean wakeDevice() = 41;
    boolean dismissDeviceKeyguard() = 42;
    boolean enterDeviceCredential(int kind, in byte[] credential) = 43;

    // Same as getAudioMode / setAudioMuted / setAudioMode, it works on the user's share (available, work
    // profile) of userId; when userId is Mina's own user, it is equivalent to the original ones.
    // PLAY_AUDIO is opened separately by package + user, the two copies do not affect each other
    String getAudioModeInUser(String packageName, int userId) = 44;
    boolean setAudioMutedInUser(String packageName, int userId, boolean muted) = 45;
    boolean setAudioModeInUser(String packageName, int userId, String mode) = 46;
    // This package (userId's share) has visible tasks on the home screen, that is, the user is using it
    // on the home screen; true is returned when the home screen cannot be seen clearly.
    boolean visibleOnMainScreen(String packageName, int userId) = 47;

    // The task ends: Process the Apps on this screen according to the rules (see AppsAfterTask), and
    // then release this screen. The remaining ones are moved to the home screen and placed at the end
    // (not in front of the user's eyes). The ones that are making sounds and those in close are turned
    // off along with the screen. keep / close is the key marked by the model (package name, the other is
    // package name@user). Return AppsAfterTask.KEY_*: kept=the key left, closed=the "key: reason" for
    // closing, note=a reason when it cannot be moved (can be empty). Screens that are not Mina are null
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 48;

    // Use the previously determined destruction transaction number; AIDL requires either all methods to
    // be numbered or none to be numbered
    void destroy() = 16777114;
}
