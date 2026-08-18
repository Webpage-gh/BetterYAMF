package com.buildsession.betterYAMF.xposed.services

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.common.model.StartCmd
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.xposed.IAppIconCallback
import com.buildsession.betterYAMF.xposed.IAppListCallback
import com.buildsession.betterYAMF.xposed.IOpenCountListener
import com.buildsession.betterYAMF.xposed.IYAMFManager
import com.buildsession.betterYAMF.xposed.hook.HookLauncher
import com.buildsession.betterYAMF.xposed.ui.window.AppWindow
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.Instances.systemContext
import com.buildsession.betterYAMF.xposed.utils.Instances.systemUiContext
import com.buildsession.betterYAMF.xposed.utils.componentName
import com.buildsession.betterYAMF.xposed.utils.createContext
import com.buildsession.betterYAMF.xposed.utils.dpToPx
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import com.buildsession.betterYAMF.xposed.utils.getTopRootTask
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import com.buildsession.betterYAMF.xposed.utils.startAuto
import com.qauxv.ui.CommonContextWrapper
import de.robv.android.xposed.XposedHelpers
import rikka.hidden.compat.ActivityManagerApis
import java.io.ByteArrayOutputStream
import java.io.File


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "BetterYAMFManager"

    const val ACTION_GET_LAUNCHER_CONFIG = "com.buildsession.betterYAMF.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.buildsession.betterYAMF.action.OPEN_APP"
    private const val ACTION_CURRENT_TO_WINDOW = "com.buildsession.betterYAMF.action.CURRENT_TO_WINDOW"
    private const val ACTION_OPEN_APP_LIST = "com.buildsession.betterYAMF.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.buildsession.betterYAMF.ACTION_OPEN_IN_YAMF"

    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"

    private const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    val windowList = mutableListOf<Int>()
    private val activeWindows = mutableMapOf<Int, AppWindow>()
    val smoothFreeformTasks = mutableSetOf<Int>()
    val smoothFreeformBounds = mutableMapOf<Int, android.graphics.Rect>()
    val pendingSmoothFreeformApps = mutableSetOf<ComponentName>()
    lateinit var config: Config
    val configFile = File("/data/system/BetterYAMF.json")
    private var openWindowCount = 0
    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    lateinit var activityManagerService: Any
    private val listeners = mutableListOf<TopDisplayId>()
    var currentDisplayId = 0

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    private val taskStackListener by lazy {
        android.app.ITaskStackListenerProxy.newInstance(Instances.systemContext.classLoader) { args, method ->
            runMain {
                when (method.name) {
                    "onTaskMovedToFront" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { it.onTaskMovedToFront(taskInfo) }
                        
                        // If a task is moved to front and it's in FREEFORM, and we are in smooth mode,
                        // consider adding it to smoothFreeformTasks if it's not already there.
                        // Or if it's moved to FULLSCREEN, remove it.
                        if (config.windowMode == 1) {
                            val windowingMode = XposedHelpers.getIntField(taskInfo, "windowingMode")
                            if (windowingMode == WINDOWING_MODE_FREEFORM) {
                                val topActivity = taskInfo.topActivity
                                if (topActivity != null && pendingSmoothFreeformApps.contains(topActivity)) {
                                    smoothFreeformTasks.add(taskInfo.taskId)
                                    pendingSmoothFreeformApps.remove(topActivity)
                                    // Trigger a config change to apply spoofing/scaling immediately
                                    switchToSmoothFreeform(taskInfo.taskId)
                                }
                            } else if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                                smoothFreeformTasks.remove(taskInfo.taskId)
                                smoothFreeformBounds.remove(taskInfo.taskId)
                            }
                        }
                    }
                    "onTaskDescriptionChanged" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { it.onTaskDescriptionChanged(taskInfo) }
                    }
                    "onTaskRemovalStarted" -> {
                        val taskId = args[0] as Int
                        smoothFreeformTasks.remove(taskId)
                        smoothFreeformBounds.remove(taskId)
                        // 过滤：只有当被移除的任务 ID 确实属于某个小窗时才触发销
                        activeWindows.values.toList().forEach { window ->
                            if (window.currentTaskId == taskId) {
                                window.onDestroy()
                            }
                        }
                    }
                }
            }
        }
    }
    private var isTaskStackListenerRegistered = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun systemReady() {
        Instances.init(activityManagerService)
        systemContext.registerReceiver(ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        systemContext.registerReceiver(ACTION_CURRENT_TO_WINDOW) { _, _ ->
            currentToWindow()
        }
        systemContext.registerReceiver(ACTION_OPEN_APP_LIST) { _, _ ->
            val componentName = ComponentName("com.buildsession.betterYAMF", "com.buildsession.betterYAMF.manager.applist.AppListWindow")
            val intent = Intent().setComponent(componentName)
            AndroidAppHelper.currentApplication().startService(intent)
        }
        systemContext.registerReceiver(ACTION_OPEN_APP) { _, intent ->
            val componentName = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT_NAME)
                ?: return@registerReceiver
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }
        systemContext.registerReceiver(ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            ActivityManagerApis.broadcastIntent(Intent(HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                // log(TAG, "send config: ${config.hookLauncher}")
                putExtra(HookLauncher.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(HookLauncher.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                putExtra(HookLauncher.EXTRA_HOOK_POPUP, config.hookLauncher.hookPopup)
                putExtra(HookLauncher.EXTRA_HOOK_TRANSIENT_TASKBAR, config.hookLauncher.hookTransientTaskbar)
                `package` = intent.getStringExtra("sender")
            }, 0)
        }

        configFile.createNewFile()
        config = runCatching {
            gson.fromJson(configFile.readText(), Config::class.java)
        }.getOrNull() ?: Config()
        // log(TAG, "config: $config")
    }

    fun addWindow(id: Int, window: AppWindow) {
        windowList.add(0, id)
        activeWindows[id] = window
        openWindowCount++

        if (!isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = true
                // log(TAG, "Global TaskStackListener registered")
            }
        }

        val toRemove = mutableSetOf<IOpenCountListener>()
        iOpenCountListenerSet.forEach {
            runCatching {
                it.onUpdate(openWindowCount)
            }.onFailure { _ ->
                toRemove.add(it)
            }
        }
        iOpenCountListenerSet.removeAll(toRemove)
    }

    fun removeWindow(id: Int) {
        windowList.remove(id)
        activeWindows.remove(id)
        
        if (activeWindows.isEmpty() && isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = false
                // log(TAG, "Global TaskStackListener unregistered (no active windows)")
            }
        }
    }

    fun isTop(id: Int) = if (windowList.isNotEmpty()) windowList[0] == id else true

    fun moveToTop(id: Int) {
        windowList.remove(id)
        windowList.add(0, id)
    }

    fun createWindow(startCmd: StartCmd?) {
        Instances.iStatusBarService.collapsePanels()
        
        val isSmooth = config.windowMode == 1
        val taskId = startCmd?.taskId ?: -1

        if (isSmooth && taskId != -1) {
            smoothFreeformTasks.add(taskId)
        }

        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext()),
            config.flags
        ) { window, displayId ->
            addWindow(displayId, window)
            
            if (isSmooth) {
                if (taskId != -1) {
                    switchToSmoothFreeform(taskId)
                    window.currentTaskId = taskId
                } else if (startCmd?.componentName != null) {
                    pendingSmoothFreeformApps.add(startCmd.componentName)
                    // Start new activity in freeform
                    val options = android.app.ActivityOptions.makeBasic()
                    XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
                    
                    val intent = Intent().apply {
                        component = startCmd.componentName
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    
                    val userHandle = XposedHelpers.callStaticMethod(
                        android.os.UserHandle::class.java,
                        "of",
                        startCmd.userId ?: 0
                    )
                    
                    XposedHelpers.callMethod(
                        Instances.systemContext,
                        "startActivityAsUser",
                        intent,
                        options.toBundle(),
                        userHandle
                    )
                }
            } else {
                startCmd?.startAuto(displayId)
            }
        }
    }

    fun updateSmoothBounds(taskId: Int, bounds: android.graphics.Rect) {
        smoothFreeformBounds[taskId] = bounds
        
        val classLoader = Instances.systemContext.classLoader
        val wctClass = classLoader.loadClass("android.window.WindowContainerTransaction")
        val wct = XposedHelpers.newInstance(wctClass)
        
        val runningTasks = XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        val taskInfo = runningTasks.find { 
            XposedHelpers.getIntField(it, "taskId") == taskId 
        } ?: return
        
        val token = XposedHelpers.getObjectField(taskInfo, "token")
        XposedHelpers.callMethod(wct, "setBounds", token, bounds)
        
        val windowOrganizerController = XposedHelpers.callMethod(Instances.activityTaskManager, "getWindowOrganizerController")
        XposedHelpers.callMethod(windowOrganizerController, "applyTransaction", wct)
    }

    fun switchToSmoothFreeform(taskId: Int) {
        smoothFreeformTasks.add(taskId)
        
        val classLoader = Instances.systemContext.classLoader
        val wctClass = classLoader.loadClass("android.window.WindowContainerTransaction")
        val wct = XposedHelpers.newInstance(wctClass)
        
        val runningTasks = XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        val taskInfo = runningTasks.find { 
            XposedHelpers.getIntField(it, "taskId") == taskId 
        } ?: return
        
        val token = XposedHelpers.getObjectField(taskInfo, "token")
        XposedHelpers.callMethod(wct, "setWindowingMode", token, WINDOWING_MODE_FREEFORM)
        
        // Set initial bounds
        val dm = Instances.systemContext.resources.displayMetrics
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        val left = (dm.widthPixels - width) / 2
        val top = (dm.heightPixels - height) / 2
        val bounds = android.graphics.Rect(left, top, left + width, top + height)
        XposedHelpers.callMethod(wct, "setBounds", token, bounds)
        
        val windowOrganizerController = XposedHelpers.callMethod(Instances.activityTaskManager, "getWindowOrganizerController")
        XposedHelpers.callMethod(windowOrganizerController, "applyTransaction", wct)
        // log(TAG, "Switched task $taskId to smooth freeform")
    }

    init {
        // log(TAG, "reYAMF service initialized")
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun createWindow() {
        runMain {
            createWindow(null)
        }
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun getConfigJson(): String {
        return gson.toJson(config)
    }

    override fun updateConfig(newConfig: String) {
        config = gson.fromJson(newConfig, Config::class.java)
        runMain {
            configFile.writeText(newConfig)
            // Log.d(TAG, "updateConfig: $config")
        }
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        iOpenCountListenerSet.add(iOpenCountListener)
        iOpenCountListener.onUpdate(openWindowCount)
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        iOpenCountListenerSet.remove(iOpenCountListener)
    }

    override fun currentToWindow() {
        runMain {
            val task = getTopRootTask(0) ?: return@runMain
            if (task.baseActivity?.packageName != "com.android.launcher3") {
                createWindow(
                    StartCmd(taskId = task.taskId, componentName = task.topActivity, userId = task.userId)
                )
            }
        }
    }

    override fun resetAllWindow() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
            systemContext.sendBroadcast(Intent(AppWindow.ACTION_RESET_ALL_WINDOW))
        }
    }

    override fun getAppList(): List<AppInfo?>? {
        return listOf()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            var apps: List<ActivityInfo>
            val showApps: MutableList<AppInfo> = mutableListOf()
            val users = mutableMapOf<Int, String>()
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            users.forEach { usr ->
                apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0, usr.key
                ).map {
                    (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        0, usr.key
                    )
                }

                apps.forEach { activityInfo ->
                    showApps.add(
                        AppInfo(
                            activityInfo, usr.key, usr.value
                        )
                    )
                }
            }

            showApps.chunked(5).forEach { chunk ->
                callback.onAppListReceived(chunk.toMutableList())
            }

            callback.onAppListFinished()
        }
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is AdaptiveIconDrawable -> {
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            callback.onResult(byteArray)
        }
    }

    override fun collapseStatusBarPanel() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
        }
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        { _: Context, intent: Intent ->
            val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_UNSPECIFIED)
            createWindow(StartCmd(componentName, userId, taskId))

            // TODO: better way to close recents
            if (source == SOURCE_RECENT && config.recentBackHome) {
                val down = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(down, 0)
                val up = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(up, 0)
            }
        }
}
