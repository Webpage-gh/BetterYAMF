package com.buildsession.betterYAMF.xposed.hook

import android.content.res.Configuration
import android.graphics.Rect
import android.view.SurfaceControl
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.log
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object FreeformHook {
    private const val TAG = "reYAMF_FreeformHook"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    fun init(classLoader: ClassLoader) {
        runCatching { hookRelaunch(classLoader) }.onFailure { log(TAG, "hookRelaunch failed", it) }
        runCatching { hookAdditionalRelaunchPoints(classLoader) }.onFailure { log(TAG, "hookAdditionalRelaunchPoints failed", it) }
        runCatching { hookConfigSpoofing(classLoader) }.onFailure { log(TAG, "hookConfigSpoofing failed", it) }
        runCatching { hookSurfaceScaling(classLoader) }.onFailure { log(TAG, "hookSurfaceScaling failed", it) }
        runCatching { hookInputScaling(classLoader) }.onFailure { log(TAG, "hookInputScaling failed", it) }
    }

    private fun shouldForcePreventRelaunch(activityRecord: Any): Boolean {
        return runCatching {
            val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return false
            val taskId = XposedHelpers.getIntField(task, "mTaskId")

            val isSmoothTask = YAMFManager.smoothFreeformTasks.contains(taskId)
            val isForcePrevent = runCatching { YAMFManager.config.forcePreventRelaunch }.getOrDefault(false)
            
            if (isSmoothTask) return true
            
            if (isForcePrevent) {
                val windowingMode = XposedHelpers.callMethod(task, "getWindowingMode") as Int
                val displayId = XposedHelpers.callMethod(task, "getDisplayId") as Int
                
                // 1. 丝滑模式 (Freeform)
                // 2. Legacy 模式 (处于虚拟显示器上)
                if (windowingMode == WINDOWING_MODE_FREEFORM || YAMFManager.windowList.contains(displayId)) {
                    return true
                }
            }
            false
        }.getOrElse {
            // log(TAG, "Error in shouldForcePreventRelaunch", it)
            false
        }
    }

    private fun hookRelaunch(classLoader: ClassLoader) {
        val activityRecordClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
        
        // Hook all overloads of shouldRelaunchLocked
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "shouldRelaunchLocked") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldForcePreventRelaunch(param.thisObject)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        }
    }

    private fun hookAdditionalRelaunchPoints(classLoader: ClassLoader) {
        val activityRecordClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")

        // Hook all overloads of ensureActivityConfiguration
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "ensureActivityConfiguration") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == true && shouldForcePreventRelaunch(param.thisObject)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        }

        // Hook all overloads of relaunchActivityLocked to log and potentially suppress
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "relaunchActivityLocked") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldForcePreventRelaunch(param.thisObject)) {
                                log(TAG, "Suppressing relaunchActivityLocked for ${param.thisObject}")
                                param.result = null // Skip original method
                            }
                        }
                    })
                }
            }
        }
    }

    private fun hookConfigSpoofing(classLoader: ClassLoader) {
        val taskFragmentClass = classLoader.loadClass("com.android.server.wm.TaskFragment")
        
        XposedHelpers.findAndHookMethod(taskFragmentClass, "computeConfigResourceOverrides",
            Configuration::class.java,
            Configuration::class.java,
            "com.android.server.wm.TaskFragment\$ConfigOverrideHint",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskFragment = param.thisObject
                    if (!taskFragment.javaClass.name.endsWith(".Task")) return
                    
                    val taskId = XposedHelpers.getIntField(taskFragment, "mTaskId")
                    if (YAMFManager.smoothFreeformTasks.contains(taskId)) {
                        val inOutConfig = param.args[0] as Configuration
                        val parentConfig = param.args[1] as Configuration
                        
                        val inOutWindowConfig = XposedHelpers.getObjectField(inOutConfig, "windowConfiguration")
                        val parentWindowConfig = XposedHelpers.getObjectField(parentConfig, "windowConfiguration")
                        
                        // Save physical bounds
                        val physicalBounds = Rect(XposedHelpers.callMethod(inOutWindowConfig, "getBounds") as Rect)
                        YAMFManager.smoothFreeformBounds[taskId] = physicalBounds
                        
                        // Spoof configuration
                        val parentBounds = XposedHelpers.callMethod(parentWindowConfig, "getBounds") as Rect
                        XposedHelpers.callMethod(inOutWindowConfig, "setBounds", parentBounds)
                        
                        val parentAppBounds = XposedHelpers.callMethod(parentWindowConfig, "getAppBounds") as? Rect
                        if (parentAppBounds != null) {
                            XposedHelpers.callMethod(inOutWindowConfig, "setAppBounds", parentAppBounds)
                        }
                        
                        inOutConfig.screenWidthDp = parentConfig.screenWidthDp
                        inOutConfig.screenHeightDp = parentConfig.screenHeightDp
                        inOutConfig.smallestScreenWidthDp = parentConfig.smallestScreenWidthDp
                        inOutConfig.densityDpi = parentConfig.densityDpi
                        inOutConfig.orientation = parentConfig.orientation
                        XposedHelpers.callMethod(inOutWindowConfig, "setWindowingMode", WINDOWING_MODE_FULLSCREEN)
                    }
                }
            }
        )
    }

    private fun hookSurfaceScaling(classLoader: ClassLoader) {
        val taskClass = classLoader.loadClass("com.android.server.wm.Task")
        
        findMethod(taskClass) {
            name == "onConfigurationChanged" && paramCount == 1
        }.hookAfter { param ->
            val task = param.thisObject
            val taskId = XposedHelpers.getIntField(task, "mTaskId")
            
            if (YAMFManager.smoothFreeformTasks.contains(taskId)) {
                val physicalBounds = YAMFManager.smoothFreeformBounds[taskId] ?: return@hookAfter
                val config = XposedHelpers.callMethod(task, "getConfiguration") as Configuration
                val windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration")
                val spoofedBounds = XposedHelpers.callMethod(windowConfig, "getBounds") as Rect
                
                if (spoofedBounds.isEmpty) return@hookAfter
                
                val scaleX = physicalBounds.width().toFloat() / spoofedBounds.width()
                val scaleY = physicalBounds.height().toFloat() / spoofedBounds.height()
                
                val surfaceControl = XposedHelpers.getObjectField(task, "mSurfaceControl") as? SurfaceControl ?: return@hookAfter
                val transaction = XposedHelpers.callMethod(task, "getSyncTransaction") as SurfaceControl.Transaction
                
                XposedHelpers.callMethod(transaction, "setMatrix", surfaceControl, scaleX, 0f, 0f, scaleY)
                XposedHelpers.callMethod(transaction, "setPosition", surfaceControl, physicalBounds.left.toFloat(), physicalBounds.top.toFloat())
            }
        }
    }

    private fun hookInputScaling(classLoader: ClassLoader) {
        val inputMonitorClass = classLoader.loadClass("com.android.server.wm.InputMonitor")
        
        // populateInputWindowHandle(InputWindowHandleWrapper inputWindowHandle, WindowState w)
        XposedHelpers.findAndHookMethod(inputMonitorClass, "populateInputWindowHandle",
            "com.android.server.wm.InputWindowHandleWrapper",
            "com.android.server.wm.WindowState",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val windowState = param.args[1]
                    val task = XposedHelpers.callMethod(windowState, "getTask") ?: return
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")
                    
                    if (YAMFManager.smoothFreeformTasks.contains(taskId)) {
                        val physicalBounds = YAMFManager.smoothFreeformBounds[taskId] ?: return
                        val config = XposedHelpers.callMethod(task, "getConfiguration") as Configuration
                        val windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration")
                        val spoofedBounds = XposedHelpers.callMethod(windowConfig, "getBounds") as Rect
                        
                        if (spoofedBounds.isEmpty) return
                        
                        val scaleX = physicalBounds.width().toFloat() / spoofedBounds.width()
                        // Inverse scale for input
                        val inputScale = 1.0f / scaleX
                        
                        val inputWindowHandleWrapper = param.args[0]
                        XposedHelpers.callMethod(inputWindowHandleWrapper, "setScaleFactor", inputScale)
                    }
                }
            }
        )
    }
}
