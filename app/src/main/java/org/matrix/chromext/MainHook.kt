package org.matrix.chromext

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.Log.logexIfThrow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.matrix.chromext.hook.BaseHook
import org.matrix.chromext.hook.ChromeHook

private const val PACKAGE_NAME_HOOKED = "com.android.chrome"
private const val TAG = "ChromeXt"

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit /* Optional */ {
  override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
    if (lpparam.packageName == PACKAGE_NAME_HOOKED) {
      // Init EzXHelper
      EzXHelperInit.initHandleLoadPackage(lpparam)
      EzXHelperInit.setLogTag(TAG)
      EzXHelperInit.setToastTag(TAG)
      // Init hooks
      initHooks(ChromeHook)
    }
  }

  // Optional
  override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
    EzXHelperInit.initZygote(startupParam)
  }

  private fun initHooks(vararg hook: BaseHook) {
    hook.forEach {
      runCatching {
            if (it.isInit) return@forEach
            it.init()
            it.isInit = true
            Log.i("Inited hook: ${it.javaClass.simpleName}")
          }
          .logexIfThrow("Failed init hook: ${it.javaClass.simpleName}")
    }
  }
}
