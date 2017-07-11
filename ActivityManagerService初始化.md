# ActivityManagerService简介
* ActivityManagerService是Android Framework的核心，它管理着Android系统中的4大组件：Activity、Service、ContentProvider和BroadcastReceiver。同时也管理和调度所有用户进程
* ActivityManagerService是Binder服务，但是AMS的Binder框架代码不是通过AIDL自动生成的
* ActivityManagerService是ActivityManagerNative的子类
* 而ActivityManagerNative继承Binder且实现IActivityManager接口
* ActivityManagerProxy也实现了IActivityManager接口，且ActivityManagerProxy是ActivityManagerNative的内部类。
* ActivityManager相当于把ActivityManagerProxy封装起来，ActivityManager通过调用ActivityManagerNative的getDefault()方法来得到ActivityManagerProxy对象的引用。


# ActivityManagerService的初始化
AMS 运行在SystemServer进程中，对象的创建时在SystemServer类初始化时完成的

## `SystemServer.main()` 入口方法
创建SystemServer对象，并调用它的run()方法
```java
public static void main(String[] args) {
    new SystemServer().run();
}
```

## `SystemServer.SystemServer()` 构造方法
初始化`mFactoryTestMode`是否为工厂测试模式
```java
public SystemServer() {
    /* 通过[FactoryTest]对象的[getMode()]方法检查是否为工厂模式，并为[mFactoryTestMode]初始化
     * frameworks/base/core/java/android/os/FactoryTest.java
     */
    mFactoryTestMode = FactoryTest.getMode();
}
```

## `SystemServer.run()` 方法
private void run() {
    try {
        createSystemContext();
        mSystemServiceManager = new SystemServiceManager(mSystemContext)
    }
    ...
    try {
        //mActivityManagerService 在[startBootstrapServices()]方法中初始化
        startBootstrapServices()
        startCoreServices()
        startOtherServices()
    }
    ...
}

Step1:
    创建mSystemContext

[3.1][---createSystemContext][SystemServer]
private void createSystemContext() {
    //创建一个ActivityThread 对象
    ActivityThread activityThread = ActivityThread.systemMain();
    //调用 ActivityThread 的 [getSystemContext()] 方法初始化 mSystemContext 对象
    mSystemContext = activityThread.getSystemContext();
    mSystemContext.setTheme(android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
}

[3.2][---systemMain()][ActivityThread]
public static ActivityThread systemMain() {
    //如果系统进程运行在设备的低内存，不启动硬件加速
    if (!ActivityManager.isHighEndGfx()) {
        HardwareRenderer.disable(true);
    } else {
        HardwareRenderer.enableForegroundTrimming();
    }
    //创建一个 ActivityThread 对象并返回
    ActivityThread thread = new ActivityThread();
    thread.attach(true);
    return thread;
}

[3.2.1][---ActivityThread()][ActivityThread]
ActivityThread() {
    mResourcesManager = ResourcesManager.getInstance();
}

[3.2.2][---attach()][ActivityThread]
private void attach(boolean system) {
    sCurrentActivityThread = this;
    mSystemThread = system;
    if (!system) {
        ViewRootImpl.addFirstDrawHandler(new Runnable() {
            @Override
            public void run() {
                ensureJitEnabled();
            }
        });
        android.ddm.DdmHandleAppName.setAppName("<pre-initialized>",
                                                UserHandle.myUserId());
        RuntimeInit.setApplicationObject(mAppThread.asBinder());
        final IActivityManager mgr = ActivityManagerNative.getDefault();
        try {
            mgr.attachApplication(mAppThread);
        } catch (RemoteException ex) {
            // Ignore
        }
        // Watch for getting close to heap limit.
        BinderInternal.addGcWatcher(new Runnable() {
            @Override public void run() {
                if (!mSomeActivitiesChanged) {
                    return;
                }
                Runtime runtime = Runtime.getRuntime();
                long dalvikMax = runtime.maxMemory();
                long dalvikUsed = runtime.totalMemory() - runtime.freeMemory();
                if (dalvikUsed > ((3*dalvikMax)/4)) {
                    if (DEBUG_MEMORY_TRIM) Slog.d(TAG, "Dalvik max=" + (dalvikMax/1024)
                            + " total=" + (runtime.totalMemory()/1024)
                            + " used=" + (dalvikUsed/1024));
                    mSomeActivitiesChanged = false;
                    try {
                        mgr.releaseSomeActivities(mAppThread);
                    } catch (RemoteException e) {
                    }
                }
            }
        });
    } else {
        // Don't set application object here -- if the system crashes,
        // we can't display an alert, we just want to die die die.
        android.ddm.DdmHandleAppName.setAppName("system_process",
                UserHandle.myUserId());
        try {
            mInstrumentation = new Instrumentation();
            ContextImpl context = ContextImpl.createAppContext(
                    this, getSystemContext().mPackageInfo);
            mInitialApplication = context.mPackageInfo.makeApplication(true, null);
            mInitialApplication.onCreate();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to instantiate Application():" + e.toString(), e);
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

[3.3][---getSystemContext()][ActivityThread]
public ContextImpl getSystemContext() {
    synchronized (this) {
        if (mSystemContext == null) {
            //调用 ContextImpl 的 [createSystemContext()] 的方法初始化mSystemContext对象
            mSystemContext = ContextImpl.createSystemContext(this);
        }
        return mSystemContext;
    }
}

[3.4][---createSystemContext()][ContextImpl]
static ContextImpl createSystemContext(ActivityThread mainThread) {
    LoadedApk packageInfo = new LoadedApk(mainThread);
    ContextImpl context = new ContextImpl(null, mainThread,
            packageInfo, null, null, false, null, null, Display.INVALID_DISPLAY);
    context.mResources.updateConfiguration(context.mResourcesManager.getConfiguration(),
            context.mResourcesManager.getDisplayMetricsLocked());
    return context;
}


Step2:
    创建 SystemServicesManager 对象
[4][---SystemServicesManager()][SystemServicesManager]
public SystemServiceManager(Context context) {
    mContext = context;
}

Step3:
    调用 startBootstrapService() 方法

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

[6.1][---Lifecycle()][Lifecycle][ActivityManagerService]
public Lifecycle(Context context) {
    super(context);
    //实例化ActivityManagerService 对象
    mService = new ActivityManagerService(context);
}

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

[6.3][---onStart()][Lifecycle][ActivityManagerService]
@Override
public void onStart() {
    //调用ActivityManagerService 的start() 方法
    mService.start();
}

[6.4][---start()][ActivityManagerService]
private void start() {
    Process.removeAllProcessGroups();
    mProcessCpuThread.start();
    mBatteryStatsService.publish(mContext);
    mAppOpsService.publish(mContext);
    LocalServices.addService(ActivityManagerInternal.class, new LocalService());
}

[6.5][---getService()][Lifecycle][ActivityManagerService]
public ActivityManagerService getService() {
    return mService;
}


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


【ActivityManager获取代理对象】
    ActivityManager通过调用ActivityManagerNative的静态方法getDefault()来得到ActivityManagerProxy对象的引用
[1][---getDefault()][ActivityManagerNative]
static public IActivityManager getDefault() {
    //返回[gDefault]的[get()]方法，需要知道[gDefault]的定义
    return gDefault.get();
}

[2][---gDefault][ActivityManagerNative]
//[gDefault]是一个Singleton对象（单例），
private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
    protected IActivityManager create() {
        IBinder b = ServiceManager.getService("activity");
        IActivityManager am = asInterface(b);
        return am;
    }
};

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
