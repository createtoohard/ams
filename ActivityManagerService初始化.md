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
2. 调用`SystemServiceManager.startService()`启动并初始化AMS
3. 调用系统准备好时调用`ActivityManagerService.systemReady()`方法

## AMS的初始化流程时序图
```puml
Title : AMS初始化时序图
SystemServer -> SystemServer : main()
SystemServer -> SystemServer : SystemServer()
SystemServer -> SystemServer : run()

Note left of SystemServer : 初始化ContextImpl对象
SystemServer -> SystemServer : createSystemContext()
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
ActivityThread -> ContextImpl : createAppContext()
ContextImpl -> ContextImpl : ContextImpl()
Note right of ActivityThread : 返回ContextImpl对象
ContextImpl --> ActivityThread : return context


ActivityThread -> LoadedApk : makeApplication()
LoadedApk -> Instrumentation : newApplication()
Instrumentation -> Application : attach()
Application -> ContextWrapper : attachBaseContext()
Note left of Instrumentation : 返回创建的Application对象
Instrumentation -> ActivityThread : return app


SystemServer -> SystemServer : startBootstrapServices()
Note left of SystemServer : 初始化AMS对象
SystemServer -> SystemServiceManager : startService(Ams.Lifecycle)
SystemServiceManager -> AMS.Lifecycle : Lifecycle()
AMS.Lifecycle -> AMS : ActivityManagerService()
SystemServiceManager -> AMS.Lifecycle : onStart()
AMS.Lifecycle -> AMS : start()
SystemServer -> AMS.Lifecycle : getService()
Note left of AMS.Lifecycle : 返回AMS对象
AMS.Lifecycle -> SystemServer : return mService


SystemServer -> SystemServer : startOtherServices()
Note left of SystemServer : SystemReady
SystemServer -> AMS : SystemReady()
AMS -> AMS : removeProcessLocked()
AMS -> AMS : retrieveSettings()

AMS -> SystemServer : goingCallback.run()
SystemServer -> SystemServiceManager : startBootPhase()
SystemServer -> SystemServer : startSystemUi()
SystemServer -> SystemServiceManager : startBootPhase()

AMS -> SystemServiceManager : startUser()
AMS -> AMS : startPersistentApps()
AMS -> AMS : startHomeActivityLocked()
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
        mSystemServiceManager = new SystemServiceManager(mSystemContext);
    }
    //...
    try {
        //mActivityManagerService 在startBootstrapServices()方法中初始化
        startBootstrapServices();
        startCoreServices();
        startOtherServices();
    }
}
```

---
# 1. 初始化ContextImpl对象
## 1. `SystemServer.createSystemContext()`方法
* 创建SystemContext并初始化SystemServer中的`mSystemContext`变量
* 创建一个系统应用名为android的Application对象
```java
private void createSystemContext() {
    //调用ActivityThread.systemMain()方法
    ActivityThread activityThread = ActivityThread.systemMain();
    //调用 ActivityThread的getSystemContext()方法初始化 mSystemContext 对象
    mSystemContext = activityThread.getSystemContext();
    //设置系统主题
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

## `ActivityThread.attach()`方法
1. 创建一个ContextImpl对象
2. 创建一个Application对象，并调用它的`onCreate()`方法
3. 向ViewRootImpl中注册一个设置变化的监听回调
```java
Application mInitialApplication;

private void attach(boolean system) {
    //将ActivityThread保存在sCurrentActivityThread变量中
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
            //这里传入的是LoadedApk
            ContextImpl context = ContextImpl.createAppContext(
                    this, getSystemContext().mPackageInfo);
            //调用LoadedApk.makeApplication()方法
            mInitialApplication = context.mPackageInfo.makeApplication(true, null);
            mInitialApplication.onCreate();//调用Application.onCreate()方法
        } catch (Exception e) {
            throw new RuntimeException(...);
        }
    }

    // add dropbox logging to libcore
    DropBox.setReporter(new DropBoxReporter());

    //注册一个设置变化的回调接口道ViewRootImpl中
    ViewRootImpl.addConfigCallback(new ComponentCallbacks2() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            synchronized (mResourcesManager) {
                //...
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

### 1.1 创建`ContextImpl`对象
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
    //ApplicationInfo 继承 PackageItemInfo，packageName定义在PackageItemInfo中
    mApplicationInfo.packageName = "android";
    mPackageName = "android";
    //...
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
* 初始化`ContextImpl`类中的各种成员变量
```java
private ContextImpl(ContextImpl container, ActivityThread mainThread,
        LoadedApk packageInfo, IBinder activityToken, UserHandle user, int flags,
        Display display, Configuration overrideConfiguration, int createDisplayWithId) {
    mOuterContext = this;

    // 如果创建者没有指定要使用的存储，使用应用程序的默认位置
    if ((flags & (Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE
            | Context.CONTEXT_DEVICE_PROTECTED_STORAGE)) == 0) {
        final File dataDir = packageInfo.getDataDirFile();
        if (Objects.equals(dataDir, packageInfo.getCredentialProtectedDataDirFile())) {
            flags |= Context.CONTEXT_CREDENTIAL_PROTECTED_STORAGE;
        } else if (Objects.equals(dataDir, packageInfo.getDeviceProtectedDataDirFile())) {
            flags |= Context.CONTEXT_DEVICE_PROTECTED_STORAGE;
        }
    }
    //初始化成员变量
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
    //创建一个ContextImpl对象并返回
    return new ContextImpl(null, mainThread,
            packageInfo, null, null, 0, null, null, Display.INVALID_DISPLAY);
}
```

### 1.2 `LoadedApk.makeApplication()`方法
```java
private Application mApplication;

public Application makeApplication(boolean forceDefaultAppClass,
        Instrumentation instrumentation) {
    if (mApplication != null) {
        return mApplication;
    }

    Application app = null;

    String appClass = mApplicationInfo.className;
    if (forceDefaultAppClass || (appClass == null)) {
        //从上面的流程走下来，由于forceDefaultAppClass为true，所以能走到这里
        appClass = "android.app.Application";
    }

    try {
        java.lang.ClassLoader cl = getClassLoader();
        if (!mPackageName.equals("android")) {
            initializeJavaContextClassLoader();
        }

        ContextImpl appContext = ContextImpl.createAppContext(mActivityThread, this);
        //调用Instrumentation.newApplication()方法
        app = mActivityThread.mInstrumentation.newApplication(
                cl, appClass, appContext);
        //调用ContextImpl.setOuterContext()方法
        appContext.setOuterContext(app);
    } catch (Exception e) {...}
    //因为一个进程可以存在多个Application
    //将创建的Application对象加入到ActivityThread.mAllApplications列表中
    mActivityThread.mAllApplications.add(app);
    mApplication = app;

    if (instrumentation != null) {
        try {
            //调用Instrumentation.callApplicationOnCreate()方法，调用Application的onCreate()方法
            instrumentation.callApplicationOnCreate(app);
        } catch (Exception e) {...}
        }
    }

    // Rewrite the R 'constants' for all library apks.
    SparseArray<String> packageIdentifiers = getAssets(mActivityThread)
            .getAssignedPackageIdentifiers();
    final int N = packageIdentifiers.size();
    for (int i = 0; i < N; i++) {
        final int id = packageIdentifiers.keyAt(i);
        if (id == 0x01 || id == 0x7f) {
            continue;
        }

        rewriteRValues(getClassLoader(), packageIdentifiers.valueAt(i), id);
    }
    return app;
}
```


### `Instrumentation.newApplication()`方法
* 通过反射创建一个Application对象
* 调用Application的`attach()`方法初始化`ContextWrapper`中的`Context`类型的全局变量`mBase`
* 初始化`Application`类中的`LoadedApk`类型的全局变量`mLoadedApk`
```java
public Application newApplication(ClassLoader cl, String className, Context context)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    //调用重载方法
    return newApplication(cl.loadClass(className), context);
}

static public Application newApplication(Class<?> clazz, Context context)
        throws InstantiationException, IllegalAccessException,
        ClassNotFoundException {
    Application app = (Application)clazz.newInstance();
    //调用Application.attach()方法，并返回Application对象
    app.attach(context);
    return app;
}
```

#### `Application.attach()`方法
```java
final void attach(Context context) {
    //调用attachBaseContext()方法，Application继承ContextWrapper，该方法是属于父类的
    attachBaseContext(context);
    //初始化成员变量mLoadedApk
    mLoadedApk = ContextImpl.getImpl(context).mPackageInfo;
}
```

#### `ContextWrapper.attachBaseContext()`方法
* 初始化ContextWrapper中的Context类型的成员函数mBase
```java
Context mBase;

protected void attachBaseContext(Context base) {
    if (mBase != null) {
        throw new IllegalStateException("Base context already set");
    }
    mBase = base;
}
```

### `Instrumentation.callApplicationOnCreate()`方法
```java
public void callApplicationOnCreate(Application app) {
    app.onCreate();
}
```

---
# 2. 初始化ActivityManagerService对象
## `SystemServer.startBootstrapService()`方法
```java
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
    //...
    mActivityManagerService.initPowerManagement();
    //...
    mActivityManagerService.setSystemProcess();
}
```

### 2.1 `ActivityManagerService.Lifecycle.Lifecycle()`构造方法
```java
public Lifecycle(Context context) {
    super(context);
    //实例化ActivityManagerService 对象
    mService = new ActivityManagerService(context);
}

```

### `ActivityManagerService.ActivityManagerService()`构造方法
* ActivityManagerService 的构造函数主要是创建4大组件的管理对象和一些内部对象
```java
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

### `ActivityManagerService.Lifecycle.startService()`方法
```java
@Override
public void onStart() {
    //调用ActivityManagerService 的start() 方法
    mService.start();
}
```

### `ActivityManagerService.start()`方法
```java
private void start() {
    Process.removeAllProcessGroups();
    mProcessCpuThread.start();
    mBatteryStatsService.publish(mContext);
    mAppOpsService.publish(mContext);
    LocalServices.addService(ActivityManagerInternal.class, new LocalService());
}
```

### `ActivityManagerService.Lifecycle.getService()`方法
```java
public ActivityManagerService getService() {
    return mService;//返回ActivityManagerService对象mService
}
```


### 3. `ActivityManagerService.setSystemProcess()`方法
* 向ServiceManager中注册了一些服务
```java
final ActivityThread mSystemThread;

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
        //调用.installSystemApplicationInfo()方法
        mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

        synchronized (this) {
            //把SystemServer进程本身加入到process的管理中
            //创建进程管理结构对象ProcessRecord
            ProcessRecord app = newProcessRecordLocked(info, info.processName, false, 0);
            app.persistent = true;
            app.pid = MY_PID;
            app.maxAdj = ProcessList.SYSTEM_ADJ;
            app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.put(app.pid, app);
            }
            //更新进程lru列表和优先级
            updateLruProcessLocked(app, false, null);
            updateOomAdjLocked();
        }
    } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException("Unable to find android system package", e);
    }
}
```


# 3. SystemReady
## `SystemServer.startOtherServices()`方法
```java
private void startOthersServices() {
    mActivityManagerService.systemReady(new Runnable() {
        @Override
        public void run() {
            //调用SystemServiceManager.startBootPhase()方法，即调用所有service的onBootPhase()方法
            mSystemServiceManager.startBootPhase(
                    SystemService.PHASE_ACTIVITY_MANAGER_READY);
            //...
            try {
                //调用startSystemUi()方法，启动SystemUI
                startSystemUi(context);
            } catch (Throwable e) {}
            //...
            //启动Watchdog
            Watchdog.getInstance().start();
            //调用SystemServiceManager.startBootPhase()方法
            mSystemServiceManager.startBootPhase(
                    SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
            //...
        }
    });
}
```

### `SystemServer.startSystemUi()`方法
```java
static final void startSystemUi(Context context) {
    Intent intent = new Intent();
    intent.setComponent(new ComponentName("com.android.systemui",
                "com.android.systemui.SystemUIService"));
    intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
    context.startServiceAsUser(intent, UserHandle.SYSTEM);
}
```

### `ActivityManagerService.systemReady()`方法
```java
//判断系统是否准备成功的标志
volatile boolean mSystemReady = false;
//以pid为key的组合正在运行的所有进程的数组
final SparseArray<ProcessRecord> mPidsSelfLocked;

volatile boolean mProcessesReady = false;

public void systemReady(final Runnable goingCallback) {
    synchronized(this) {
        //如果系统已经准备成功，即mSystemReady为true时，调用SystemServer中调用该方法时传入的Runnable.run()方法
        if (mSystemReady) {
            if (goingCallback != null) {
                goingCallback.run();
            }
            return;
        }
        //获取DeviceIdleController.LocalService对象（Doze模式）
        mLocalDeviceIdleController
                = LocalServices.getService(DeviceIdleController.LocalService.class);

        //调用以下几个对象的onSystemReady()或onSystemReadyLocked()方法
        mUserController.onSystemReady();
        mRecentTasks.onSystemReadyLocked();
        mAppOpsService.systemReady();
        //将mSystemReady设为true，则下次就不会再往下走了
        mSystemReady = true;
    }

    ArrayList<ProcessRecord> procsToKill = null;
    synchronized(mPidsSelfLocked) {
        for (int i=mPidsSelfLocked.size()-1; i>=0; i--) {
            ProcessRecord proc = mPidsSelfLocked.valueAt(i);
            //如果不是persistent的进程，加入到procsToKill列表中
            if (!isAllowedWhileBooting(proc.info)){
                if (procsToKill == null) {
                    procsToKill = new ArrayList<ProcessRecord>();
                }
                procsToKill.add(proc);
            }
        }
    }

    synchronized(this) {
        if (procsToKill != null) {
            for (int i=procsToKill.size()-1; i>=0; i--) {
                ProcessRecord proc = procsToKill.get(i);
                //调用removeProcessLocked()方法，杀死进程
                removeProcessLocked(proc, true, false, "system update done");
            }
        }
        //将mProcessesReady设为true
        mProcessesReady = true;
    }

    synchronized(this) {
        if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            //如果是工厂测试模式
        }
    }
    //调用retrieveSettings()方法，根据数据库和资源文件配置一些参数
    retrieveSettings();
    final int currentUserId;
    synchronized (this) {
        //调用UserController.getCurrentUserIdLocked()方法，获得当前userid
        currentUserId = mUserController.getCurrentUserIdLocked();
        //urigrants.xml文件相关操作
        readGrantedUriPermissionsLocked();
    }

    //调用传入的在SystemServer中定义的Runnable.run()方法
    if (goingCallback != null) goingCallback.run();
    //Battery相关
    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
            Integer.toString(currentUserId), currentUserId);
    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
            Integer.toString(currentUserId), currentUserId);

    //调用SystemServiceManager.startUser()方法，即调用所有service的onStartUser()方法
    mSystemServiceManager.startUser(currentUserId);

    synchronized (this) {
        //调用startPersistentApps()方法启动persistent为1的应用的进程
        startPersistentApps(PackageManager.MATCH_DIRECT_BOOT_AWARE);

        // Start up initial activity.
        mBooting = true;
        // Enable home activity for system user, so that the system can always boot
        if (UserManager.isSplitSystemUser()) {
            ComponentName cName = new ComponentName(mContext, SystemUserHomeActivity.class);
            try {
                AppGlobals.getPackageManager().setComponentEnabledSetting(cName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0,
                        UserHandle.USER_SYSTEM);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
        //调用startHomeActivityLocked()方法，启动home(Launcher)
        startHomeActivityLocked(currentUserId, "systemReady");

        try {
            //发送处理uid错误的消息
            if (AppGlobals.getPackageManager().hasSystemUidErrors()) {
                mUiHandler.obtainMessage(SHOW_UID_ERROR_UI_MSG).sendToTarget();
            }
        } catch (RemoteException e) {}

        if (!Build.isBuildConsistent()) {
            mUiHandler.obtainMessage(SHOW_FINGERPRINT_ERROR_UI_MSG).sendToTarget();
        }

        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(Intent.ACTION_USER_STARTED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, currentUserId);
            broadcastIntentLocked(null, null, intent,
                    null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                    null, false, false, MY_PID, Process.SYSTEM_UID,
                    currentUserId);
            intent = new Intent(Intent.ACTION_USER_STARTING);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, currentUserId);
            broadcastIntentLocked(null, null, intent,
                    null, new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode, String data,
                                Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                throws RemoteException {
                        }
                    }, 0, null, null,
                    new String[] {INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                    null, true, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
        } catch (Throwable t) {} finally {
            Binder.restoreCallingIdentity(ident);
        }
        mStackSupervisor.resumeFocusedStackTopActivityLocked();
        mUserController.sendUserSwitchBroadcastsLocked(-1, currentUserId);
    }
}
```

### `ActivityManagerService.isAllowedWhileBooting()`方法
* 判断应用是否为FLAG_PERSISTENT进程
```java
boolean isAllowedWhileBooting(ApplicationInfo ai) {
    return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
}
```

### `ActivityManagerService.startPersistentApps()`方法
```java
private void startPersistentApps(int matchFlags) {
    if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) return;

    synchronized (this) {
        try {
            final List<ApplicationInfo> apps = AppGlobals.getPackageManager()
                    .getPersistentApplications(STOCK_PM_FLAGS | matchFlags).getList();
            for (ApplicationInfo app : apps) {
                if (!"android".equals(app.packageName)) {
                    addAppLocked(app, false, null /* ABI override */);
                }
            }
        } catch (RemoteException ex) {
        }
    }
}
```

### `ActivityManagerService.startHomeActivityLocked()`方法
* 启动home
```java
boolean startHomeActivityLocked(int userId, String reason) {
    //如果是工厂测试相关，返回false
    if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL
            && mTopAction == null) {
        return false;
    }
    Intent intent = getHomeIntent();
    ActivityInfo aInfo = resolveActivityInfo(intent, STOCK_PM_FLAGS, userId);
    if (aInfo != null) {
        intent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
        aInfo = new ActivityInfo(aInfo);
        aInfo.applicationInfo = getAppInfoForUser(aInfo.applicationInfo, userId);
        ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                aInfo.applicationInfo.uid, true);
        if (app == null || app.instrumentationClass == null) {
            intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivityStarter.startHomeActivityLocked(intent, aInfo, reason);
        }
    } else {}

    return true;
}
```


# ActivityManager获取代理对象
* ActivityManager通过调用ActivityManagerNative的静态方法getDefault()来得到ActivityManagerProxy对象的引用

### `ActivityManagerNative.getDefault()`方法
```java
static public IActivityManager getDefault() {
    //返回[gDefault]的[get()]方法，需要知道[gDefault]的定义
    return gDefault.get();
}
```

### `ActivityManagerNative.gDefault`
```java
//[gDefault]是一个Singleton对象（单例），
private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
    protected IActivityManager create() {
        IBinder b = ServiceManager.getService("activity");
        IActivityManager am = asInterface(b);
        return am;
    }
};
```

### `ActivityManagerNative.asInterface()`方法
```java
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


# SystemServiceManager的启动方式
### `SystemServiceManager.startService()`方法
```java
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
        //...
        }
        //...
        // 将该service添加到[mServices]列表（ArrayList）中
        mServices.add(service);

        // 启动该service
        try {
            service.onStart();
        }
        //...
        // 返回该service对象
        return service;
    }
}
```

### 涉及到的类
* ActivityManagerService
    `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`

* ActivityManagerNative
    //继承Binder类，实现了IActivityManager，是一个抽象类
    `frameworks/base/core/java/android/app/ActivityManagerNative.java`

* ActivityManagerProxy
    //ActivityManagerNative的内部类
    `frameworks/base/core/java/android/app/ActivityManagerNative.java`

* ActivityManager
    `frameworks/base/core/java/android/app/ActivityManager.java`

* ResourcesManager
    `frameworks/base/core/java/android/app/ResourceManager.java`

* ActivityThread
    `frameworks/base/core/java/android/app/ActivityThread.java`

* ContextImpl
    `frameworks/base/core/java/android/app/ContextImpl.java`

* SystemServiceManager
    `frameworks/base/services/core/java/com/android/server/SystemServiceManager.java`

* Lifecycle
    //ActivityManagerService的内部类，SystemService的子类
    `frameworks/base/services/core/java/com/adnroid/server/am/ActivityManagerService.java`

* ActiveServices
    //用于管理Service的类
    `frameworks/base/services/core/java/com/android/server/am/ActiveServices.java`

* ProviderMap
    //用于管理Provider的类
    `frameworks/base/services/core/java/com/android/server/am/ProviderMap.java`

* ActivityStackSupervisor
    //用于管理Activity的类
    `frameworks/base/services/core/java/com/android/server/am/ActivityStackSipervisor.java`

* BroadcastQueue
    //用于管理Broadcast的类
    `frameworks/base/services/core/java/com/android/server/am/BroadcastQueue.java`
