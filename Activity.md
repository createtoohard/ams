# Activity

# Activity的启动
## Activity启动流程图
```puml
Title : Activity启动流程图
Launcher -> Launcher : startActivitySafely()
Launcher -> Launcher : startActivity()
Launcher -> Activity : startActivity()

Activity -> Activity : startActivityForResult()
Activity -> Instrumentation : execStartActivity()
Instrumentation -> AMS : startActivity()
AMS -> AMS : startActivityAsUser()
AMS -> ActivityStarter : startActivityMayWait()

ActivityStarter -> ActivityStackSupervisor : resolveIntent()
ActivityStackSupervisor -> PMS : queryIntentActivitiesInternal()
PMS -> Intent : getComponent()
PMS -> PMS : getActivityInfo()
PMS -> PMS : getMatchingCrossProfileIntentFilters()
ActivityStarter -> ActivityStackSupervisor : resolveActivity()
ActivityStarter -> ActivityStarter : startActivityLocked()

Instrumentation -> Activity : 返回Result
Activity -> ActivityThread : sendActivityResult(Result)

```


# 相关的类
#### Instrumentation
* 用于实现应用程序代码的基类
* 当打开Instrumentation的时候，该类会在所有应用程序之前进行初始化，允许跟踪监听系统与应用的所有交互
* Instrumentation的实现是通过AndroidManifest.xml中instrumentation标签的描述

#### ActivityStarter
* 解释执行如何启动Activity的控制器
* 该类收集所有的逻辑来决定如何将intent和flags转换为Activity和对应的task以及stack

#### ActivityStackSupervisor
* 用于管理Activity栈信息？？？？？

#### ActivityThread

#### ActivityInfo
* 该类可以查找特定应用Activity和Receiver的信息
* 这些信息相当于是从AndroidManifest.xml中的Activity和Receiver标签中收集的

#### ResolveInfo
* 从一个Intent解析到一个Intent对应的IntentFilter的返回信息，这部分对应信息从AndroidManifest.xml中手机

### `Activity.startActivity()`方法
```java
public void startActivity(Intent intent) {
    this.startActivity(intent, null);
}

@Override
public void startActivity(Intent intent, @Nullable Bundle options) {
    if (options != null) {
        startActivityForResult(intent, -1, options);
    } else {
        // Note we want to go through this call for compatibility with
        // applications that may have overridden the method.
        startActivityForResult(intent, -1);
    }
}
```

### `Activity.startActivityForResult()`方法
```java
public void startActivityForResult(@RequiresPermission Intent intent, int requestCode) {
    startActivityForResult(intent, requestCode, null);
}

public void startActivityForResult(@RequiresPermission Intent intent, int requestCode,
        @Nullable Bundle options) {
    if (mParent == null) {
        // 调用 Instrumentation的execStartActivity()方法
        Instrumentation.ActivityResult ar =
            mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, this,
                intent, requestCode, options);
        if (ar != null) {
            mMainThread.sendActivityResult(
                mToken, mEmbeddedID, requestCode, ar.getResultCode(),
                ar.getResultData());
        }
        if (requestCode >= 0) {
            mStartedActivity = true;
        }
        cancelInputsAndStartExitTransition(options);
    } else {
        if (options != null) {
            mParent.startActivityFromChild(this, intent, requestCode, options);
        } else {
            mParent.startActivityFromChild(this, intent, requestCode);
        }
    }
}
```


### `Instrumentation.execStartActivity()`方法
```java
public ActivityResult execStartActivity(
    Context who, IBinder contextThread, IBinder token, String target,
    Intent intent, int requestCode, Bundle options) {
    IApplicationThread whoThread = (IApplicationThread) contextThread;
    if (mActivityMonitors != null) {
        synchronized (mSync) {
            final int N = mActivityMonitors.size();
            for (int i=0; i<N; i++) {
                final ActivityMonitor am = mActivityMonitors.get(i);
                if (am.match(who, null, intent)) {
                    am.mHits++;
                    if (am.isBlocking()) {
                        return requestCode >= 0 ? am.getResult() : null;
                    }
                    break;
                }
            }
        }
    }
    try {
        intent.migrateExtraStreamToClipData();
        intent.prepareToLeaveProcess(who);
        //调用AMS.startActivity()方法
        int result = ActivityManagerNative.getDefault()
            .startActivity(whoThread, who.getBasePackageName(), intent,
                    intent.resolveTypeIfNeeded(who.getContentResolver()),
                    token, target, requestCode, 0, null, options);
        checkStartActivityResult(result, intent);
    } catch (RemoteException e) {
        throw new RuntimeException("Failure from system", e);
    }
    return null;
}
```

### `ActivityManagerService.startActivity()`方法
```java
@Override
public final int startActivity(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
    return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
            resultWho, requestCode, startFlags, profilerInfo, bOptions,
            UserHandle.getCallingUserId());
}
```

### `ActivityManagerService.startActivityAsUser()`方法
```java
@Override
public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
        Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
        int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
    enforceNotIsolatedCaller("startActivity");
    userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
            userId, false, ALLOW_FULL_ONLY, "startActivity", null);
    // TODO: Switch to user app stacks here.
    //调用ActivityStarter的startActivityMayWait()方法
    return mActivityStarter.startActivityMayWait(caller, -1, callingPackage, intent,
            resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
            profilerInfo, null, null, bOptions, false, userId, null, null);
}
```


### `ActivityStarter.startActivityMayWait()`方法
```java
private final ActivityStackSupervisor mSupervisor;

final int startActivityMayWait(IApplicationThread caller, int callingUid,
        String callingPackage, Intent intent, String resolvedType,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        IBinder resultTo, String resultWho, int requestCode, int startFlags,
        ProfilerInfo profilerInfo, IActivityManager.WaitResult outResult, Configuration config,
        Bundle bOptions, boolean ignoreTargetSecurity, int userId,
        IActivityContainer iContainer, TaskRecord inTask) {
    // 不能泄露文件描述符
    if (intent != null && intent.hasFileDescriptors()) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }
    mSupervisor.mActivityMetricsLogger.notifyActivityLaunching();
    boolean componentSpecified = intent.getComponent() != null;

    //短暂的需要保存一个副本
    final Intent ephemeralIntent = new Intent(intent);
    //不更改客户端的intent，所以在这里备份一个
    intent = new Intent(intent);
    //调用ActivityStackSupervisor.resolveIntent()方法
    ResolveInfo rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
    //如果ResolveInfo为空
    if (rInfo == null) {
        UserInfo userInfo = mSupervisor.getUserInfo(userId);
        if (userInfo != null && userInfo.isManagedProfile()) {
            // 用户权限管理相关
            UserManager userManager = UserManager.get(mService.mContext);
            boolean profileLockedAndParentUnlockingOrUnlocked = false;
            long token = Binder.clearCallingIdentity();
            try {
                UserInfo parent = userManager.getProfileParent(userId);
                profileLockedAndParentUnlockingOrUnlocked = (parent != null)
                        && userManager.isUserUnlockingOrUnlocked(parent.id)
                        && !userManager.isUserUnlockingOrUnlocked(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (profileLockedAndParentUnlockingOrUnlocked) {
                rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            }
        }
    }

    //调用ActivityStackSupervisor.resolveActivity()方法，找到合适的最佳的被启动的Activity信息
    ActivityInfo aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, profilerInfo);

    ActivityOptions options = ActivityOptions.fromBundle(bOptions);
    ActivityStackSupervisor.ActivityContainer container =
            (ActivityStackSupervisor.ActivityContainer)iContainer;
    synchronized (mService) {
        if (container != null && container.mParentActivity != null &&
                container.mParentActivity.state != RESUMED) {
            // Cannot start a child activity if the parent is not resumed.
            return ActivityManager.START_CANCELED;
        }
        final int realCallingPid = Binder.getCallingPid();
        final int realCallingUid = Binder.getCallingUid();
        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = realCallingPid;
            callingUid = realCallingUid;
        } else {
            callingPid = callingUid = -1;
        }

        final ActivityStack stack;
        if (container == null || container.mStack.isOnHomeDisplay()) {
            stack = mSupervisor.mFocusedStack;
        } else {
            stack = container.mStack;
        }
        stack.mConfigWillChange = config != null && mService.mConfiguration.diff(config) != 0;

        final long origId = Binder.clearCallingIdentity();

        if (aInfo != null &&
                (aInfo.applicationInfo.privateFlags
                        & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
            // This may be a heavy-weight process!  Check to see if we already
            // have another, different heavy-weight process running.
            if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                final ProcessRecord heavy = mService.mHeavyWeightProcess;
                if (heavy != null && (heavy.info.uid != aInfo.applicationInfo.uid
                        || !heavy.processName.equals(aInfo.processName))) {
                    int appCallingUid = callingUid;
                    if (caller != null) {
                        ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                        if (callerApp != null) {
                            appCallingUid = callerApp.info.uid;
                        } else {
                            ActivityOptions.abort(options);
                            return ActivityManager.START_PERMISSION_DENIED;
                        }
                    }

                    IIntentSender target = mService.getIntentSenderLocked(
                            ActivityManager.INTENT_SENDER_ACTIVITY, "android",
                            appCallingUid, userId, null, null, 0, new Intent[] { intent },
                            new String[] { resolvedType }, PendingIntent.FLAG_CANCEL_CURRENT
                                    | PendingIntent.FLAG_ONE_SHOT, null);

                    Intent newIntent = new Intent();
                    if (requestCode >= 0) {
                        // Caller is requesting a result.
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
                    }
                    newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT,
                            new IntentSender(target));
                    if (heavy.activities.size() > 0) {
                        ActivityRecord hist = heavy.activities.get(0);
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP,
                                hist.packageName);
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK,
                                hist.task.taskId);
                    }
                    newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                            aInfo.packageName);
                    newIntent.setFlags(intent.getFlags());
                    newIntent.setClassName("android",
                            HeavyWeightSwitcherActivity.class.getName());
                    intent = newIntent;
                    resolvedType = null;
                    caller = null;
                    callingUid = Binder.getCallingUid();
                    callingPid = Binder.getCallingPid();
                    componentSpecified = true;
                    rInfo = mSupervisor.resolveIntent(intent, null /*resolvedType*/, userId);
                    aInfo = rInfo != null ? rInfo.activityInfo : null;
                    if (aInfo != null) {
                        aInfo = mService.getActivityInfoForUser(aInfo, userId);
                    }
                }
            }
        }

        final ActivityRecord[] outRecord = new ActivityRecord[1];
        int res = startActivityLocked(caller, intent, ephemeralIntent, resolvedType,
                aInfo, rInfo, voiceSession, voiceInteractor,
                resultTo, resultWho, requestCode, callingPid,
                callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                options, ignoreTargetSecurity, componentSpecified, outRecord, container,
                inTask);

        Binder.restoreCallingIdentity(origId);

        if (stack.mConfigWillChange) {
            // If the caller also wants to switch to a new configuration,
            // do so now.  This allows a clean switch, as we are waiting
            // for the current activity to pause (so we will not destroy
            // it), and have not yet started the next activity.
            mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                    "updateConfiguration()");
            stack.mConfigWillChange = false;
            mService.updateConfigurationLocked(config, null, false);
        }

        if (outResult != null) {
            outResult.result = res;
            if (res == ActivityManager.START_SUCCESS) {
                mSupervisor.mWaitingActivityLaunched.add(outResult);
                do {
                    try {
                        mService.wait();
                    } catch (InterruptedException e) {
                    }
                } while (outResult.result != START_TASK_TO_FRONT
                        && !outResult.timeout && outResult.who == null);
                if (outResult.result == START_TASK_TO_FRONT) {
                    res = START_TASK_TO_FRONT;
                }
            }
            if (res == START_TASK_TO_FRONT) {
                ActivityRecord r = stack.topRunningActivityLocked();
                if (r.nowVisible && r.state == RESUMED) {
                    outResult.timeout = false;
                    outResult.who = new ComponentName(r.info.packageName, r.info.name);
                    outResult.totalTime = 0;
                    outResult.thisTime = 0;
                } else {
                    outResult.thisTime = SystemClock.uptimeMillis();
                    mSupervisor.mWaitingActivityVisible.add(outResult);
                    do {
                        try {
                            mService.wait();
                        } catch (InterruptedException e) {
                        }
                    } while (!outResult.timeout && outResult.who == null);
                }
            }
        }

        final ActivityRecord launchedActivity = mReusedActivity != null
                ? mReusedActivity : outRecord[0];
        mSupervisor.mActivityMetricsLogger.notifyActivityLaunched(res, launchedActivity);
        return res;
    }
}
```


### `ActivityStackSupervisor.resolveIntent()`方法
```java
ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId) {
    return resolveIntent(intent, resolvedType, userId, 0);
}

ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId, int flags) {
    try {
        //调用PackageManagerService.resolveIntent()方法
        return AppGlobals.getPackageManager().resolveIntent(intent, resolvedType,
                PackageManager.MATCH_DEFAULT_ONLY | flags
                | ActivityManagerService.STOCK_PM_FLAGS, userId);
    } catch (RemoteException e) {
    }
    return null;
}
```


#### `AppGlobals.getPackageManager()`方法
获取PackageManagerService对象
```java
public static IPackageManager getPackageManager() {
    //调用ActivityThread.getPackageManager()方法
    return ActivityThread.getPackageManager();
}
```

#### `ActivityThread.getPackageManager()`方法
```java
static volatile IPackageManager sPackageManager;

public static IPackageManager getPackageManager() {
    if (sPackageManager != null) {
        return sPackageManager;
    }
    //获取PackageManagerService对象并返回
    IBinder b = ServiceManager.getService("package");
    sPackageManager = IPackageManager.Stub.asInterface(b);
    return sPackageManager;
}
```


### `PackageManagerService.resolveIntent()`方法
```java
@Override
public ResolveInfo resolveIntent(Intent intent, String resolvedType,
        int flags, int userId) {
    try {
        //参数合法性检查，如果用户不存在，返回null
        if (!sUserManager.exists(userId)) return null;
        flags = updateFlagsForResolve(flags, userId, intent);
        enforceCrossUserPermission(Binder.getCallingUid(), userId,
                false /*requireFullPermission*/, false /*checkShell*/, "resolve intent");

        //调用queryIntentActivitiesInternal()方法
        final List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType,
                flags, userId);

        //调用chooseBestActivity()方法，获取一个最匹配的
        final ResolveInfo bestChoice =
                chooseBestActivity(intent, resolvedType, flags, query, userId);

        if (isEphemeralAllowed(intent, query, userId)) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "resolveEphemeral");
            final EphemeralResolveInfo ai =
                    getEphemeralResolveInfo(intent, resolvedType, userId);
            if (ai != null) {
                if (DEBUG_EPHEMERAL) {
                    Slog.v(TAG, "Returning an EphemeralResolveInfo");
                }
                bestChoice.ephemeralInstaller = mEphemeralInstallerInfo;
                bestChoice.ephemeralResolveInfo = ai;
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
        return bestChoice;
    } finally {
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }
}
```


### `PackageManagerService.queryIntentActivitiesInternal()`方法
1. 检查用户权限
2. 获取组件名称
    1. 如果组件名称存在，调用`getActivityInfo()`方法直接查找符合的信息
    2. 如果组件名称等不存在，则需要在系统所有的包中查找

```java
private @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
        String resolvedType, int flags, int userId) {
    //1. 检查调用接口的用户的权限
    if (!sUserManager.exists(userId)) return Collections.emptyList();
    flags = updateFlagsForResolve(flags, userId, intent);
    enforceCrossUserPermission(Binder.getCallingUid(), userId,
            false /* requireFullPermission */, false /* checkShell */,
            "query intent activities");
    //2. 通过Intent获取组件名称
    ComponentName comp = intent.getComponent();
    if (comp == null) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
            comp = intent.getComponent();
        }
    }

    //2.1 如果Intent中已经指定了模块和组件的名称，则只会有一个匹配项
    if (comp != null) {
        final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
        //调用getActivityInfo()方法，通过模块信息得到ActivityInfo
        final ActivityInfo ai = getActivityInfo(comp, flags, userId);
        if (ai != null) {
            //转换成ResolveInfo对象
            final ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = ai;
            list.add(ri);
        }
        return list;
    }

    //2.2 如果Intent中没有指定组件名称等信息，则需要在系统所有的包中查找
    synchronized (mPackages) {
        final String pkgName = intent.getPackage();
        if (pkgName == null) {
            //调用getMatchingCrossProfileIntentFilters()方法，获取所有匹配的Intent filterIfNotSystemUser
            List<CrossProfileIntentFilter> matchingFilters =
                    getMatchingCrossProfileIntentFilters(intent, resolvedType, userId);
            //检查带有SKIP_CURRENT_PROFILE标志的filter，优先匹配带有SKIP_CURRENT_PROFILE标志的filter
            ResolveInfo xpResolveInfo  = querySkipCurrentProfileIntents(matchingFilters, intent,
                    resolvedType, flags, userId);
            if (xpResolveInfo != null) {
                List<ResolveInfo> result = new ArrayList<ResolveInfo>(1);
                result.add(xpResolveInfo);
                return filterIfNotSystemUser(result, userId);
            }

            //调用在当前的profile下查找
            List<ResolveInfo> result = mActivities.queryIntent(
                    intent, resolvedType, flags, userId);
            result = filterIfNotSystemUser(result, userId);

            // Check for cross profile results.
            boolean hasNonNegativePriorityResult = hasNonNegativePriority(result);
            xpResolveInfo = queryCrossProfileIntents(
                    matchingFilters, intent, resolvedType, flags, userId,
                    hasNonNegativePriorityResult);
            if (xpResolveInfo != null && isUserEnabled(xpResolveInfo.targetUserId)) {
                boolean isVisibleToUser = filterIfNotSystemUser(
                        Collections.singletonList(xpResolveInfo), userId).size() > 0;
                if (isVisibleToUser) {
                    result.add(xpResolveInfo);
                    Collections.sort(result, mResolvePrioritySorter);
                }
            }
            if (hasWebURI(intent)) {
                CrossProfileDomainInfo xpDomainInfo = null;
                final UserInfo parent = getProfileParent(userId);
                if (parent != null) {
                    xpDomainInfo = getCrossProfileDomainPreferredLpr(intent, resolvedType,
                            flags, userId, parent.id);
                }
                if (xpDomainInfo != null) {
                    if (xpResolveInfo != null) {
                        // If we didn't remove it, the cross-profile ResolveInfo would be twice
                        // in the result.
                        result.remove(xpResolveInfo);
                    }
                    if (result.size() == 0) {
                        result.add(xpDomainInfo.resolveInfo);
                        return result;
                    }
                } else if (result.size() <= 1) {
                    return result;
                }
                result = filterCandidatesWithDomainPreferredActivitiesLPr(intent, flags, result,
                        xpDomainInfo, userId);
                Collections.sort(result, mResolvePrioritySorter);
            }
            return result;
        }

        //如果Intent中有包名，则在指定的包名中查找
        final PackageParser.Package pkg = mPackages.get(pkgName);
        if (pkg != null) {
            return filterIfNotSystemUser(
                    mActivities.queryIntentForPackage(
                            intent, resolvedType, flags, pkg.activities, userId),
                    userId);
        }
        return new ArrayList<ResolveInfo>();
    }
}
```

#### `PackageManagerService.chooseBestActivity()`方法
```java
private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
        int flags, List<ResolveInfo> query, int userId) {
    if (query != null) {
        final int N = query.size();
        //如果ResolveInfo列表中只有一个直接返回
        if (N == 1) {
            return query.get(0);
        } else if (N > 1) {
            final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);
            // 如果有相同优先级的多个Activity,让用户决定
            ResolveInfo r0 = query.get(0);
            ResolveInfo r1 = query.get(1);

            // 如果第一个Activity与其他的有不同的优先级或者Default，返回第一个ResolverInfo
            if (r0.priority != r1.priority
                    || r0.preferredOrder != r1.preferredOrder
                    || r0.isDefault != r1.isDefault) {
                return query.get(0);
            }
            // 如果保存了该Intent的首选项的ResolveInfo，返回该ResolveInfo
            ResolveInfo ri = findPreferredActivity(intent, resolvedType,
                    flags, query, r0.priority, true, false, debug, userId);
            if (ri != null) {
                return ri;
            }

            //利用当前的mResolveInfo对象新建一个
            ri = new ResolveInfo(mResolveInfo);
            ri.activityInfo = new ActivityInfo(ri.activityInfo);
            ri.activityInfo.labelRes = ResolverActivity.getLabelRes(intent.getAction());
            final String intentPackage = intent.getPackage();
            if (!TextUtils.isEmpty(intentPackage) && allHavePackage(query, intentPackage)) {
                final ApplicationInfo appi = query.get(0).activityInfo.applicationInfo;
                ri.resolvePackageName = intentPackage;
                if (userNeedsBadging(userId)) {
                    ri.noResourceId = true;
                } else {
                    ri.icon = appi.icon;
                }
                ri.iconResourceId = appi.icon;
                ri.labelRes = appi.labelRes;
            }
            ri.activityInfo.applicationInfo = new ApplicationInfo(
                    ri.activityInfo.applicationInfo);
            if (userId != 0) {
                ri.activityInfo.applicationInfo.uid = UserHandle.getUid(userId,
                        UserHandle.getAppId(ri.activityInfo.applicationInfo.uid));
            }
            // Make sure that the resolver is displayable in car mode
            if (ri.activityInfo.metaData == null) ri.activityInfo.metaData = new Bundle();
            ri.activityInfo.metaData.putBoolean(Intent.METADATA_DOCK_HOME, true);
            return ri;
        }
    }
    return null;
}
```


#### `PackageManagerService.findPreferredActivity()`方法
```java
ResolveInfo findPreferredActivity(Intent intent, String resolvedType, int flags,
        List<ResolveInfo> query, int priority, boolean always,
        boolean removeMatches, boolean debug, int userId) {
    if (!sUserManager.exists(userId)) return null;
    flags = updateFlagsForResolve(flags, userId, intent);
    // writer
    synchronized (mPackages) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
        }
        if (DEBUG_PREFERRED) intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);

        //调用findPersistentPreferredActivityLP()方法，查找匹配的持久的优先Activity
        ResolveInfo pri = findPersistentPreferredActivityLP(intent, resolvedType, flags, query,
                debug, userId);

        // If a persistent preferred activity matched, use it.
        if (pri != null) {
            return pri;
        }

        PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
        // Get the list of preferred activities that handle the intent
        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for preferred activities...");
        List<PreferredActivity> prefs = pir != null
                ? pir.queryIntent(intent, resolvedType,
                        (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId)
                : null;
        if (prefs != null && prefs.size() > 0) {
            boolean changed = false;
            try {
                // First figure out how good the original match set is.
                // We will only allow preferred activities that came
                // from the same match quality.
                int match = 0;

                if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Figuring out best match...");

                final int N = query.size();
                for (int j=0; j<N; j++) {
                    final ResolveInfo ri = query.get(j);
                    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Match for " + ri.activityInfo
                            + ": 0x" + Integer.toHexString(match));
                    if (ri.match > match) {
                        match = ri.match;
                    }
                }

                if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Best match: 0x"
                        + Integer.toHexString(match));

                match &= IntentFilter.MATCH_CATEGORY_MASK;
                final int M = prefs.size();
                for (int i=0; i<M; i++) {
                    final PreferredActivity pa = prefs.get(i);
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Checking PreferredActivity ds="
                                + (pa.countDataSchemes() > 0 ? pa.getDataScheme(0) : "<none>")
                                + "\n  component=" + pa.mPref.mComponent);
                        pa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                    }
                    if (pa.mPref.mMatch != match) {
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping bad match "
                                + Integer.toHexString(pa.mPref.mMatch));
                        continue;
                    }
                    // If it's not an "always" type preferred activity and that's what we're
                    // looking for, skip it.
                    if (always && !pa.mPref.mAlways) {
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping mAlways=false entry");
                        continue;
                    }
                    final ActivityInfo ai = getActivityInfo(
                            pa.mPref.mComponent, flags | MATCH_DISABLED_COMPONENTS
                                    | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                            userId);
                    if (ai == null) {
                        // This previously registered preferred activity
                        // component is no longer known.  Most likely an update
                        // to the app was installed and in the new version this
                        // component no longer exists.  Clean it up by removing
                        // it from the preferred activities list, and skip it.
                        pir.removeFilter(pa);
                        changed = true;
                        continue;
                    }
                    for (int j=0; j<N; j++) {
                        final ResolveInfo ri = query.get(j);
                        if (!ri.activityInfo.applicationInfo.packageName
                                .equals(ai.applicationInfo.packageName)) {
                            continue;
                        }
                        if (!ri.activityInfo.name.equals(ai.name)) {
                            continue;
                        }

                        if (removeMatches) {
                            pir.removeFilter(pa);
                            changed = true;
                            break;
                        }

                        // Okay we found a previously set preferred or last chosen app.
                        // If the result set is different from when this
                        // was created, we need to clear it and re-ask the
                        // user their preference, if we're looking for an "always" type entry.
                        if (always && !pa.mPref.sameSet(query)) {
                            pir.removeFilter(pa);
                            // Re-add the filter as a "last chosen" entry (!always)
                            PreferredActivity lastChosen = new PreferredActivity(
                                    pa, pa.mPref.mMatch, null, pa.mPref.mComponent, false);
                            pir.addFilter(lastChosen);
                            changed = true;
                            return null;
                        }
                        return ri;
                    }
                }
            } finally {
                if (changed) {
                    scheduleWritePackageRestrictionsLocked(userId);
                }
            }
        }
    }
    return null;
}
```


#### `PackageManagerService.findPersistentPreferredActivityLP()`方法
```java
private ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType,
        int flags, List<ResolveInfo> query, boolean debug, int userId) {
    final int N = query.size();
    PersistentPreferredIntentResolver ppir = mSettings.mPersistentPreferredActivities
            .get(userId);
    // Get the list of persistent preferred activities that handle the intent
    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for presistent preferred activities...");
    List<PersistentPreferredActivity> pprefs = ppir != null
            ? ppir.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId)
            : null;
    if (pprefs != null && pprefs.size() > 0) {
        final int M = pprefs.size();
        for (int i=0; i<M; i++) {
            final PersistentPreferredActivity ppa = pprefs.get(i);
            if (DEBUG_PREFERRED || debug) {
                Slog.v(TAG, "Checking PersistentPreferredActivity ds="
                        + (ppa.countDataSchemes() > 0 ? ppa.getDataScheme(0) : "<none>")
                        + "\n  component=" + ppa.mComponent);
                ppa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
            }
            final ActivityInfo ai = getActivityInfo(ppa.mComponent,
                    flags | MATCH_DISABLED_COMPONENTS, userId);
            if (DEBUG_PREFERRED || debug) {
                Slog.v(TAG, "Found persistent preferred activity:");
                if (ai != null) {
                    ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                } else {
                    Slog.v(TAG, "  null");
                }
            }
            if (ai == null) {
                // This previously registered persistent preferred activity
                // component is no longer known. Ignore it and do NOT remove it.
                continue;
            }
            for (int j=0; j<N; j++) {
                final ResolveInfo ri = query.get(j);
                if (!ri.activityInfo.applicationInfo.packageName
                        .equals(ai.applicationInfo.packageName)) {
                    continue;
                }
                if (!ri.activityInfo.name.equals(ai.name)) {
                    continue;
                }
                //  Found a persistent preference that can handle the intent.
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Returning persistent preferred activity: " +
                            ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                }
                return ri;
            }
        }
    }
    return null;
}
```

#### `PackageManagerService.querySkipCurrentProfileIntents()`方法
```java
private ResolveInfo querySkipCurrentProfileIntents(
        List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
        int flags, int sourceUserId) {
    if (matchingFilters != null) {
        int size = matchingFilters.size();
        for (int i = 0; i < size; i ++) {
            CrossProfileIntentFilter filter = matchingFilters.get(i);
            if ((filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) != 0) {
                // Checking if there are activities in the target user that can handle the
                // intent.
                ResolveInfo resolveInfo = createForwardingResolveInfo(filter, intent,
                        resolvedType, flags, sourceUserId);
                if (resolveInfo != null) {
                    return resolveInfo;
                }
            }
        }
    }
    return null;
}
```

#### `PackageManagerService.getActivityInfo()`方法
```java
@Override
public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
    if (!sUserManager.exists(userId)) return null;
    flags = updateFlagsForComponent(flags, userId, component);
    enforceCrossUserPermission(Binder.getCallingUid(), userId,
            false /* requireFullPermission */, false /* checkShell */, "get activity info");
    synchronized (mPackages) {
        //mActivities表示ActivityIntentResolver对象，第二个mActivities是一个ArrayMap
        //ActivityIntentResolver是PMS的内部类
        PackageParser.Activity a = mActivities.mActivities.get(component);

        if (a != null && mSettings.isEnabledAndMatchLPr(a.info, flags, userId)) {
            PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
            if (ps == null) return null;
            return PackageParser.generateActivityInfo(a, flags, ps.readUserState(userId),
                    userId);
        }
        if (mResolveComponentName.equals(component)) {
            return PackageParser.generateActivityInfo(mResolveActivity, flags,
                    new PackageUserState(), userId);
        }
    }
    return null;
}
```

#### `PackageManagerService.getMatchingCrossProfileIntentFilters()`方法
```java
private List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
        String resolvedType, int userId) {
    // 通过pm包中的Settings获取CrossProfileIntentResolver对象
    CrossProfileIntentResolver resolver = mSettings.mCrossProfileIntentResolvers.get(userId);
    if (resolver != null) {
        //CrossProfileIntentResolver继承IntentResolver，调用IntentResolver.queryIntent()方法
        return resolver.queryIntent(intent, resolvedType, false, userId);
    }
    return null;
}
```

#### `IntentResolver.queryIntent()`方法
```java
public List<R> queryIntent(Intent intent, String resolvedType, boolean defaultOnly,
        int userId) {
    String scheme = intent.getScheme();

    ArrayList<R> finalList = new ArrayList<R>();

    final boolean debug = localLOGV ||
            ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

    F[] firstTypeCut = null;
    F[] secondTypeCut = null;
    F[] thirdTypeCut = null;
    F[] schemeCut = null;

    // If the intent includes a MIME type, then we want to collect all of
    // the filters that match that MIME type.
    if (resolvedType != null) {
        int slashpos = resolvedType.indexOf('/');
        if (slashpos > 0) {
            final String baseType = resolvedType.substring(0, slashpos);
            if (!baseType.equals("*")) {
                if (resolvedType.length() != slashpos+2
                        || resolvedType.charAt(slashpos+1) != '*') {
                    // Not a wild card, so we can just look for all filters that
                    // completely match or wildcards whose base type matches.
                    firstTypeCut = mTypeToFilter.get(resolvedType);
                    if (debug) Slog.v(TAG, "First type cut: " + Arrays.toString(firstTypeCut));
                    secondTypeCut = mWildTypeToFilter.get(baseType);
                    if (debug) Slog.v(TAG, "Second type cut: "
                            + Arrays.toString(secondTypeCut));
                } else {
                    // We can match anything with our base type.
                    firstTypeCut = mBaseTypeToFilter.get(baseType);
                    if (debug) Slog.v(TAG, "First type cut: " + Arrays.toString(firstTypeCut));
                    secondTypeCut = mWildTypeToFilter.get(baseType);
                    if (debug) Slog.v(TAG, "Second type cut: "
                            + Arrays.toString(secondTypeCut));
                }
                // Any */* types always apply, but we only need to do this
                // if the intent type was not already */*.
                thirdTypeCut = mWildTypeToFilter.get("*");
                if (debug) Slog.v(TAG, "Third type cut: " + Arrays.toString(thirdTypeCut));
            } else if (intent.getAction() != null) {
                // The intent specified any type ({@literal *}/*).  This
                // can be a whole heck of a lot of things, so as a first
                // cut let's use the action instead.
                firstTypeCut = mTypedActionToFilter.get(intent.getAction());
                if (debug) Slog.v(TAG, "Typed Action list: " + Arrays.toString(firstTypeCut));
            }
        }
    }

    // If the intent includes a data URI, then we want to collect all of
    // the filters that match its scheme (we will further refine matches
    // on the authority and path by directly matching each resulting filter).
    if (scheme != null) {
        schemeCut = mSchemeToFilter.get(scheme);
        if (debug) Slog.v(TAG, "Scheme list: " + Arrays.toString(schemeCut));
    }

    // If the intent does not specify any data -- either a MIME type or
    // a URI -- then we will only be looking for matches against empty
    // data.
    if (resolvedType == null && scheme == null && intent.getAction() != null) {
        firstTypeCut = mActionToFilter.get(intent.getAction());
        if (debug) Slog.v(TAG, "Action list: " + Arrays.toString(firstTypeCut));
    }

    FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
    if (firstTypeCut != null) {
        buildResolveList(intent, categories, debug, defaultOnly,
                resolvedType, scheme, firstTypeCut, finalList, userId);
    }
    if (secondTypeCut != null) {
        buildResolveList(intent, categories, debug, defaultOnly,
                resolvedType, scheme, secondTypeCut, finalList, userId);
    }
    if (thirdTypeCut != null) {
        buildResolveList(intent, categories, debug, defaultOnly,
                resolvedType, scheme, thirdTypeCut, finalList, userId);
    }
    if (schemeCut != null) {
        buildResolveList(intent, categories, debug, defaultOnly,
                resolvedType, scheme, schemeCut, finalList, userId);
    }
    sortResults(finalList);

    return finalList;
}
```

### `ActivityStackSupervisor.resolveActivity()`方法
```java
ActivityInfo resolveActivity(Intent intent, ResolveInfo rInfo, int startFlags,
        ProfilerInfo profilerInfo) {
    final ActivityInfo aInfo = rInfo != null ? rInfo.activityInfo : null;
    if (aInfo != null) {
        // Store the found target back into the intent, because now that
        // we have it we never want to do this again.  For example, if the
        // user navigates back to this point in the history, we should
        // always restart the exact same activity.
        intent.setComponent(new ComponentName(
                aInfo.applicationInfo.packageName, aInfo.name));

        // Don't debug things in the system process
        if (!aInfo.processName.equals("system")) {
            if ((startFlags & ActivityManager.START_FLAG_DEBUG) != 0) {
                mService.setDebugApp(aInfo.processName, true, false);
            }

            if ((startFlags & ActivityManager.START_FLAG_NATIVE_DEBUGGING) != 0) {
                mService.setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
            }

            if ((startFlags & ActivityManager.START_FLAG_TRACK_ALLOCATION) != 0) {
                mService.setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
            }

            if (profilerInfo != null) {
                mService.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
            }
        }
    }
    return aInfo;
}
```


### `ActivityStarter.startActivityLocked()`方法
```java
private final ActivityManagerService mService;

final int startActivityLocked(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
        String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
        IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
        IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
        String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
        ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
        ActivityRecord[] outActivity, ActivityStackSupervisor.ActivityContainer container,
        TaskRecord inTask) {
    int err = ActivityManager.START_SUCCESS;

    ProcessRecord callerApp = null;
    if (caller != null) {
        callerApp = mService.getRecordForAppLocked(caller);
        if (callerApp != null) {
            callingPid = callerApp.pid;
            callingUid = callerApp.info.uid;
        } else {
            err = ActivityManager.START_PERMISSION_DENIED;
        }
    }

    final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;
    //.......

    ActivityRecord sourceRecord = null;
    ActivityRecord resultRecord = null;
    if (resultTo != null) {
        sourceRecord = mSupervisor.isInAnyStackLocked(resultTo);
        if (sourceRecord != null) {
            if (requestCode >= 0 && !sourceRecord.finishing) {
                resultRecord = sourceRecord;
            }
        }
    }

    final int launchFlags = intent.getFlags();

    if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
        // Transfer the result target from the source activity to the new
        // one being started, including any failures.
        if (requestCode >= 0) {
            ActivityOptions.abort(options);
            return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
        }
        resultRecord = sourceRecord.resultTo;
        if (resultRecord != null && !resultRecord.isInStackLocked()) {
            resultRecord = null;
        }
        resultWho = sourceRecord.resultWho;
        requestCode = sourceRecord.requestCode;
        sourceRecord.resultTo = null;
        if (resultRecord != null) {
            resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
        }
        if (sourceRecord.launchedFromUid == callingUid) {
            // The new activity is being launched from the same uid as the previous
            // activity in the flow, and asking to forward its result back to the
            // previous.  In this case the activity is serving as a trampoline between
            // the two, so we also want to update its launchedFromPackage to be the
            // same as the previous activity.  Note that this is safe, since we know
            // these two packages come from the same uid; the caller could just as
            // well have supplied that same package name itself.  This specifially
            // deals with the case of an intent picker/chooser being launched in the app
            // flow to redirect to an activity picked by the user, where we want the final
            // activity to consider it to have been launched by the previous app activity.
            callingPackage = sourceRecord.launchedFromPackage;
        }
    }

    if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
        // We couldn't find a class that can handle the given Intent.
        // That's the end of that!
        err = ActivityManager.START_INTENT_NOT_RESOLVED;
    }

    if (err == ActivityManager.START_SUCCESS && aInfo == null) {
        // We couldn't find the specific class specified in the Intent.
        // Also the end of the line.
        err = ActivityManager.START_CLASS_NOT_FOUND;
    }

    if (err == ActivityManager.START_SUCCESS && sourceRecord != null
            && sourceRecord.task.voiceSession != null) {
        // If this activity is being launched as part of a voice session, we need
        // to ensure that it is safe to do so.  If the upcoming activity will also
        // be part of the voice session, we can only launch it if it has explicitly
        // said it supports the VOICE category, or it is a part of the calling app.
        if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0
                && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
            try {
                intent.addCategory(Intent.CATEGORY_VOICE);
                if (!AppGlobals.getPackageManager().activitySupportsIntent(
                        intent.getComponent(), intent, resolvedType)) {
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }
    }

    if (err == ActivityManager.START_SUCCESS && voiceSession != null) {
        // If the caller is starting a new voice session, just make sure the target
        // is actually allowing it to run this way.
        try {
            if (!AppGlobals.getPackageManager().activitySupportsIntent(intent.getComponent(),
                    intent, resolvedType)) {
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        } catch (RemoteException e) {
            err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
        }
    }

    final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

    if (err != START_SUCCESS) {
        if (resultRecord != null) {
            resultStack.sendActivityResultLocked(
                    -1, resultRecord, resultWho, requestCode, RESULT_CANCELED, null);
        }
        ActivityOptions.abort(options);
        return err;
    }

    boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
            requestCode, callingPid, callingUid, callingPackage, ignoreTargetSecurity, callerApp,
            resultRecord, resultStack, options);
    abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
            callingPid, resolvedType, aInfo.applicationInfo);

    if (mService.mController != null) {
        try {
            // The Intent we give to the watcher has the extra data
            // stripped off, since it can contain private information.
            Intent watchIntent = intent.cloneFilter();
            abort |= !mService.mController.activityStarting(watchIntent,
                    aInfo.applicationInfo.packageName);
        } catch (RemoteException e) {
            mService.mController = null;
        }
    }

    mInterceptor.setStates(userId, realCallingPid, realCallingUid, startFlags, callingPackage);
    mInterceptor.intercept(intent, rInfo, aInfo, resolvedType, inTask, callingPid, callingUid,
            options);
    intent = mInterceptor.mIntent;
    rInfo = mInterceptor.mRInfo;
    aInfo = mInterceptor.mAInfo;
    resolvedType = mInterceptor.mResolvedType;
    inTask = mInterceptor.mInTask;
    callingPid = mInterceptor.mCallingPid;
    callingUid = mInterceptor.mCallingUid;
    options = mInterceptor.mActivityOptions;
    if (abort) {
        if (resultRecord != null) {
            resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                    RESULT_CANCELED, null);
        }
        // We pretend to the caller that it was really started, but
        // they will just get a cancel result.
        ActivityOptions.abort(options);
        return START_SUCCESS;
    }

    // If permissions need a review before any of the app components can run, we
    // launch the review activity and pass a pending intent to start the activity
    // we are to launching now after the review is completed.
    if ((mService.mPermissionReviewRequired
            || Build.PERMISSIONS_REVIEW_REQUIRED) && aInfo != null) {
        if (mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                aInfo.packageName, userId)) {
            IIntentSender target = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                    callingUid, userId, null, null, 0, new Intent[]{intent},
                    new String[]{resolvedType}, PendingIntent.FLAG_CANCEL_CURRENT
                            | PendingIntent.FLAG_ONE_SHOT, null);

            final int flags = intent.getFlags();
            Intent newIntent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            newIntent.setFlags(flags
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            newIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, aInfo.packageName);
            newIntent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
            if (resultRecord != null) {
                newIntent.putExtra(Intent.EXTRA_RESULT_NEEDED, true);
            }
            intent = newIntent;

            resolvedType = null;
            callingUid = realCallingUid;
            callingPid = realCallingPid;

            rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
            aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags,
                    null /*profilerInfo*/);
            //......
        }
    }

    // If we have an ephemeral app, abort the process of launching the resolved intent.
    // Instead, launch the ephemeral installer. Once the installer is finished, it
    // starts either the intent we resolved here [on install error] or the ephemeral
    // app [on install success].
    if (rInfo != null && rInfo.ephemeralResolveInfo != null) {
        // Create a pending intent to start the intent resolved here.
        final IIntentSender failureTarget = mService.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ intent },
                new String[]{ resolvedType },
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null);

        // Create a pending intent to start the ephemeral application; force it to be
        // directed to the ephemeral package.
        ephemeralIntent.setPackage(rInfo.ephemeralResolveInfo.getPackageName());
        final IIntentSender ephemeralTarget = mService.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ ephemeralIntent },
                new String[]{ resolvedType },
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null);

        int flags = intent.getFlags();
        intent = new Intent();
        intent.setFlags(flags
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                rInfo.ephemeralResolveInfo.getPackageName());
        intent.putExtra(Intent.EXTRA_EPHEMERAL_FAILURE, new IntentSender(failureTarget));
        intent.putExtra(Intent.EXTRA_EPHEMERAL_SUCCESS, new IntentSender(ephemeralTarget));

        resolvedType = null;
        callingUid = realCallingUid;
        callingPid = realCallingPid;

        rInfo = rInfo.ephemeralInstaller;
        aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, null /*profilerInfo*/);
    }

    ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
            intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
            requestCode, componentSpecified, voiceSession != null, mSupervisor, container,
            options, sourceRecord);
    if (outActivity != null) {
        outActivity[0] = r;
    }

    if (r.appTimeTracker == null && sourceRecord != null) {
        // If the caller didn't specify an explicit time tracker, we want to continue
        // tracking under any it has.
        r.appTimeTracker = sourceRecord.appTimeTracker;
    }

    final ActivityStack stack = mSupervisor.mFocusedStack;
    if (voiceSession == null && (stack.mResumedActivity == null
            || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
        if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                realCallingPid, realCallingUid, "Activity start")) {
            PendingActivityLaunch pal =  new PendingActivityLaunch(r,
                    sourceRecord, startFlags, stack, callerApp);
            mPendingActivityLaunches.add(pal);
            ActivityOptions.abort(options);
            return ActivityManager.START_SWITCHES_CANCELED;
        }
    }

    if (mService.mDidAppSwitch) {
        // This is the second allowed switch since we stopped switches,
        // so now just generally allow switches.  Use case: user presses
        // home (switches disabled, switch to home, mDidAppSwitch now true);
        // user taps a home icon (coming from home so allowed, we hit here
        // and now allow anyone to switch again).
        mService.mAppSwitchesAllowedTime = 0;
    } else {
        mService.mDidAppSwitch = true;
    }

    doPendingActivityLaunchesLocked(false);

    try {
        mService.mWindowManager.deferSurfaceLayout();
        err = startActivityUnchecked(r, sourceRecord, voiceSession, voiceInteractor, startFlags,
                true, options, inTask);
    } finally {
        mService.mWindowManager.continueSurfaceLayout();
    }
    postStartActivityUncheckedProcessing(r, err, stack.mStackId, mSourceRecord, mTargetStack);
    return err;
}
```
