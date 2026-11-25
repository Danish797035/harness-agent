package com.meenai.mina.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.meenai.mina.core.shell.DevtoolsSocket
import com.meenai.mina.core.uistate.AppCopies
import com.meenai.mina.core.uistate.PressKeys
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import com.meenai.mina.shell.IHarnessShell

/**
 * It resides as a shell (uid 2000), holds the Agent's virtual screen, and runs in the built-in assistant
 * process ([HelperMain]). When the process ends, the virtual screen and the App on it are destroyed
 * together, so it must be permanent rather than a one-time command.
 *
 * Hidden interfaces will always be called by reflection. When reflection fails, they will fall back to
 * the shell command with the same effect.
 */
class ShellService(
    /** Only accept calls from this uid (Mina). */
    private val allowedUid: Int,
    /** The directory where the mute records are placed; the location where the shell identity can write. */
    stateDir: File = File("/data/local/tmp"),
) : IHarnessShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    /** The muted package → the PLAY_AUDIO mode before muting ("default" means it was not set originally), falls in [stateDir], and the process will be restarted after being killed and then restored. */
    private val muted = AudioMuteLedger(File(stateDir, "mina_audio_mute_$allowedUid"))
    /** The user where Mina is located: the original App is installed here; other users have another copy (avatar, work information). */
    private val myUser = allowedUid / PER_USER_RANGE

    // You cannot use getSystemService: the DisplayManager you get in this way is tied to the system
    // context, and the package name does not match the shell uid.
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // The clipboard must be accessed with the shell package name (the system only recognizes the "do not
    // pop the copied floating layer" mark for the clipboard from the shell); read to bypass the Settings
    // query in the ClipboardManager that is blocked by the shell process.
    private val clipboard = ShellClipboard(context)
    private val clipLock = Any()
    /** The startup of another task cannot be inserted between the occupancy check and startup: when two tasks open the same App at the same time, only one can succeed. */
    private val launchLock = Any()
    /** setClipText The previous user's clipboard (null means that it was originally empty, and the ones that cannot be read will not be swapped out); [clipSaved] is false, which means there is nothing to be restored. */
    private var savedClip: ClipData? = null
    private var clipSaved = false
    /** The last text Mina put in; use this to determine whether the clipboard belongs to Mina before restoring (the user will not move if he copies something else in the middle). */
    private var ourText: String? = null

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // When the shell does not have ADD_TRUSTED_DISPLAY (some systems do not provide it): remove
            // several items that are only available for trusted screens and build it again. After
            // returning, this screen does not have its own focus. When reading its logo on the self-test
            // page, it will report "own focus no" and give a prompt that the focus of the main screen
            // may be affected (Advice.FocusShared).
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t: Throwable) {
                Log.e(TAG, "createDisplay failed", t)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("mina-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    /** A group of self-contained virtual screens never sleep, see [DisplayAwake]. */
    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mina-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager and its method of reporting user operations on the screen; if not available, it is null (only remembered once, the screen is commonly used, but no one renews it). */
    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    /** The display group (DisplayInfo.displayGroupId, hidden field) where this screen is located; if it cannot be read, it is null. */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    /** It will only be started on the virtual screen created by the service itself, and the caller will not disturb the user if it passes the wrong screen number (such as the home screen). */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    /**
     * First make sure that the user is not using this App on the home screen: if it has visible tasks on
     * the home screen, it will not be launched (return to [START_IN_USE_ON_MAIN]), otherwise the system
     * will remove or interrupt the interface the user is looking at. Background tasks can be pulled onto
     * the virtual screen. Then parse the startup entry (some activities in the package are springboards,
     * and direct startup will jump away immediately, leaving only empty tasks on the virtual screen),
     * and call IActivityTaskManager.startActivityAsUser with the shell package name, with
     * ActivityOptions.setLaunchDisplayId. Only view the original share (the one in the user where Mina
     * is located): When the user uses the share installed in another user on the home screen, this share
     * will not be blocked.
     */
    override fun startApp(displayId: Int, packageName: String): Int = start(displayId, packageName, myUser)

    /**
     * Start [packageName](see [startApp]) in the user share of [userId]; only look at this share (package
     * name + user) for occupancy. When [userId] is Mina's own user, it is started in the original way
     * (USER_CURRENT, the entry given by the package manager); other users first check the startup entry
     * in that user, and then start it as that user. When the interface is unavailable, it returns to `am
     * start --user`.
     */
    private fun start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName, userId)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            // If you can’t clearly see the home screen status, you would rather not start it than risk
            // disturbing the user.
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        // The task left behind from the last task is still behind the main screen: move the whole thing
        // back and continue on that page without opening a new one and starting from the beginning.
        if (resumeKept(displayId, packageName, userId)) return START_OK
        val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        // In another user's copy, the desktop path is used to open the icon first: some systems prompt
        // "Which one to open with" when the program is started in another copy, but the desktop path is
        // not displayed?
        val component = intent.component
        if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, if (userId == myUser) USER_CURRENT else userId,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName, user $userId) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
            exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /** ILauncherApps (the system service behind LauncherApps); null if not available. */
    private val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    /**
     * Open the startup page [component]:ILauncherApps.startActivityAsUser (the interface behind
     * LauncherApps.startMainActivity) in another user [userId] like a desktop, with the same [options]
     * (boot to Mina's screen). On the system side, press the calling party to check whether the user can
     * be accessed: the shell has cross-user permissions, and the package name also matches its own uid.
     * If the interface does not exist or is rejected (throws an error), false is returned, and the
     * caller changes to startActivityAsUser.
     */
    private fun startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
        val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        // Android 11 onwards: (caller, callingPackage, callingFeatureId, component, sourceBounds, opts,
        // user); earlier there is no callingFeatureId
        val m = la.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 && ComponentName::class.java in it.parameterTypes }
        val args: Array<Any?> = if (m.parameterTypes.size == 7) arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
        else arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
        m.invoke(la, *args)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} in user $userId failed", t)
        false
    }

    /**
     * The intent used to open [packageName]: Mina's own user is the entrance given by the package
     * manager; other users check the user's startup page (hidden interface queryIntentActivitiesAsUser,
     * cannot change `cmd package query-activities --user`). If not found, it is null.
     */
    @SuppressLint("PrivateApi")
    private fun launchIntent(packageName: String, userId: Int): Intent? {
        if (userId == myUser) return context.packageManager.getLaunchIntentForPackage(packageName)
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val found = try {
            val pm = context.packageManager
            val m = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            (m.invoke(pm, query, 0, userId) as List<*>).filterIsInstance<ResolveInfo>().map { it.activityInfo.packageName to it.activityInfo.name }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivitiesAsUser failed, asking the package service", t)
            val args = ActivityQuery.args(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER, packageName = packageName, user = "$userId")
            sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
        }
        val (pkg, cls) = found?.firstOrNull { it.first == packageName } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
    }

    /** These packages contain tasks on another screen of Mina (not [displayId]): that is the app being used by other tasks. When [userId] is not empty, only look at that user. */
    private fun onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) return false
        val displayField = TASK_INFO.getField("displayId")
        return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
    }

    /** The task is the share of [packageName] among the users of [userId] (see [sameCopy] for rules). */
    private fun isCopy(task: Any, packageName: String, userId: Int?): Boolean =
        sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

    /**
     * Only when all the tasks of this App are on Mina's screen, the process will be ended and restarted
     * (return to the initial page). It will never end if there are tasks (user status) on other screens.
     * It will start normally and return to [START_OK_COLD_IGNORED]; it will not start when the user is
     * using it on the home screen.
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

    /** [restartApp] works on the user's share of [userId]: only the tasks of this one are viewed, only the processes in this user are ended, and the other copy installed in other users remains unchanged. */
    private fun restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        // Screens (including the main screen) that are not created by yourself will not move, and you
        // cannot end the process first.
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            // Another task is using Mina on another screen: never end its process
            if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
            // To start from the beginning: The copy left behind the home screen from the last task was
            // left by Mina herself. Close it first and it will not be counted as the user's status.
            dropKept(packageName, userId)
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, userId))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (launchIntent(packageName, userId) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> start(displayId, packageName, userId).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
            ColdStart.RESTART -> {
                val user = if (userId == myUser) "current" else "$userId"
                if (exec("am", "force-stop", "--user", user, packageName) != 0) return START_ERROR
                start(displayId, packageName, userId)
            }
        }
    }

    /** Open in another user: Only those copies judged to be usable by [appCopyUsers] (private spaces and other users will not be touched), see [AppCopies] for the rules. */
    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        if (userId == myUser) return if (cold) restartApp(displayId, packageName) else startApp(displayId, packageName)
        if (!ownDisplay(displayId)) return START_ERROR
        val usable = try {
            users()?.let { AppCopies.usable(myUser, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read users", t)
            null
        } ?: return START_ERROR
        if (userId !in usable) {
            Log.w(TAG, "refused to start $packageName in user $userId")
            return START_USER_NOT_ALLOWED
        }
        return if (cold) restart(displayId, packageName, userId) else start(displayId, packageName, userId)
    }

    override fun appCopyUsers(): Bundle? = try {
        users()?.let { users ->
            Bundle().apply {
                putIntArray(KEY_COPY_USERS, AppCopies.usable(myUser, users).toIntArray())
                putString(KEY_COPY_SUMMARY, AppCopies.summary(myUser, users))
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "appCopyUsers failed", t)
        null
    }

    /**
     * The user of this phone reads according to the original Android UserInfo / UserProperties (hidden
     * interface reflection): tag, type, data group, whether it is running, quiet mode, whether it shares
     * media with the current user, and whether it is hidden from other apps. The user list that cannot
     * be read is null; the single item that cannot be read is left blank (calculated as unsatisfied, see
     * [AppCopies]).
     */
    @SuppressLint("PrivateApi")
    private fun users(): List<AppCopies.User>? {
        val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
            ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
        val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
            ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
        val info = Class.forName("android.content.pm.UserInfo")
        // The name given to the mark by the system itself (the same UserInfo.flagsToString used by
        // dumpsys user, including system-defined marks); when reflection fails, read dumpsys user
        val flagsToString = runCatching { info.getMethod("flagsToString", Int::class.javaPrimitiveType) }.getOrNull()
        val dumped by lazy { sh("dumpsys", "user").takeIf { it.first == 0 }?.second?.let(AppCopies::parseDumpsys).orEmpty() }
        return list.filterNotNull().map { u ->
            val id = info.getField("id").getInt(u)
            val flags = info.getField("flags").getInt(u)
            val handle = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
            val props = runCatching { um.javaClass.getMethod("getUserProperties", UserHandle::class.java).invoke(um, handle) }.getOrNull()
            fun prop(name: String): Any? = props?.let { p -> runCatching { p.javaClass.getMethod(name).invoke(p) }.getOrNull() }
            val names = flagsToString?.let { m -> runCatching { (m.invoke(null, flags) as? String)?.let(::flagNames) }.getOrNull() }
                ?: dumped[id]?.flagNames
            val group = runCatching { info.getField("profileGroupId").getInt(u) }.getOrDefault(AppCopies.NO_PROFILE_GROUP)
            val running = runCatching { um.isUserRunning(handle) }.getOrDefault(false)
            AppCopies.User(
                id = id,
                flags = flags,
                type = runCatching { info.getField("userType").get(u) as? String }.getOrNull(),
                profileGroupId = group,
                running = running,
                unlocked = running && runCatching { um.isUserUnlocked(handle) }.getOrDefault(false),
                quiet = runCatching { um.isQuietModeEnabled(handle) }.getOrDefault(false),
                mediaShared = prop("isMediaSharedWithParent") as? Boolean,
                hidden = (prop("getProfileApiVisibility") as? Int)?.let { it == PROFILE_API_VISIBILITY_HIDDEN },
                flagNames = names,
                // The parentId in dumpsys is the data group (written only when it is not yourself or
                // "not in the group"); read it and bring it with you
                parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
            )
        }
    }

    /** The result of flagsToString (such as "CLONED_PROFILE|FULL|INITIALIZED", some versions have curly brackets) is split into names. */
    private fun flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

    /**
     * Open a link or settings page. Press [ShellLinks] again on the shell side to check the link; if it
     * cannot be parsed, [START_UNRESOLVED] will be returned. The app that can open it will not start if
     * there is a task on the home screen: a link with NEW_TASK may fall into that task and appear in
     * front of the user. Only one app can be directly assigned to that page when it is opened,
     * preventing the system from making another selection.
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        ShellLinks.refuse(action, uri, packageName)?.let { return it }
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // Web-style links are only given to pages that are declared to be open from the link, and do not
        // touch the internal pages of the App.
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = activitiesFor(intent, null) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(link) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                    (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * Share text to [packageName]:ACTION_SEND text/plain with EXTRA_TEXT, only to this package. It does
     * not return the text sharing page to [START_UNRESOLVED]; like [startApp], it does not start when
     * the user uses it on the home screen. When there is only one shared page, you can specify it
     * directly. When there are several pages, the system will ask which one to use.
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val intent = ShareIntent.of(packageName, text)
        val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, ShareIntent.TYPE, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * System action (see [SystemIntents]): Only release those types of intents. If there is no page,
     * [START_UNRESOLVED] will be returned; just like [startView], the app taken over will not start if
     * there are tasks on the home screen. Start as shell first; if it fails (the page taken over
     * requires permissions that shell does not have, such as setting an alarm clock), the [asMina]
     * prepared by Mina is issued: Start as Mina (check Mina for permissions), check the screen and
     * background startup according to the side (shell) from which it was issued, and it still falls on
     * this screen.
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMina: android.app.PendingIntent?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        // Mina's own compatibility self-check page: Open directly to this screen as a shell
        if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
        if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val asShell = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, intent.type, null, null,
                0, 0, null, options.toBundle(), USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(${intent.action}) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
            false
        }
        if (asShell) return START_OK
        if (asMina == null) return START_ERROR
        // Starting from Android 14, the issuer of PendingIntent must explicitly state that it is allowed
        // to launch the page from the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            asMina.send(context, 0, null, null, null, null, options.toBundle())
            START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "sending ${intent.action} as Mina failed", t)
            START_ERROR
        }
    }

    /**
     * As a shell, open Mina's compatibility self-test page ([SystemIntents.OWN_PAGE_CLASS]) to this
     * screen, which is the same as [startApp] (startActivityAsUser with setLaunchDisplayId, if not,
     * return to `am start --display`). That page is only open to callers holding
     * INTERACT_ACROSS_USERS_FULL (shell has it, ordinary apps don’t); shell startup is not restricted by
     * the background startup page. The package name must be Mina's own, and other components will not be
     * opened.
     */
    private fun startOwnPage(displayId: Int, intent: Intent): Int {
        val component = intent.component ?: return START_INVALID_LINK
        val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
        // Only Mina can get this service; check the package name again (only the class name will be
        // recognized if Mina's package name cannot be found)
        if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
        // Open a page from the beginning every time: the old page and its tasks are cleared, and the new
        // one must be at the top of the Mina screen
        val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, page, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(own page) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
        }
        return if (ok) START_OK else START_ERROR
    }

    /**
     * Can access the page of [intent], (package name, complete class name). If the system interface
     * cannot be found on this side, ask the system service instead (see [ActivityQuery]); if neither is
     * possible, it will be null. [type] is the data type of the intent (to be given separately in the
     * command).
     */
    private fun activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
        val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
            .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    /** This package has visible tasks on the home screen, or is the top task on the home screen. When [userId] is not empty, only look at that user (see [sameCopy]). */
    private fun inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            isCopy(task, packageName, userId) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    /**
     * IActivityTaskManager.getTasks: Sorted by recent use, the shell holds REAL_GET_TASKS and can see
     * all App tasks. API 33+ has an overload with displayId, use it first; earlier versions take all and
     * filter by TaskInfo.displayId.
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** Tasks on all screens: Reload with screen number and pass INVALID_DISPLAY, that is, no screen filtering. */
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackage failed", t)
        -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackageInUser failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId), which is the call behind `am
     * display move-stack`. Manual verification on a real machine (Android 14): Tasks can be moved in
     * both directions between the virtual screen and the main screen. The tasks started by Mina are all
     * root tasks.
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    /**
     * Tasks left behind the main screen when the task ends: Task number → key ([AppsAfterTask.keyOf]).
     * Move it back the next time Mina opens the same app ([resumeKept]). Only in the memory: The
     * assistant does not recognize it after restarting, and it will still go to the startup entrance
     * when opening it (the system will probably pull this background task over).
     */
    private val kept = HashMap<Int, String>()

    /**
     * End of the task: Press [AppsAfterTask] to decide whether to leave each task on this screen. Move
     * the remaining tasks to the back of the main screen, and then release this screen (the screen
     * carries FLAG_DESTROY_CONTENT_ON_REMOVAL, and the remaining ones are turned off with the screen).
     * Sequence: decide whether to stay → move what is left → take another look at what is being said →
     * check the top of the home screen → release. The mute will be restored by Mina after this, and the
     * remaining apps will not suddenly make sounds during restoration.
     *
     * The method of moving it to the back (these are the original Android interfaces): first use
     * WindowContainerTransaction.setHidden to hide the task, then moveRootTaskToDisplay to move it to
     * the home screen (the system always puts it at the top, and will not display it, take focus, or
     * pause the page the user is using), then reorder it to the back, and finally unhide it. If a step
     * cannot be used on this system, it will not be moved again, and it will be turned off with the
     * screen as usual. The result will be stated in the results.
     */
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return null
        val closed = ArrayList<String>()
        val notes = LinkedHashSet<String>()
        // It has been moved to the back of the home screen; if you make any mistakes in the process,
        // remember it and you will be able to pick it up next time you open it.
        val moved = ArrayList<AppsAfterTask.Fate>()
        try {
            val playing = playingUids()
            if (playing == null) notes += AppsAfterTask.NOTE_PLAYBACK_UNKNOWN
            val rows = tasksOn(displayId)
            val idField = TASK_INFO.getField("taskId")
            val byId = rows.associateBy { idField.getInt(it) }
            val fates = AppsAfterTask.decide(rows.map { afterTaskRow(it, playing) },
                AppsAfterTask.normalize(keep, myUser), AppsAfterTask.normalize(close, myUser))
            val mainTop = topTaskId(Display.DEFAULT_DISPLAY)
            var supported = true
            for (f in fates) {
                if (!f.keep) { closed += AppsAfterTask.closedEntry(f); continue }
                if (!supported) { closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported)); continue }
                when (keepBehind(byId.getValue(f.task.id))) {
                    Behind.OK -> moved += f
                    Behind.UNSUPPORTED -> {
                        supported = false
                        notes += AppsAfterTask.NOTE_MOVE_UNSUPPORTED
                        closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                    }
                    Behind.FAILED -> closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveFailed))
                }
            }
            // If the sound comes out again after moving it (not marked keep): Move this screen back and
            // turn it off with the screen; if you can’t move it back, turn it off directly
            val playingNow = if (moved.isEmpty()) null else playingUids()
            val nowLoud = moved.map { f -> AppsAfterTask.recheck(f, task(f.task.id)?.let { playingIn(it, playingNow) }) }.filter { !it.keep }
            for (f in nowLoud) {
                if (!moveRoot(f.task.id, displayId)) removeTask(f.task.id)
                moved.removeAll { it.task.id == f.task.id }
                closed += AppsAfterTask.closedEntry(f)
            }
            val removed = ensureBehind(moved.map { it.task.id }.toSet(), mainTop)
            moved.filter { it.task.id in removed }.forEach { closed += AppsAfterTask.closedEntry(it.copy(keep = false, why = AppsAfterTask.Why.MoveFailed)) }
            moved.removeAll { it.task.id in removed }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot inspect tasks, closing the rest with the display", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        synchronized(kept) { moved.forEach { kept[it.task.id] = it.key } }
        releaseDisplay(displayId)
        Bundle().apply {
            putStringArray(AppsAfterTask.KEY_KEPT, moved.map { it.key }.toTypedArray())
            putStringArray(AppsAfterTask.KEY_CLOSED, closed.toTypedArray())
            putString(AppsAfterTask.KEY_NOTE, notes.joinToString("; ").ifEmpty { null })
        }
    }

    private enum class Behind { OK, UNSUPPORTED, FAILED }

    /** The line of the task is for the [AppsAfterTask.decide]: key, is it a normal App page, is it Mina's own, and is silent at the moment. */
    private fun afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
        val user = runCatching { TASK_INFO.getField("userId").getInt(t) }.getOrDefault(myUser)
        val pkgs = packagesOf(t).distinct()
        val type = runCatching { TASK_INFO.getField("topActivityType").getInt(t) }.getOrNull()
        return AppsAfterTask.Task(
            id = TASK_INFO.getField("taskId").getInt(t),
            keys = pkgs.map { AppsAfterTask.keyOf(it, user, myUser) },
            standard = type == null || type == ACTIVITY_TYPE_UNDEFINED || type == ACTIVITY_TYPE_STANDARD,
            own = pkgs.any { it in ownPackages },
            playing = playingIn(t, playing),
            muted = synchronized(muted) { pkgs.any { muted.contains(AppsAfterTask.keyOf(it, user, myUser)) } },
        )
    }

    private val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** The App of this task (in the same user) is making a sound at the moment; if it cannot be read, it is null. */
    private fun playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
        playing ?: return null
        val user = runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull()
        val pkgs = packagesOf(task)
        return playing.any { (uid, owners) -> (user == null || uid / PER_USER_RANGE == user) && pkgs.any { it in owners } }
    }

    /**
     * The uid being played at the moment → the package of this uid. Ask the audio service first
     * (AudioManager.getActivePlaybackConfigurations, only give the uid when MODIFY_AUDIO_ROUTING is
     * specified); when the uid obtained is hidden, read `dumpsys audio` instead. Only players whose
     * status is started are recognized (those that are muted by Mina are still playing: muted ones only
     * have no sound, and restored ones will sound). None can be read as null.
     */
    private fun playingUids(): Map<Int, Set<String>>? {
        val uids = runCatching {
            val configs = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            val uidOf = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
            val stateOf = AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
            val rows = configs.map { (uidOf.invoke(it) as Int) to (stateOf.invoke(it) as Int) }
            if (rows.any { it.first < 0 }) null
            else rows.filter { it.second == AppsAfterTask.PLAYER_STATE_STARTED }.map { it.first }.toSet()
        }.onFailure { Log.w(TAG, "playback configurations unavailable", it) }.getOrNull()
            ?: sh("dumpsys", "audio").takeIf { it.first == 0 }?.second?.let(AppsAfterTask::playingUids)
            ?: return null
        return uids.associateWith { uid ->
            runCatching { context.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()
        }
    }

    /** Move the task [row] on the Mina screen to the back of the main screen (see [finishDisplay] for the method). */
    private fun keepBehind(row: Any): Behind {
        val id = TASK_INFO.getField("taskId").getInt(row)
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: return Behind.UNSUPPORTED
        if (!transact { wct -> wctCall(wct, "setHidden", token, true) }) return Behind.UNSUPPORTED
        if (!moveRoot(id, Display.DEFAULT_DISPLAY)) {
            transact { wct -> wctCall(wct, "setHidden", token, false) }
            return Behind.UNSUPPORTED
        }
        // Do not unhide it before placing it at the end: The system will not move it when unhiding it.
        // If you unhide it first, it will appear in front of the user.
        val behind = transact { wct -> wctCall(wct, "reorder", token, false) }
        if (behind && transact { wct -> wctCall(wct, "setHidden", token, false) }) return Behind.OK
        // Can't stay even if it's parked halfway (hidden or at the top): turn it off
        Log.w(TAG, "task $id could not be put behind on the main screen, removing it")
        removeTask(id)
        return Behind.FAILED
    }

    /**
     * Check: None of the moved tasks are in front of the user (visible on the home screen). If you can
     * see it (it shouldn't happen), go to the back of the queue immediately, and turn it off if you can
     * still see it. If you can see it, press TaskInfo.isVisible; if you can't read this item, press "Is
     * it the top task on the home screen?" (the tasks there are arranged according to recent use, and
     * are only used as a retreat). [before] was the top task on the main screen before it was moved, and
     * was only recorded in the log.
     */
    private fun ensureBehind(moved: Set<Int>, before: Int): Set<Int> {
        if (moved.isEmpty()) return emptySet()
        val removed = HashSet<Int>()
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        fun inFront(): List<Any> = if (visibleField != null) {
            tasksOn(Display.DEFAULT_DISPLAY).filter { TASK_INFO.getField("taskId").getInt(it) in moved && visibleField.getBoolean(it) }
        } else listOfNotNull(tasksOn(Display.DEFAULT_DISPLAY).firstOrNull()?.takeIf { TASK_INFO.getField("taskId").getInt(it) in moved })
        repeat(2) { attempt ->
            val front = inFront()
            if (front.isEmpty()) return removed
            for (row in front) {
                val id = TASK_INFO.getField("taskId").getInt(row)
                Log.w(TAG, "moved task $id is in front on the main screen (top was $before), sending it back")
                val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
                if (attempt == 1 || token == null || !transact { wct -> wctCall(wct, "reorder", token, false) }) {
                    removeTask(id)
                    removed += id
                }
            }
        }
        return removed
    }

    /** IActivityTaskManager.moveRootTaskToDisplay; returns `am display move-stack` (same call) when the interface is no longer available. */
    private fun moveRoot(taskId: Int, displayId: Int): Boolean = moveTask(taskId, displayId)

    /** IActivityTaskManager.removeTask (turn off the task, which is the same as crossing out the recent task); if it cannot be returned, `am stack remove`. */
    private fun removeTask(taskId: Int): Boolean = try {
        taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "removeTask unavailable, falling back to am", t)
        exec("am", "stack", "remove", "$taskId") == 0
    }

    /** Create a WindowContainerTransaction and hand it over to the system via IWindowOrganizerController.applyTransaction; if any step fails, false will be returned. */
    @SuppressLint("PrivateApi")
    private fun transact(build: (Any) -> Unit): Boolean = try {
        val wct = WCT.getConstructor().newInstance()
        build(wct)
        val controller = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        controller.javaClass.methods.first { it.name == "applyTransaction" && it.parameterTypes.size == 1 }.invoke(controller, wct)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "window container transaction failed", t)
        false
    }

    @SuppressLint("PrivateApi")
    private fun wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
        WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
    }

    /** The tasks of this App that are left from the last task on the home screen (not in front of the user). */
    private fun keptOnMain(packageName: String, userId: Int): List<Any> {
        val ids = synchronized(kept) { kept.keys.toSet() }
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        val all = allTasks()
        // Tasks that are gone are no longer remembered
        synchronized(kept) { kept.keys.retainAll(all.map { idField.getInt(it) }.toSet()) }
        return all.filter { t ->
            idField.getInt(t) in ids && displayField.getInt(t) == Display.DEFAULT_DISPLAY && isCopy(t, packageName, userId) &&
                visibleField?.getBoolean(t) != true
        }
    }

    /** Move the task left behind the main screen back to Mina's screen; if it is not available or cannot be moved, it returns false (the caller starts as usual). */
    private fun resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
        val row = runCatching { keptOnMain(packageName, userId).firstOrNull() }.getOrNull() ?: return false
        val id = TASK_INFO.getField("taskId").getInt(row)
        if (!moveRoot(id, displayId)) return false
        synchronized(kept) { kept.remove(id) }
        return true
    }

    /** Before cold start, close the copy left behind the home screen from the last task (left by Mina herself, not the user's status). */
    private fun dropKept(packageName: String, userId: Int) {
        keptOnMain(packageName, userId).forEach { t ->
            val id = TASK_INFO.getField("taskId").getInt(t)
            if (removeTask(id)) synchronized(kept) { kept.remove(id) }
        }
    }

    /** Record of main screen task: Agent starts staring before action, see [MainScreenWatch]. Only built (thread, task stack monitoring) when used for the first time. */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("userId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userOfTask failed", t)
        -1
    }

    /** All on-screen tasks are replaced with [MainScreenLog.Task]; when the page type cannot be read, it is treated as a normal App page. */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // The same white list as press_key: even if the caller makes an error, the volume, power, home
        // and other machine buttons will not be issued from here.
        if (!PressKeys.isAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    /** Ctrl+A / Ctrl+V: The modifier keys are written in the metaState of the key event (the App recognizes the key combination by pressing it), only these two are allowed, and are only sent to Mina's own screen (paste cannot be placed on the user's screen). */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { false }
    }

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * A stroke of one finger: press, press [times] to move point by point, and lift at the last point;
     * each event is injected at its moment (the event time is that moment), and the speed seen by the
     * App is the speed arranged by Mina. Only sent to Mina's own screen. If the injection is not
     * successful after pressing, it returns false (the caller takes another path); if the injection is
     * not successful at any point in the middle after pressing, it will continue to go down. In the end,
     * it must be reissued and raised to prevent the finger from being pressed all the time.
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
        sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

    /**
     * `appops set <pkg> PLAY_AUDIO ignore`; Record the original mode when muting for the first time
     * (dropping files at the same time), and restore it when canceling muting. If it has not been set
     * before, it will be restored to allow without writing default (see [AudioMuteLedger.restoreMode]).
     * If the command fails, false will be returned, and the original mode will not be recorded (the
     * original value will not be changed if it cannot be read). Acts on the user where Mina is located
     * (original share).
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = audioMuted(packageName, null, muted)

    /** Same as [setAudioMuted], it applies to the user's share (available, work information) of [userId]; records are recorded separately as [AppAudioKey]. */
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        if (userId == myUser) return audioMuted(packageName, null, muted)
        return audioMuted(packageName, otherUser(userId) ?: return false, muted)
    }

    private fun audioMuted(packageName: String, user: Int?, muted: Boolean): Boolean = synchronized(this.muted) {
        val key = AppAudioKey.of(packageName, user)
        if (muted) {
            if (!this.muted.contains(key)) {
                val (code, out) = appops("get", packageName, user)
                if (code != 0) return false
                this.muted.put(key, parsePlayAudioMode(out))
            }
            appops("set", packageName, user, "ignore").first == 0
        } else {
            val ok = appops("set", packageName, user, AudioMuteLedger.restoreMode(this.muted[key])).first == 0
            if (ok) this.muted.remove(key)
            ok
        }
    }

    /** When starting, restore the packages that were not restored last time (only the ones in the record are moved). */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    /** Press the recorded key to write back an item; if the key is not in the format, it will be treated as processed, so as not to keep it forever. */
    private fun writeRecorded(key: String, mode: String): Boolean {
        val (pkg, user) = AppAudioKey.parse(key) ?: return true
        return appops("set", pkg, user, mode).first == 0
    }

    /** [user] is empty and is the user where Mina is located. */
    private fun appops(verb: String, packageName: String, user: Int?, mode: String? = null): Pair<Int, String> {
        val u = (user ?: myUser).toString()
        return if (mode == null) sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO")
        else sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO", mode)
    }

    /** Other user numbers are given as they are; when it is the user where Mina is, it is null (processed as originally); when it is illegal, it is also null, and the caller refuses accordingly. */
    private fun otherUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != myUser }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    private fun audioMode(packageName: String, user: Int?): String? {
        val (code, out) = appops("get", packageName, user)
        return if (code == 0) parsePlayAudioMode(out) else null
    }

    /** "default" (not set) is written as allow, the reason is the same as [AudioMuteLedger.restoreMode]. */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
    }

    private fun audioMode(packageName: String, user: Int?, mode: String): Boolean {
        if (!MODE_RE.matches(mode)) return false
        val ok = appops("set", packageName, user, AudioMuteLedger.restoreMode(mode)).first == 0
        if (ok) synchronized(muted) { muted.remove(AppAudioKey.of(packageName, user)) }
        return ok
    }

    /**
     * This package ([userId] in the user's share) has a visible task on the home screen: the user is
     * using it on the home screen. The mute button works according to the package. At this time, there
     * is no sound when muting the device on the user's hand. Mina accordingly does not mute it. When you
     * can't see the home screen clearly, press "Active". It would be better not to mute the screen than
     * to mute what the user is using.
     */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        try {
            inUseOnMainScreen(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            true
        }

    /**
     * Only when the user's original clipboard can be read can it be moved: if it cannot be read, it will
     * be difficult to distinguish between "originally empty" and "containing content but cannot be
     * seen". If it is overwritten, it cannot be restored. In this case, [CLIP_UNREADABLE] will be
     * returned, and the clipboard will remain as it is.
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            if (!clipSaved) {
                if (!canReadClipboard()) return CLIP_UNREADABLE
                val prior = clipboard.primaryClip
                // Reading empty does not necessarily mean empty: the system may quietly block the
                // reading. At this time, the description will say that there is content.
                when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                    ShellClip.Read.Unreadable -> {
                        Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                        return CLIP_UNREADABLE
                    }
                    ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                    ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            CLIP_SET
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip() = synchronized(clipLock) {
        if (!clipSaved) return
        runCatching {
            val prior = savedClip
            val now = nowState(ourText)
            val action = ShellClip.restore(prior == null, now)
            Log.i(TAG, "restoreClip: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(prior!!))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }
        savedClip = null
        clipSaved = false
        ourText = null
    }

    override fun savedClipText(): String? = synchronized(clipLock) {
        if (!clipSaved) return null
        runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
    }

    override fun restoreClipText(original: String, ours: String) = synchronized(clipLock) {
        runCatching {
            val now = nowState(ours)
            val action = ShellClip.restore(original.isEmpty(), now)
            Log.i(TAG, "restoreClipText: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(ClipData.newPlainText("", original)))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }
        Unit
    }

    /** Is the clipboard [ours] put by Mina at the moment? It should not be empty when it cannot be read (it has content but cannot be retrieved). */
    private fun nowState(ours: String?): ShellClip.Now {
        val now = clipboard.primaryClip
        return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
            ShellClip.Read.Empty -> ShellClip.Now.Empty
            ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
            ShellClip.Read.Has -> {
                val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                    now.getItemAt(0).text?.toString() == ours
                if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
            }
        }
    }

    /** The shell must have the permission to read the clipboard in the background and the permission operation to read the clipboard has not been turned off before it can be read. */
    private fun canReadClipboard(): Boolean = runCatching {
        val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val ops = context.getSystemService(AppOpsManager::class.java)
        granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    /**
     * The content pasted by Mina (may be address, mobile phone number, etc.) is marked as sensitive: the
     * clipboard preview and history of the system and input method do not display the original text.
     * Only mark what Mina put in, and keep it as is when restoring the user's clipboard.
     */
    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // The process of the built-in assistant does not end with Mina, and Binder may also be obtained
        // by others: only Mina can call interface methods
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (!ownDisplay(displayId)) return commandResult(-1, "No task display is active.")
        return exec(command, displayId)
    }

    override fun runCommand(command: String): Bundle = exec(command, null)

    /** The assistant process looks at the wish sent by Mina (see [setRestoreWish]); after Mina dies, it decides whether to pull it up based on this. */
    @Volatile var onRestoreWish: ((Int) -> Unit)? = null

    override fun setRestoreWish(flags: Int) {
        onRestoreWish?.invoke(flags)
    }

    private val deviceUnlock by lazy { DeviceUnlockInput(context,
        inject = { inject(it, Display.DEFAULT_DISPLAY, waitForFinish = true) },
        injectTo = { event, uid -> injectTo(event, Display.DEFAULT_DISPLAY, uid) },
        command = { exec(*it.toTypedArray()) == 0 }) }

    override fun wakeDevice(): Boolean = deviceUnlock.wake()
    override fun dismissDeviceKeyguard(): Boolean = deviceUnlock.dismiss()
    override fun enterDeviceCredential(kind: Int, credential: ByteArray): Boolean = deviceUnlock.enter(kind, credential)

    private fun commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

    /** Run `sh -c` as shell, merge the output, and have a time limit; put HARNESS_DISPLAY_ID when [displayId] is not empty. */
    private fun exec(command: String, displayId: Int?): Bundle {
        fun result(code: Int, output: String) = commandResult(code, output)
        if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply {
                    if (displayId != null) {
                        val dStr = displayId.toString()
                        environment()["HARNESS_DISPLAY_ID"] = dStr
                        environment()["MINA_DISPLAY_ID"] = dStr
                    }
                }.start()
        } catch (t: Throwable) {
            return result(-1, t.message ?: "Command could not start.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
        return result(if (finished) process.exitValue() else -1, output)
    }

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? {
        val pid = DevtoolsSocket.pidOf(name) ?: return null
        // Only the debugging socket of Mina's own process is connected: other App's web pages do not go
        // through here
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Mina's (uid $uid)")
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: Exception) {
            Log.w(TAG, "devtools socket $name: ${e.message}")
            null
        } finally {
            // What is handed over is the copied fd, this one is closed
            runCatching { socket.close() }
        }
    }

    override fun releaseAll() {
        restoreClip()
        synchronized(muted) { muted.packages() }.forEach { key ->
            AppAudioKey.parse(key)?.let { (pkg, user) -> audioMuted(pkg, user, false) } ?: synchronized(muted) { muted.remove(key) }
        }
        awake.stopAll()
        synchronized(displays) { displays.values.toList().also { displays.clear() } }.forEach { it.release() }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    /** IWindowManager.setDisplayImePolicy: The input method does not pop up on the user's home screen. See IME_POLICY for which policy to choose. */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    /**
     * Only cast to the focus window owned by [targetUid], and then return after processing is completed;
     * the system rejects it when the focus is not there, and the event will not fall to other windows.
     * Use the hidden overload `injectInputEvent(event, mode, targetUid)` since Android 13, and it will
     * not be injected without this overload. Only the exception type is recorded for failure: the reason
     * for rejection does not include keystrokes, but the lock screen credentials are entered in this
     * path, leaving no redundant information.
     */
    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    /** The injection entry point is InputManagerGlobal starting from API 34, and InputManager earlier. */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /**
     * Run a shell command and return (exit code, output); timeout returns -1. The output is read in a
     * separate thread, and the stuck command will not block the binder thread forever.
     */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    companion object {
        private const val TAG = "MinaShell"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        // injectStroke The upper limit of a stroke: points, milliseconds from pressing to lifting
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L
        private val MODE_RE = Regex("[a-z_]+")
        private const val PER_USER_RANGE = 100_000

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** Cold startup was changed to normal startup: the app has tasks outside of Mina's screen. */
        const val START_OK_COLD_IGNORED = 4
        /** startView: No app can open this link. */
        const val START_UNRESOLVED = 5
        /** startView: The link is not allowed to be opened (see [ShellLinks]). */
        const val START_INVALID_LINK = 6
        /** This app is being used for other tasks on another screen of Mina. */
        const val START_IN_USE_BY_OTHER = 7
        /** startAppInUser: That user is not available (see [AppCopies]). */
        const val START_USER_NOT_ALLOWED = 8

        // appCopyUsers Bundle
        const val KEY_COPY_USERS = "users"
        const val KEY_COPY_SUMMARY = "summary"
        // UserProperties.PROFILE_API_VISIBILITY_HIDDEN
        private const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // results of setClipText
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "Harness Agent"
        // This name is hidden in AppOpsManager
        private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        private const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*: Desktop, recent tasks, assistant, etc. are other
        // categories
        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are hidden constants)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * Flag used to create a virtual screen. When [trusted] is false, remove the items that depend on
         * the trusted screen: in some systems, the shell does not have ADD_TRUSTED_DISPLAY, and bringing
         * TRUSTED will be rejected by the system with a SecurityException; OWN_DISPLAY_GROUP,
         * ALWAYS_UNLOCKED and OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED are also only available for trusted
         * screens. The remaining items have nothing to do with whether they are trustworthy or not, just
         * as usual.
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // When there is only OWN_FOCUS, starting the App on the virtual screen or opening a new page
            // by the App itself will make it the "top focus screen", and the focus window of the main
            // screen will be cleared; STEAL_TOP_FOCUS_DISABLED (need to have OWN_FOCUS and TRUSTED at
            // the same time) will prevent it from grabbing the focus of the main screen.
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /**
         * Injection method. Press the key and wait until the system determines who to vote to
         * (WAIT_FOR_RESULT): When there is no window on the screen that can accept the key press, the
         * injection will report failure, and the caller will return to the shell command or report the
         * action failed. It will no longer "send it and forget it"; it will only wait for dispatch, not
         * wait for the App to finish processing, and at most wait until the system's own injection times
         * out. Touching still does not wait (ASYNC): A stroke must be delivered one by one according to
         * time, and waiting for delivery will disrupt the speed. [waitForFinish]’s (home screen unlock
         * input) waits for the App to be processed.
         */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }

        /**
         * A pair of press/lift: [fallback] (shell command) will be executed only if the injection is not
         * successful after pressing; returning after pressing has been delivered will cause an extra
         * complete click/key, and only raise will be reissued at this time (retry once if failed).
         */
        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        /** Only lack of permissions (SecurityException, such as the shell does not have ADD_TRUSTED_DISPLAY) is worth removing the trusted flag and trying again; other failures will still report errors. */
        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2. Actual test on
        // real machine (Android 14) LOCAL: After focusing on the input box on the virtual screen, the
        // input method window appears on the virtual screen, and the main screen focus remains
        // unchanged, without HIDE.
        private const val IME_POLICY = 0

        // SystemUI's convention: when the clipboard from the shell is brought with it, the "Copied"
        // floating layer will not be displayed (otherwise it will pop up on the user's home screen)
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /**
         * A task (the page is in the package [taskPackages] and belongs to the user [taskUser], if it
         * cannot be read, it is null). Is [packageName] the share of the user [userId]? When [userId] is
         * empty, it does not matter which share it is; when the user of the task cannot read it, it
         * counts (it would rather be regarded as occupied than risk disturbing the user).
         */
        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            packageName in taskPackages && (userId == null || taskUser == null || taskUser == userId)

        /** Parsing `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: ignore; time=..." → "ignore"; not set → "default". */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
