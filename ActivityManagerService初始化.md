# ActivityManagerService简介
* `ActivityManagerService`是Android Framework的核心，它管理着Android系统中的4大组件：`Activity`、`Service`、`ContentProvider`和`BroadcastReceiver`。同时也管理和调度所有用户进程
* `ActivityManagerService`是Binder服务，但是AMS的Binder框架代码不是通过AIDL自动生成的
* `ActivityManagerService`是`ActivityManagerNative`的子类
* `ActivityManagerNative`继承Binder且实现`IActivityManager`接口，作为Binder的Bn端
* `ActivityManagerProxy`也实现了`IActivityManager`接口，且`ActivityManagerProxy`是`ActivityManagerNative`的内部类。作为Binder服务的Bp端
* `ActivityManager`相当于把`ActivityManagerProxy`封装起来，`ActivityManager`通过调用`ActivityManagerNative`的`getDefault()`方法来得到`ActivityManagerProxy`对象的引用。


# ActivityManagerService的初始化
AMS 运行在SystemServer进程中，对象的创建时在SystemServer类初始化时完成的
1. 初始化`SystemServer`中的Context类型的变量`mSystemContext`

## AMS的初始化流程时序图
```puml
Title : AMS初始化时序图
SystemServer -> SystemServer : main()
SystemServer -> SystemServer : SystemServer()
SystemServer -> SystemServer : run()

SystemServer -> SystemServer : 1. createSystemContext()
SystemServer -> ActivityThread : systemMain()
ActivityThread -> ActivityThread : ActivityThread()
ActivityThread -> ActivityThread : attach(true)

ActivityThread -> Instrumentation : Instrumentation()

ActivityThread -> ActivityThread : getSystemContext()
Note right of ActivityThread : 如果mSystemContext为null
ActivityThread -> ContextImpl : createSystemContext()
ContextImpl -> LoadedApk : LoadedApk()
LoadedApk -> ApplicationInfo : ApplicationInfo()
ContextImpl -> ContextImpl : ContextImpl()
Note right of ActivityThread : 初始化mSystemContext
ContextImpl -> ActivityThread : return context



Note left ActivityThread : 返回ActivityThread对象
ActivityThread -> SystemServer : return thread

SystemServer -> ActivityThread : getSystemContext()
```

## `SystemServer.main()`入口方法
* 创建SystemServer对象，并调用它的run()方法
```java
public static void main(String[] args) {
    new SystemServer().run();
}
```

#### `SystemServer.SystemServer()`构造方法
* 初始化`mFactoryTestMode`是否为工厂测试模式
```java
public SystemServer() {
    /* 通过[FactoryTest]对象的[getMode()]方法检查是否为工厂模式，并为[mFactoryTestMode]初始化
     * frameworks/base/core/java/android/os/FactoryTest.java
     */
    mFactoryTestMode = FactoryTest.getMode();
}
```

## `SystemServer.run()`方法
```java
private void run() {
    try {
        //...
        //调用createSystemContext()方法创建SystemContext
        createSystemContext();
        mSystemServiceManager = new SystemServiceManager(mSystemContext)
    }
    //...
    try {
        //mActivityManagerService 在startBootstrapServices()方法中初始化
        startBootstrapServices()
        startCoreServices()
        startOtherServices()
    }
}
```

---
## 1. `SystemServer.createSystemContext()`方法
* 创建SystemContext并初始化SystemServer中的`mSystemContext`变量
```java
private void createSystemContext() {
    //调用ActivityThread.systemMain()方法
    ActivityThread activityThread = ActivityThread.systemMain();
    //调用 ActivityThread的getSystemContext()方法初始化 mSystemContext 对象
    mSystemContext = activityThread.getSystemContext();
    mSystemContext.setTheme(android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
}
```

### `ActivityThread.systemMain()`方法
```java
public static ActivityThread systemMain() {
    //如果系统进程运行在低端设备（低内存），不启动硬件加速
    if (!ActivityManager.isHighEndGfx()) {
        HardwareRenderer.disable(true);
    } else {
        HardwareRenderer.enableForegroundTrimming();
    }
    //创建一个 ActivityThread 对象并返回
    ActivityThread thread = new ActivityThread();
    //调用ActivityThread的attach()方法，传入true
    thread.attach(true);
    return thread;
}
```

#### `ActivityThread.ActivityThread()`构造函数
* 获取ResourcesManager实例化对象并初始化ActivityThread中的mResourcesManager变量
```java
ActivityThread() {
    mResourcesManager = ResourcesManager.getInstance();
}
```

### `ActivityThread.attach()`方法
```java
Application mInitialApplication;

private void attach(boolean system) {
    sCurrentActivityThread = this;
    mSystemThread = system;
    if (!system) {
        //...
    } else {
        //不要在这里设置应用对象，如果系统crash，不会弹出警告
        //在ddms中创建一个system_process进程
        android.ddm.DdmHandleAppName.setAppName("system_process",
                UserHandle.myUserId());
        try {
            //创建Instrumentation对象，并初始化mInstrumentation对象
            mInstrumentation = new Instrumentation();
            //先调用getSystemContext()方法，再调用ContextImpl的createAppContext()方法
            ContextImpl context = ContextImpl.createAppContext(
                    this, getSystemContext().mPackageInfo);
            //调用LoadedApk.makeApplication()方法
            mInitialApplication = context.mPackageInfo.makeApplication(true, null);
            mInitialApplication.onCreate();
        } catch (Exception e) {
            throw new RuntimeException(...);
        }
    }

    // add dropbox logging to libcore
    DropBox.setReporter(new DropBoxReporter());

    ViewRootImpl.addConfigCallback(new ComponentCallbacks2() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            synchronized (mResourcesManager) {
                // We need to apply this change to the resources
                // immediately, because upon returning the view
                // hierarchy will be informed about it.
                if (mResourcesManager.applyConfigurationToResourcesLocked(newConfig, null)) {
                    // This actually changed the resources!  Tell
                    // everyone about it.
                    if (mPendingConfiguration == null ||
                            mPendingConfiguration.isOtherSeqNewer(newConfig)) {
                        mPendingConfiguration = newConfig;

                        sendMessage(H.CONFIGURATION_CHANGED, newConfig);
                    }
                }
            }
        }
        @Override
        public void onLowMemory() {
        }
        @Override
        public void onTrimMemory(int level) {
        }
    });
}
```

### `Instrumentation.Instrumentation()`构造函数
* Instrumentation的构造函数是一个空实现，仅仅是创建一个对象
```java
public Instrumentation() {
}
```

### `ActivityThread.getSystemContext()`方法
* 如果`mSystemContext`为null,则调用`ContextImpl.createSystemContext()`创建并初始化
```java
public ContextImpl getSystemContext() {
    synchronized (this) {
        if (mSystemContext == null) {
            //调用 ContextImpl.createSystemContext()的方法初始化mSystemContext对象
            mSystemContext = ContextImpl.createSystemContext(this);
        }
        return mSystemContext;
    }
}
```

### `ContextImpl.createSystemContext()`方法
```java
static ContextImpl createSystemContext(ActivityThread mainThread) {
    //创建LoadedApk类型的对象packageInfo
    LoadedApk packageInfo = new LoadedApk(mainThread);
    //创建ContextImpl对象并返回
    ContextImpl context = new ContextImpl(null, mainThread,
            packageInfo, null, null, false, null, null, Display.INVALID_DISPLAY);
    context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(),
            context.mResourcesManager.getDisplayMetricsLocked());
    return context;
}
```

### `LoadedApk.LoadedApk()`构造方法
* 创建关于系统包的信息
* 后面必须调用`installSystemApplicationInfo()`方法
* 构造方法仅仅是初始化各种全局变量
```java
LoadedApk(ActivityThread activityThread) {
    mActivityThread = activityThread;
    //创建一个ApplicationInfo对象，并初始化全局变量mApplicationInfo
    mApplicationInfo = new ApplicationInfo();
    mApplicationInfo.packageName = "android";//mApplicationInfo.packageName没找到啊
    mPackageName = "android";
    mAppDir = null;
    mResDir = null;
    mSplitAppDirs = null;
    mSplitResDirs = null;
    mOverlayDirs = null;
    mSharedLibraries = null;
    mDataDir = null;
    mDataDirFile = null;
    mDeviceProtectedDataDirFile = null;
    mCredentialProtectedDataDirFile = null;
    mLibDir = null;
    mBaseClassLoader = null;
    mSecurityViolation = false;
    mIncludeCode = true;
    mRegisterPackage = false;
    mClassLoader = ClassLoader.getSystemClassLoader();
    mResources = Resources.getSystem();
}
```

#### `ApplicationInfo.ApplicationInfo()`构造方法
* ApplicationInfo的无参构造方法没有任何实现，仅仅是创建一个对象
```java
public ApplicationInfo() {
}
```

### `ContextImpl.ContextImpl()`构造方法
```java
private ContextImpl(ContextImpl container, ActivityThread mainThread,
        LoadedApk packageInfo, IBinder activityToken, UserHandle user, int flags,
        Display display, Configuration overrideConfiguration, int createDisplayWithId) {
    mOuterContext = this;

    // If creator didn't specify which storage to use, use the default
    // location for application.
    if ((flags & (Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE
            | Context.CONTEXT_DEVICE_PROTECTED_STORAGE)) == 0) {
        final File dataDir = packageInfo.getDataDirFile();
        if (Objects.equals(dataDir, packageInfo.getCredentialProtectedDataDirFile())) {
            flags |= Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE;
        } else if (Objects.equals(dataDir, packageInfo.getDeviceProtectedDataDirFile())) {
            flags |= Context.CONTEXT_DEVICE_PROTECTED_STORAGE;
        }
    }

    mMainThread = mainThread;
    mActivityToken = activityToken;
    mFlags = flags;

    if (user == null) {
        user = Process.myUserHandle();
    }
    mUser = user;

    mPackageInfo = packageInfo;
    mResourcesManager = ResourcesManager.getInstance();

    final int displayId = (createDisplayWithId != Display.INVALID_DISPLAY)
            ? createDisplayWithId
            : (display != null) ? display.getDisplayId() : Display.DEFAULT_DISPLAY;

    CompatibilityInfo compatInfo = null;
    if (container != null) {
        compatInfo = container.getDisplayAdjustments(displayId).getCompatibilityInfo();
    }
    if (compatInfo == null) {
        compatInfo = (displayId == Display.DEFAULT_DISPLAY)
                ? packageInfo.getCompatibilityInfo()
                : CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
    }

    Resources resources = packageInfo.getResources(mainThread);
    if (resources != null) {
        if (displayId != Display.DEFAULT_DISPLAY
                || overrideConfiguration != null
                || (compatInfo != null && compatInfo.applicationScale
                        != resources.getCompatibilityInfo().applicationScale)) {

            if (container != null) {
                // This is a nested Context, so it can't be a base Activity context.
                // Just create a regular Resources object associated with the Activity.
                resources = mResourcesManager.getResources(
                        activityToken,
                        packageInfo.getResDir(),
                        packageInfo.getSplitResDirs(),
                        packageInfo.getOverlayDirs(),
                        packageInfo.getApplicationInfo().sharedLibraryFiles,
                        displayId,
                        overrideConfiguration,
                        compatInfo,
                        packageInfo.getClassLoader());
            } else {
                // This is not a nested Context, so it must be the root Activity context.
                // All other nested Contexts will inherit the configuration set here.
                resources = mResourcesManager.createBaseActivityResources(
                        activityToken,
                        packageInfo.getResDir(),
                        packageInfo.getSplitResDirs(),
                        packageInfo.getOverlayDirs(),
                        packageInfo.getApplicationInfo().sharedLibraryFiles,
                        displayId,
                        overrideConfiguration,
                        compatInfo,
                        packageInfo.getClassLoader());
            }
        }
    }
    mResources = resources;

    mDisplay = (createDisplayWithId == Display.INVALID_DISPLAY) ? display
            : mResourcesManager.getAdjustedDisplay(displayId, mResources.getDisplayAdjustments());

    if (container != null) {
        mBasePackageName = container.mBasePackageName;
        mOpPackageName = container.mOpPackageName;
    } else {
        mBasePackageName = packageInfo.mPackageName;
        ApplicationInfo ainfo = packageInfo.getApplicationInfo();
        if (ainfo.uid == Process.SYSTEM_UID && ainfo.uid != Process.myUid()) {
            // Special case: system components allow themselves to be loaded in to other
            // processes.  For purposes of app ops, we must then consider the context as
            // belonging to the package of this process, not the system itself, otherwise
            // the package+uid verifications in app ops will fail.
            mOpPackageName = ActivityThread.currentPackageName();
        } else {
            mOpPackageName = mBasePackageName;
        }
    }

    mContentResolver = new ApplicationContentResolver(this, mainThread, user);
}
```

### `ContextImpl.createAppContext()`方法
```java
static ContextImpl createAppContext(ActivityThread mainThread, LoadedApk packageInfo) {
    if (packageInfo == null) throw new IllegalArgumentException("packageInfo");
    return new ContextImpl(null, mainThread,
            packageInfo, null, null, 0, null, null, Display.INVALID_DISPLAY);
}
```














Step2:
    创建 SystemServicesManager 对象
```java
[4][---SystemServicesManager()][SystemServicesManager]
public SystemServiceManager(Context context) {
    mContext = context;
}
```
Step3:
    调用 startBootstrapService() 方法

```java
[5][---startBootstrapService()][SystemServer]
private void startBootstrapServices() {
    /*
     * 等待Installer完成启动，这样就有机会用适当的权限去创建像/data/user这样关键的目录，
     * 我们需要在初始化其他service之前完成Installer的启动。
     * 调用[mSystemServiceManager]对象的[startService()]方法启动Installer并初始化installer
     */
    Installer installer = mSystemServiceManager.startService(Installer.class);

    //启动ActivityManagerService
    mActivityManagerService = mSystemServiceManager.startService(
                ActivityManagerService.Lifecycle.class).getService();
    mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
    mActivityManagerService.setInstaller(installer);
    ...
    mActivityManagerService.initPowerManagement();
    ...
    mActivityManagerService.setSystemProcess();
}
```

```java
[6][---startService()][SystemServiceManager]
//根据传入的类，利用反射获得他的对象，返回并加入到mServices中，并调用该service的[onStart()]方法
@SuppressWarnings("unchecked")
public <T extends SystemService> T startService(Class<T> serviceClass) {
    try {
        final String name = serviceClass.getName();
        ...
        final T service;
        try {
            Constructor<T> constructor = serviceClass.getConstructor(Context.class);
            // 创建该类（service）的实例（对象）
            service = constructor.newInstance(mContext);
        ...
        }
        ...
        // 将该service添加到[mServices]列表（ArrayList）中
        mServices.add(service);

        // 启动该service
        try {
            service.onStart();
        }
        ...
        // 返回该service对象
        return service;
    }
}
```

```java
[6.1][---Lifecycle()][Lifecycle][ActivityManagerService]
public Lifecycle(Context context) {
    super(context);
    //实例化ActivityManagerService 对象
    mService = new ActivityManagerService(context);
}

```

```java
[6.2][---ActivityManagerService()][ActivityManagerService]
// ActivityManagerService 的构造函数主要是创建4大组件的管理对象和一些内部对象

public ActivityManagerService(Context systemContext) {
    //ContextImpl 实例化对象
    mContext = systemContext;
    //工厂模式类型，0表示非工厂模式
    mFactoryTest = FactoryTest.getMode();

    //获取运行在 SystemServer 中的 ActivityThread 对象
    mSystemThread = ActivityThread.currentActivityThread();

    //创建用于处理消息的线程和Handler对象
    mHandlerThread = new ServiceThread(TAG,
            android.os.Process.THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
    mHandlerThread.start();
    mHandler = new MainHandler(mHandlerThread.getLooper());
    mUiHandler = new UiHandler();

    //创建管理广播的数据结构
    mFgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "foreground", BROADCAST_FG_TIMEOUT, false);
    mBgBroadcastQueue = new BroadcastQueue(this, mHandler,
            "background", BROADCAST_BG_TIMEOUT, true);
    mBroadcastQueues[0] = mFgBroadcastQueue;
    mBroadcastQueues[1] = mBgBroadcastQueue;

    //创建管理组件 Service 的对象
    mServices = new ActiveServices(this);
    //创建管理组件 Provider 的对象
    mProviderMap = new ProviderMap(this);

    //获得 /data 目录
    File dataDir = Environment.getDataDirectory();
    //创建 /data/system 目录
    File systemDir = new File(dataDir, "system");
    systemDir.mkdirs();

    //创建 BatteryStatsService 服务
    mBatteryStatsService = new BatteryStatsService(systemDir, mHandler);
    mBatteryStatsService.getActiveStatistics().readLocked();
    mBatteryStatsService.scheduleWriteToDisk();
    mOnBattery = DEBUG_POWER ? true
            : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
    mBatteryStatsService.getActiveStatistics().setCallback(this);

    //创建 ProcessStatsService 服务
    mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

    //创建 AppOpsService 服务
    mAppOpsService = new AppOpsService(new File(systemDir, "appops.xml"), mHandler);

    //打开 urigrants.xml 文件
    mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"));

    //设置0号用户为第一个用户
    mStartedUsers.put(UserHandle.USER_OWNER, new UserState(UserHandle.OWNER, true));
    mUserLru.add(UserHandle.USER_OWNER);
    updateStartedUserArrayLocked();

    //获取 OpenglES 的版本
    GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
        ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

    mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));

    //设置 mConfiguration 配置项为系统缺省值
    mConfiguration.setToDefaults();
    mConfiguration.setLocale(Locale.getDefault());
    //每次配置变动都会导致seq加一
    mConfigurationSeq = mConfiguration.seq = 1;

    mProcessCpuTracker.init();

    mCompatModePackages = new CompatModePackages(this, systemDir, mHandler);
    //创建 Intent 防火墙
    mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);
    mRecentTasks = new RecentTasks(this);
    //创建 Activity 的管理对象
    mStackSupervisor = new ActivityStackSupervisor(this, mRecentTasks);
    mTaskPersister = new TaskPersister(systemDir, mStackSupervisor, mRecentTasks);
    //创建统计CPU使用情况的线程
    mProcessCpuThread = new Thread("CpuTracker") {
        @Override
        public void run() {
            while (true) {
                try {
                    try {
                        synchronized(this) {
                            final long now = SystemClock.uptimeMillis();
                            long nextCpuDelay = (mLastCpuTime.get()+MONITOR_CPU_MAX_TIME)-now;
                            long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                            if (nextWriteDelay < nextCpuDelay) {
                                nextCpuDelay = nextWriteDelay;
                            }
                            if (nextCpuDelay > 0) {
                                mProcessCpuMutexFree.set(true);
                                this.wait(nextCpuDelay);
                            }
                        }
                    } catch (InterruptedException e) {
                    }
                    updateCpuStatsNow();
                } catch (Exception e) {
                    Slog.e(TAG, "Unexpected exception collecting process stats", e);
                }
            }
        }
    };

    //把服务加到 Watchdog 的监控中
    Watchdog.getInstance().addMonitor(this);
    Watchdog.getInstance().addThread(mHandler);
}
```

```java
[6.3][---onStart()][Lifecycle][ActivityManagerService]
@Override
public void onStart() {
    //调用ActivityManagerService 的start() 方法
    mService.start();
}
```

```java
[6.4][---start()][ActivityManagerService]
private void start() {
    Process.removeAllProcessGroups();
    mProcessCpuThread.start();
    mBatteryStatsService.publish(mContext);
    mAppOpsService.publish(mContext);
    LocalServices.addService(ActivityManagerInternal.class, new LocalService());
}
```

```java
[6.5][---getService()][Lifecycle][ActivityManagerService]
public ActivityManagerService getService() {
    return mService;
}
```

```java
[7][---setSystemProcess()][ActivityManagerService]
/**
 * 主要工作是想ServiceMamager 注册了一些服务
 *
 **/
public void setSystemProcess() {
    try {
        //将ActivityManagerService注册到ServiceManager中
        ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
        //ProcessStats 是 dump 进程信息的服务
        ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
        //MemBinder 是dump系统中每个进程内存使用情况的服务
        ServiceManager.addService("meminfo", new MemBinder(this));
        //GraphicsBinder 是dump每个进程使用图形加速卡状态的服务
        ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
        //DbBinder 是dump系统中每个进程的db状况得服务
        ServiceManager.addService("dbinfo", new DbBinder(this));
        if (MONITOR_CPU_USAGE) {
            ServiceManager.addService("cpuinfo", new CpuBinder(this));
        }
        //PermissionController 是检查 Binder 调用权限的服务
        ServiceManager.addService("permission", new PermissionController(this));
        ServiceManager.addService("processinfo", new ProcessInfoService(this));
        //得到framework-res.apk 的 ApplicationInfo
        ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                "android", STOCK_PM_FLAGS);
        mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

        synchronized (this) {
            //把SystemServer进程本身加入到process的管理中
            ProcessRecord app = newProcessRecordLocked(info, info.processName, false, 0);
            app.persistent = true;
            app.pid = MY_PID;
            app.maxAdj = ProcessList.SYSTEM_ADJ;
            app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.put(app.pid, app);
            }
            updateLruProcessLocked(app, false, null);
            updateOomAdjLocked();
        }
    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException("Unable to find android system package", e);
    }
}
```

【ActivityManager获取代理对象】
    ActivityManager通过调用ActivityManagerNative的静态方法getDefault()来得到ActivityManagerProxy对象的引用
```java
[1][---getDefault()][ActivityManagerNative]
static public IActivityManager getDefault() {
    //返回[gDefault]的[get()]方法，需要知道[gDefault]的定义
    return gDefault.get();
}
```

```java
[2][---gDefault][ActivityManagerNative]
//[gDefault]是一个Singleton对象（单例），
private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
    protected IActivityManager create() {
        IBinder b = ServiceManager.getService("activity");
        IActivityManager am = asInterface(b);
        return am;
    }
};
```

```java
[3][---asInterface][ActivityManagerNative]
static public IActivityManager asInterface(IBinder obj) {
    if (obj == null) {
        return null;
    }
    IActivityManager in =
            (IActivityManager)obj.queryLocalInterface(descriptor);
    if (in != null) {
        return in;
    }
    //返回一个ActivityManagerProxy对象
    return new ActivityManagerProxy(obj);
}
```
### 涉及到的类
[---ActivityManagerService]
    frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java

[---ActivityManagerNative]
    //继承Binder类，实现了IActivityManager，是一个抽象类
    frameworks/base/core/java/android/app/ActivityManagerNative.java

[---ActivityManagerProxy]
    //ActivityManagerNative的内部类
    frameworks/base/core/java/android/app/ActivityManagerNative.java

[---ActivityManager]
    frameworks/base/core/java/android/app/ActivityManager.java

[---ResourcesManager]
    frameworks/base/core/java/android/app/ResourceManager.java

[---ActivityThread]
    frameworks/base/core/java/android/app/ActivityThread.java

[---ContextImpl]
    frameworks/base/core/java/android/app/ContextImpl.java

[---SystemServiceManager]
    frameworks/base/services/core/java/com/android/server/SystemServiceManager.java

[---Lifecycle]
    //ActivityManagerService的内部类，SystemService的子类
    frameworks/base/services/core/java/com/adnroid/server/am/ActivityManagerService.java

[---ActiveServices]
    //用于管理Service的类
    frameworks/base/services/core/java/com/android/server/am/ActiveServices.java

[---ProviderMap]
    //用于管理Provider的类
    frameworks/base/services/core/java/com/android/server/am/ProviderMap.java

[---ActivityStackSupervisor]
    //用于管理Activity的类
    frameworks/base/services/core/java/com/android/server/am/ActivityStackSipervisor.java

[---BroadcastQueue]
    //用于管理Broadcast的类
    frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java
