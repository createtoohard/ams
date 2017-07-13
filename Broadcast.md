# BroadcastReceiver
1. BroadcastReceiver可以分为两类
* 静态接收者：指在AndroidManifest.xml中通过标签`<receiver></receiver>`指定的接收者
* 动态接收者：指通过AMS的`registerReceiver()`方法注册的接收者
    * 动态注册的好处是使用灵活，在不需要接收广播的使用可以通过`unregisterReceiver()`方法取消注册


## Broadcast发送过程
* 应用发送广播调用的是Context类中的方法

## Broadcast发送过程流程图
```puml

```

### `ContextImpl.sendBroadcast()`方法
* ContextImpl中有很多发送广播的方法，但最终都是调用AMS中的broadcastIntent()方法

```java
@Override
public void sendBroadcast(Intent intent) {
    warnIfCallingFromSystemProcess();
    String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
    try {
        intent.prepareToLeaveProcess(this);
        //调用ActivityManagerService.broadcastIntent()方法
        ActivityManagerNative.getDefault().broadcastIntent(
                mMainThread.getApplicationThread(), intent, resolvedType, null,
                Activity.RESULT_OK, null, null, null, AppOpsManager.OP_NONE, null, false, false,
                getUserId());
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

### `ActivityManagerService.broadcastIntent()`方法
```java
public final int broadcastIntent(IApplicationThread caller,
        Intent intent, String resolvedType, IIntentReceiver resultTo,
        int resultCode, String resultData, Bundle resultExtras,
        String[] requiredPermissions, int appOp, Bundle bOptions,
        boolean serialized, boolean sticky, int userId) {
    enforceNotIsolatedCaller("broadcastIntent");
    synchronized(this) {
        intent = verifyBroadcastLocked(intent);

        final ProcessRecord callerApp = getRecordForAppLocked(caller);
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        //调用AMS的broadcastIntentLocked()方法
        int res = broadcastIntentLocked(callerApp,
                callerApp != null ? callerApp.info.packageName : null,
                intent, resolvedType, resultTo, resultCode, resultData, resultExtras,
                requiredPermissions, appOp, bOptions, serialized, sticky,
                callingPid, callingUid, userId);
        Binder.restoreCallingIdentity(origId);
        return res;
    }
}
```

#### `ActivityManagerService.verifyBroadcastLocked()`方法
一些特殊情况下的特殊flag的广播检查
```java
final Intent verifyBroadcastLocked(Intent intent) {
    // intent中不能包含文件描述符
    if (intent != null && intent.hasFileDescriptors() == true) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }

    int flags = intent.getFlags();

    if (!mProcessesReady) {
        //如果调用者真的知道在做什么，允许广播在没有启动任何接收器之前
        if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT) != 0) {
            // 如果没有FLAG_RECEIVER_REGISTERED_ONLY该标签抛出异常
        } else if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            throw new IllegalStateException("Cannot broadcast before boot completed");
        }
    }

    if ((flags&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
        throw new IllegalArgumentException(
                "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
    }
    return intent;
}
```

### `ActivityManagerService.broadcastIntentLocked()`方法
1. 检查广播的Intent的一些特殊的flag
2. 如果是一些系统广播，则调用相应的方法处理
3. 如果是粘性广播，把广播的Intent加入到AMS的粘性广播列表中
4. 查找所有接收者，逐个调用他们
```java
final UserController mUserController;

final int broadcastIntentLocked(ProcessRecord callerApp,
        String callerPackage, Intent intent, String resolvedType,
        IIntentReceiver resultTo, int resultCode, String resultData,
        Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions,
        boolean ordered, boolean sticky, int callingPid, int callingUid, int userId) {
    //1. 检查广播Intent的一些特殊的flag

    //备份一个Intent
    intent = new Intent(intent);
    //默认不包含已经停止的应用（即应用处于stop状态，该广播不发送给它）
    intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
    //如果启动还没有完成，不允许它启动新的进程
    if (!mProcessesReady && (intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    }

    userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
            ALLOW_NON_FULL, "broadcast", callerPackage);

    //检查接收广播的用户是否活动的，如果不是则过滤该广播，但是光机广播和升级广播例外
    if (userId != UserHandle.USER_ALL && !mUserController.isUserRunningLocked(userId, 0)) {
        if ((callingUid != Process.SYSTEM_UID
                || (intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0)
                && !Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
            return ActivityManager.BROADCAST_FAILED_USER_STOPPED;
        }
    }

    BroadcastOptions brOptions = null;
    if (bOptions != null) {
        brOptions = new BroadcastOptions(bOptions);
        if (brOptions.getTemporaryAppWhitelistDuration() > 0) {
            if (checkComponentPermission(
                    android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                    Binder.getCallingPid(), Binder.getCallingUid(), -1, true)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(msg);
            }
        }
    }

    //2. 验证受保护的广播只能有系统发送，系统只发送一些受保护的广播
    //通过调用PackageManagerService.isProtectedBroadcast()方法判断action，该广播是不是受保护的广播
    final String action = intent.getAction();
    final boolean isProtectedBroadcast;
    try {
        isProtectedBroadcast = AppGlobals.getPackageManager().isProtectedBroadcast(action);
    } catch (RemoteException e) {
        return ActivityManager.BROADCAST_SUCCESS;
    }

    //判断调用者是不是系统
    final boolean isCallerSystem;
    switch (UserHandle.getAppId(callingUid)) {
        case Process.ROOT_UID:
        case Process.SYSTEM_UID:
        case Process.PHONE_UID:
        case Process.BLUETOOTH_UID:
        case Process.NFC_UID:
            isCallerSystem = true;
            break;
        default:
            isCallerSystem = (callerApp != null) && callerApp.persistent;
            break;
    }

    if (isCallerSystem) {
        if (isProtectedBroadcast
                || Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                || Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS.equals(action)
                || Intent.ACTION_MEDIA_BUTTON.equals(action)
                || Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action)
                || Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION.equals(action)
                || TelephonyIntents.ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE.equals(action)
                || SuggestionSpan.ACTION_SUGGESTION_PICKED.equals(action)) {
            // Broadcast is either protected, or it's a public action that
            // we've relaxed, so it's fine for system internals to send.
        } else {
            // The vast majority of broadcasts sent from system internals
            // should be protected to avoid security holes, so yell loudly
            // to ensure we examine these cases.
            if (callerApp != null) {
                Log.wtf(TAG, "Sending non-protected broadcast " + action
                        + " from system " + callerApp.toShortString() + " pkg " + callerPackage,
                        new Throwable());
            } else {
                Log.wtf(TAG, "Sending non-protected broadcast " + action
                        + " from system uid " + UserHandle.formatUid(callingUid)
                        + " pkg " + callerPackage,
                        new Throwable());
            }
        }

    } else {
        if (isProtectedBroadcast) {
            String msg = "Permission Denial: not allowed to send broadcast "
                    + action + " from pid="
                    + callingPid + ", uid=" + callingUid;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);

        } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            // Special case for compatibility: we don't want apps to send this,
            // but historically it has not been protected and apps may be using it
            // to poke their own app widget.  So, instead of making it protected,
            // just limit it to the caller.
            if (callerPackage == null) {
                String msg = "Permission Denial: not allowed to send broadcast "
                        + action + " from unknown caller.";
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            } else if (intent.getComponent() != null) {
                // They are good enough to send to an explicit component...  verify
                // it is being sent to the calling app.
                if (!intent.getComponent().getPackageName().equals(
                        callerPackage)) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + action + " to "
                            + intent.getComponent().getPackageName() + " from "
                            + callerPackage;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } else {
                // Limit broadcast to their own package.
                intent.setPackage(callerPackage);
            }
        }
    }

    if (action != null) {
        switch (action) {
            case Intent.ACTION_UID_REMOVED:
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_PACKAGE_CHANGED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
            case Intent.ACTION_PACKAGES_SUSPENDED:
            case Intent.ACTION_PACKAGES_UNSUSPENDED:
                // Handle special intents: if this broadcast is from the package
                // manager about a package being removed, we need to remove all of
                // its activities from the history stack.
                if (checkComponentPermission(
                        android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                        callingPid, callingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
                switch (action) {
                    case Intent.ACTION_UID_REMOVED:
                        final Bundle intentExtras = intent.getExtras();
                        final int uid = intentExtras != null
                                ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
                        if (uid >= 0) {
                            mBatteryStatsService.removeUid(uid);
                            mAppOpsService.uidRemoved(uid);
                        }
                        break;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                        // If resources are unavailable just force stop all those packages
                        // and flush the attribute cache as well.
                        String list[] =
                                intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        if (list != null && list.length > 0) {
                            for (int i = 0; i < list.length; i++) {
                                forceStopPackageLocked(list[i], -1, false, true, true,
                                        false, false, userId, "storage unmount");
                            }
                            mRecentTasks.cleanupLocked(UserHandle.USER_ALL);
                            sendPackageBroadcastLocked(
                                    IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE, list,
                                    userId);
                        }
                        break;
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                        mRecentTasks.cleanupLocked(UserHandle.USER_ALL);
                        break;
                    case Intent.ACTION_PACKAGE_REMOVED:
                    case Intent.ACTION_PACKAGE_CHANGED:
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                            boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(action);
                            final boolean replacing =
                                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                            final boolean killProcess =
                                    !intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false);
                            final boolean fullUninstall = removed && !replacing;
                            if (removed) {
                                if (killProcess) {
                                    forceStopPackageLocked(ssp, UserHandle.getAppId(
                                            intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                            false, true, true, false, fullUninstall, userId,
                                            removed ? "pkg removed" : "pkg changed");
                                }
                                final int cmd = killProcess
                                        ? IApplicationThread.PACKAGE_REMOVED
                                        : IApplicationThread.PACKAGE_REMOVED_DONT_KILL;
                                sendPackageBroadcastLocked(cmd,
                                        new String[] {ssp}, userId);
                                if (fullUninstall) {
                                    mAppOpsService.packageRemoved(
                                            intent.getIntExtra(Intent.EXTRA_UID, -1), ssp);

                                    // Remove all permissions granted from/to this package
                                    removeUriPermissionsForPackageLocked(ssp, userId, true);

                                    removeTasksByPackageNameLocked(ssp, userId);

                                    // Hide the "unsupported display" dialog if necessary.
                                    if (mUnsupportedDisplaySizeDialog != null && ssp.equals(
                                            mUnsupportedDisplaySizeDialog.getPackageName())) {
                                        mUnsupportedDisplaySizeDialog.dismiss();
                                        mUnsupportedDisplaySizeDialog = null;
                                    }
                                    mCompatModePackages.handlePackageUninstalledLocked(ssp);
                                    mBatteryStatsService.notePackageUninstalled(ssp);
                                }
                            } else {
                                if (killProcess) {
                                    killPackageProcessesLocked(ssp, UserHandle.getAppId(
                                            intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                            userId, ProcessList.INVALID_ADJ,
                                            false, true, true, false, "change " + ssp);
                                }
                                cleanupDisabledPackageComponentsLocked(ssp, userId, killProcess,
                                        intent.getStringArrayExtra(
                                                Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
                            }
                        }
                        break;
                    case Intent.ACTION_PACKAGES_SUSPENDED:
                    case Intent.ACTION_PACKAGES_UNSUSPENDED:
                        final boolean suspended = Intent.ACTION_PACKAGES_SUSPENDED.equals(
                                intent.getAction());
                        final String[] packageNames = intent.getStringArrayExtra(
                                Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        final int userHandle = intent.getIntExtra(
                                Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

                        synchronized(ActivityManagerService.this) {
                            mRecentTasks.onPackagesSuspendedChanged(
                                    packageNames, suspended, userHandle);
                        }
                        break;
                }
                break;
            case Intent.ACTION_PACKAGE_REPLACED:
            {
                final Uri data = intent.getData();
                final String ssp;
                if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                    final ApplicationInfo aInfo =
                            getPackageManagerInternalLocked().getApplicationInfo(
                                    ssp,
                                    userId);
                    if (aInfo == null) {
                        return ActivityManager.BROADCAST_SUCCESS;
                    }
                    mStackSupervisor.updateActivityApplicationInfoLocked(aInfo);
                    sendPackageBroadcastLocked(IApplicationThread.PACKAGE_REPLACED,
                            new String[] {ssp}, userId);
                }
                break;
            }
            case Intent.ACTION_PACKAGE_ADDED:
            {
                // Special case for adding a package: by default turn on compatibility mode.
                Uri data = intent.getData();
                String ssp;
                if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                    final boolean replacing =
                            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    mCompatModePackages.handlePackageAddedLocked(ssp, replacing);

                    try {
                        ApplicationInfo ai = AppGlobals.getPackageManager().
                                getApplicationInfo(ssp, 0, 0);
                        mBatteryStatsService.notePackageInstalled(ssp,
                                ai != null ? ai.versionCode : 0);
                    } catch (RemoteException e) {
                    }
                }
                break;
            }
            case Intent.ACTION_PACKAGE_DATA_CLEARED:
            {
                Uri data = intent.getData();
                String ssp;
                if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                    // Hide the "unsupported display" dialog if necessary.
                    if (mUnsupportedDisplaySizeDialog != null && ssp.equals(
                            mUnsupportedDisplaySizeDialog.getPackageName())) {
                        mUnsupportedDisplaySizeDialog.dismiss();
                        mUnsupportedDisplaySizeDialog = null;
                    }
                    mCompatModePackages.handlePackageDataClearedLocked(ssp);
                }
                break;
            }
            case Intent.ACTION_TIMEZONE_CHANGED:
                // If this is the time zone changed action, queue up a message that will reset
                // the timezone of all currently running processes. This message will get
                // queued up before the broadcast happens.
                mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
                break;
            case Intent.ACTION_TIME_CHANGED:
                // If the user set the time, let all running processes know.
                final int is24Hour =
                        intent.getBooleanExtra(Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT, false) ? 1
                                : 0;
                mHandler.sendMessage(mHandler.obtainMessage(UPDATE_TIME, is24Hour, 0));
                BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                synchronized (stats) {
                    stats.noteCurrentTimeChangedLocked();
                }
                break;
            case Intent.ACTION_CLEAR_DNS_CACHE:
                mHandler.sendEmptyMessage(CLEAR_DNS_CACHE_MSG);
                break;
            case Proxy.PROXY_CHANGE_ACTION:
                ProxyInfo proxy = intent.getParcelableExtra(Proxy.EXTRA_PROXY_INFO);
                mHandler.sendMessage(mHandler.obtainMessage(UPDATE_HTTP_PROXY_MSG, proxy));
                break;
            case android.hardware.Camera.ACTION_NEW_PICTURE:
            case android.hardware.Camera.ACTION_NEW_VIDEO:
                // These broadcasts are no longer allowed by the system, since they can
                // cause significant thrashing at a crictical point (using the camera).
                // Apps should use JobScehduler to monitor for media provider changes.
                if (resultTo != null) {
                    final BroadcastQueue queue = broadcastQueueForIntent(intent);
                    try {
                        queue.performReceiveLocked(callerApp, resultTo, intent,
                                Activity.RESULT_CANCELED, null, null,
                                false, false, userId);
                    } catch (RemoteException e) {}
                }
                // Lie; we don't want to crash the app.
                return ActivityManager.BROADCAST_SUCCESS;
        }
    }

    // 添加到粘性广播列表
    if (sticky) {
        //检查权限
        if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                callingPid, callingUid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(msg);
        }
        if (requiredPermissions != null && requiredPermissions.length > 0) {
            Slog.w(TAG, "Can't broadcast sticky intent " + intent
                    + " and enforce permissions " + Arrays.toString(requiredPermissions));
            return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
        }
        if (intent.getComponent() != null) {
            throw new SecurityException(
                    "Sticky broadcasts can't target a specific component");
        }
        // We use userId directly here, since the "all" target is maintained
        // as a separate set of sticky broadcasts.
        if (userId != UserHandle.USER_ALL) {
            // But first, if this is not a broadcast to all users, then
            // make sure it doesn't conflict with an existing broadcast to
            // all users.
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(
                    UserHandle.USER_ALL);
            if (stickies != null) {
                ArrayList<Intent> list = stickies.get(intent.getAction());
                if (list != null) {
                    int N = list.size();
                    int i;
                    for (i=0; i<N; i++) {
                        if (intent.filterEquals(list.get(i))) {
                            throw new IllegalArgumentException(
                                    "Sticky broadcast " + intent + " for user "
                                    + userId + " conflicts with existing global broadcast");
                        }
                    }
                }
            }
        }
        ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
        if (stickies == null) {
            stickies = new ArrayMap<>();
            mStickyBroadcasts.put(userId, stickies);
        }
        ArrayList<Intent> list = stickies.get(intent.getAction());
        if (list == null) {
            list = new ArrayList<>();
            stickies.put(intent.getAction(), list);
        }
        final int stickiesCount = list.size();
        int i;
        for (i = 0; i < stickiesCount; i++) {
            if (intent.filterEquals(list.get(i))) {
                // This sticky already exists, replace it.
                list.set(i, new Intent(intent));
                break;
            }
        }
        if (i >= stickiesCount) {
            list.add(new Intent(intent));
        }
    }

    int[] users;
    if (userId == UserHandle.USER_ALL) {
        // Caller wants broadcast to go to all started users.
        users = mUserController.getStartedUserArrayLocked();
    } else {
        // Caller wants broadcast to go to one specific user.
        users = new int[] {userId};
    }

    // Figure out who all will receive this broadcast.
    List receivers = null;
    List<BroadcastFilter> registeredReceivers = null;
    // Need to resolve the intent to interested receivers...
    if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
             == 0) {
        receivers = collectReceiverComponents(intent, resolvedType, callingUid, users);
    }
    if (intent.getComponent() == null) {
        if (userId == UserHandle.USER_ALL && callingUid == Process.SHELL_UID) {
            // Query one target user at a time, excluding shell-restricted users
            for (int i = 0; i < users.length; i++) {
                if (mUserController.hasUserRestriction(
                        UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                    continue;
                }
                List<BroadcastFilter> registeredReceiversForUser =
                        mReceiverResolver.queryIntent(intent,
                                resolvedType, false, users[i]);
                if (registeredReceivers == null) {
                    registeredReceivers = registeredReceiversForUser;
                } else if (registeredReceiversForUser != null) {
                    registeredReceivers.addAll(registeredReceiversForUser);
                }
            }
        } else {
            registeredReceivers = mReceiverResolver.queryIntent(intent,
                    resolvedType, false, userId);
        }
    }

    final boolean replacePending =
            (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;

    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueing broadcast: " + intent.getAction()
            + " replacePending=" + replacePending);

    int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
    if (!ordered && NR > 0) {
        // If we are not serializing this broadcast, then send the
        // registered receivers separately so they don't wait for the
        // components to be launched.
        final BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType, requiredPermissions,
                appOp, brOptions, registeredReceivers, resultTo, resultCode, resultData,
                resultExtras, ordered, sticky, false, userId);
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing parallel broadcast " + r);
        final boolean replaced = replacePending && queue.replaceParallelBroadcastLocked(r);
        if (!replaced) {
            queue.enqueueParallelBroadcastLocked(r);
            queue.scheduleBroadcastsLocked();
        }
        registeredReceivers = null;
        NR = 0;
    }

    // Merge into one list.
    int ir = 0;
    if (receivers != null) {
        // A special case for PACKAGE_ADDED: do not allow the package
        // being added to see this broadcast.  This prevents them from
        // using this as a back door to get run as soon as they are
        // installed.  Maybe in the future we want to have a special install
        // broadcast or such for apps, but we'd like to deliberately make
        // this decision.
        String skipPackages[] = null;
        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String pkgName = data.getSchemeSpecificPart();
                if (pkgName != null) {
                    skipPackages = new String[] { pkgName };
                }
            }
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(intent.getAction())) {
            skipPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        }
        if (skipPackages != null && (skipPackages.length > 0)) {
            for (String skipPackage : skipPackages) {
                if (skipPackage != null) {
                    int NT = receivers.size();
                    for (int it=0; it<NT; it++) {
                        ResolveInfo curt = (ResolveInfo)receivers.get(it);
                        if (curt.activityInfo.packageName.equals(skipPackage)) {
                            receivers.remove(it);
                            it--;
                            NT--;
                        }
                    }
                }
            }
        }

        int NT = receivers != null ? receivers.size() : 0;
        int it = 0;
        ResolveInfo curt = null;
        BroadcastFilter curr = null;
        while (it < NT && ir < NR) {
            if (curt == null) {
                curt = (ResolveInfo)receivers.get(it);
            }
            if (curr == null) {
                curr = registeredReceivers.get(ir);
            }
            if (curr.getPriority() >= curt.priority) {
                // Insert this broadcast record into the final list.
                receivers.add(it, curr);
                ir++;
                curr = null;
                it++;
                NT++;
            } else {
                // Skip to the next ResolveInfo in the final list.
                it++;
                curt = null;
            }
        }
    }
    while (ir < NR) {
        if (receivers == null) {
            receivers = new ArrayList();
        }
        receivers.add(registeredReceivers.get(ir));
        ir++;
    }

    if ((receivers != null && receivers.size() > 0)
            || resultTo != null) {
        BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType,
                requiredPermissions, appOp, brOptions, receivers, resultTo, resultCode,
                resultData, resultExtras, ordered, sticky, false, userId);

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing ordered broadcast " + r
                + ": prev had " + queue.mOrderedBroadcasts.size());
        if (DEBUG_BROADCAST) Slog.i(TAG_BROADCAST,
                "Enqueueing broadcast " + r.intent.getAction());

        boolean replaced = replacePending && queue.replaceOrderedBroadcastLocked(r);
        if (!replaced) {
            queue.enqueueOrderedBroadcastLocked(r);
            queue.scheduleBroadcastsLocked();
        }
    } else {
        // There was nobody interested in the broadcast, but we still want to record
        // that it happened.
        if (intent.getComponent() == null && intent.getPackage() == null
                && (intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            // This was an implicit broadcast... let's record it for posterity.
            addBroadcastStatLocked(intent.getAction(), callerPackage, 0, 0, 0);
        }
    }

    return ActivityManager.BROADCAST_SUCCESS;
}
```


#### `PackageManagerService.isProtectedBroadcast()`方法
```java
//mProtectedBroadcasts列表表示仅对系统可见的广播的Action列表
final ArraySet<String> mProtectedBroadcasts = new ArraySet<String>();

@Override
public boolean isProtectedBroadcast(String actionName) {
    synchronized (mPackages) {
        if (mProtectedBroadcasts.contains(actionName)) {
            return true;
        } else if (actionName != null) {
            // TODO: remove these terrible hacks
            if (actionName.startsWith("android.net.netmon.lingerExpired")
                    || actionName.startsWith("com.android.server.sip.SipWakeupTimer")
                    || actionName.startsWith("com.android.internal.telephony.data-reconnect")
                    || actionName.startsWith("android.net.netmon.launchCaptivePortalApp")) {
                return true;
            }
        }
    }
    return false;
}
```
