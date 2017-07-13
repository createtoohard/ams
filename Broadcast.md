# BroadcastReceiver
1. BroadcastReceiver可以分为两类
* 静态接收者：指在AndroidManifest.xml中通过标签`<receiver></receiver>`指定的接收者
* 动态接收者：指通过AMS的`registerReceiver()`方法注册的接收者
    * 动态注册的好处是使用灵活，在不需要接收广播的使用可以通过`unregisterReceiver()`方法取消注册


# Broadcast发送过程
* 应用发送广播调用的是Context类中的方法

## Broadcast发送过程流程图
```puml
ContextImpl -> ContextImpl : sendBroadcast()
ContextImpl -> AMS : broadcastIntent()
AMS -> AMS : broadcastIntentLocked()
AMS -> BroadcastQueue : scheduleBroadcastsLocked()
BroadcastQueue -> BroadcastQueue : processNextBroadcast()
```

## 涉及到的类
#### ParceledListSlice
* 通过IPC传输大量的可扩展对象。 如有需要，可分割成多个交换。


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
2. 如果是一些系统广播，特殊广播则调用相应的方法处理
3. 如果是粘性广播，把广播的Intent加入到AMS的粘性广播列表中
4. 创建BroadcastRecord对象并加入到发送队列
5. 查找所有接收者，逐个调用他们
```java
//用户管理
final UserController mUserController;
//粘性广播数组
final SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts
//
final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver

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
    //如果调用者是system
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
            // 如果是收保护（限制）的广播
        } else {...}
    //如果不是system调用
    } else {
        //如果发送的是受保护的广播，抛出异常
        if (isProtectedBroadcast) {
            throw new SecurityException(msg);

        } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            // 特殊情况的兼容性问题，限制调用者
            if (callerPackage == null) {
                throw new SecurityException(msg);
            } else if (intent.getComponent() != null) {
                if (!intent.getComponent().getPackageName().equals(
                        callerPackage)) {
                    throw new SecurityException(msg);
                }
            } else {
                intent.setPackage(callerPackage);
            }
        }
    }

    //如果action不为null，判断action，处理一些与Package相关的等等特殊的Intent
    if (action != null) {
        switch (action) {
            case Intent.ACTION_UID_REMOVED:
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_PACKAGE_CHANGED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
            case Intent.ACTION_PACKAGES_SUSPENDED:
            case Intent.ACTION_PACKAGES_UNSUSPENDED:
            //......
            case Intent.ACTION_PACKAGE_REPLACED:
            //......
            case Intent.ACTION_PACKAGE_ADDED:
            //......
            case Intent.ACTION_PACKAGE_DATA_CLEARED:
            //......
            case Intent.ACTION_TIMEZONE_CHANGED:
            //......
            case Intent.ACTION_TIME_CHANGED:
            //......
            case Intent.ACTION_CLEAR_DNS_CACHE:
            //......
            case Proxy.PROXY_CHANGE_ACTION:
            //......
            case android.hardware.Camera.ACTION_NEW_PICTURE:
            case android.hardware.Camera.ACTION_NEW_VIDEO:
            //......
        }
    }

    // 3. 处理粘性广播，添加到粘性广播列表
    if (sticky) {
        //检查权限
        if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                callingPid, callingUid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(msg);
        }
        //发送粘性广播不能指定permission
        if (requiredPermissions != null && requiredPermissions.length > 0) {
            return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
        }
        //粘性广播必须指定组件
        if (intent.getComponent() != null) {
            throw new SecurityException(
                    "Sticky broadcasts can't target a specific component");
        }
        // 如果一个粘性广播不是发送给所有用户，先检查是否存在一个发送给所有用户的粘性广播
        if (userId != UserHandle.USER_ALL) {
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(
                    UserHandle.USER_ALL);
            if (stickies != null) {
                //检测到相同的广播就抛出异常
                //......
            }
        }
        //保存stick广播到mStickyBroadcasts列表中
        ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
        if (stickies == null) {
            stickies = new ArrayMap<>();
            mStickyBroadcasts.put(userId, stickies);
        }
        //将IntentFilter加入到stick广播的列表中
        ArrayList<Intent> list = stickies.get(intent.getAction());
        if (list == null) {
            list = new ArrayList<>();
            stickies.put(intent.getAction(), list);
        }
        final int stickiesCount = list.size();
        int i;
        for (i = 0; i < stickiesCount; i++) {
            if (intent.filterEquals(list.get(i))) {
                // 如果Intent已经存在就覆盖它
                list.set(i, new Intent(intent));
                break;
            }
        }
        if (i >= stickiesCount) {
            //加入Intent到Intent列表
            list.add(new Intent(intent));
        }
    }

    // 4.创建BroadcastRecord对象并加入到发送队列
    int[] users;
    if (userId == UserHandle.USER_ALL) {
        // 调用者希望广播给所有用户
        users = mUserController.getStartedUserArrayLocked();
    } else {
        // 调用者希望广播给某个用户
        users = new int[] {userId};
    }

    // 找出所有接收这个广播的receiver
    List receivers = null;
    List<BroadcastFilter> registeredReceivers = null;
    // 需要解决对该Intent感兴趣的receiver
    if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
             == 0) {
        //如果Intent没有只发送给动态接收者的标志，收集所有静态接收者
        receivers = collectReceiverComponents(intent, resolvedType, callingUid, users);
    }
    //如果没有指定组件，则查找所有匹配该Intent的receiver
    if (intent.getComponent() == null) {
        if (userId == UserHandle.USER_ALL && callingUid == Process.SHELL_UID) {
            // Query one target user at a time, excluding shell-restricted users
            for (int i = 0; i < users.length; i++) {
                if (mUserController.hasUserRestriction(
                        UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                    continue;
                }
                //调用IntentResolver.queryIntent()方法，查找所有匹配该Intent的Receiver
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

    int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
    if (!ordered && NR > 0) {
        // 如果是普通广播，先发送动态注册的接收者
        final BroadcastQueue queue = broadcastQueueForIntent(intent);
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType, requiredPermissions,
                appOp, brOptions, registeredReceivers, resultTo, resultCode, resultData,
                resultExtras, ordered, sticky, false, userId);
        final boolean replaced = replacePending && queue.replaceParallelBroadcastLocked(r);
        if (!replaced) {
            //加入到并行队列中
            queue.enqueueParallelBroadcastLocked(r);
            //发送广播
            queue.scheduleBroadcastsLocked();
        }
        registeredReceivers = null;
        NR = 0;
    }

    // 如果有静态的receiver,把静态接收者和动态接收者合并成一个队列
    int ir = 0;
    if (receivers != null) {
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
                //得到静态接收者
                curt = (ResolveInfo)receivers.get(it);
            }
            if (curr == null) {
                //得到动态接收者
                curr = registeredReceivers.get(ir);
            }
            //根据优先级把动态接收者插入到静态接收者的队列，同优先级下，动态接收者会被插入到静态接收者的后面
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
    //在队列中加入剩下的动态接收者
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
        //创建BroadcastRecord对象
        BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                callerPackage, callingPid, callingUid, resolvedType,
                requiredPermissions, appOp, brOptions, receivers, resultTo, resultCode,
                resultData, resultExtras, ordered, sticky, false, userId);

        boolean replaced = replacePending && queue.replaceOrderedBroadcastLocked(r);
        if (!replaced) {
            //把广播加入到队列
            queue.enqueueOrderedBroadcastLocked(r);
            //发送广播
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


#### `ActivityManagerService.collectReceiverComponents()`方法
通过调用PackageManagerService.queryIntentReceivers()方法手机ResolveInfo列表
```java
private List<ResolveInfo> collectReceiverComponents(Intent intent, String resolvedType, int callingUid, int[] users) {
    int pmFlags = STOCK_PM_FLAGS | MATCH_DEBUG_TRIAGED_MISSING;

    List<ResolveInfo> receivers = null;
    try {
        HashSet<ComponentName> singleUserReceivers = null;
        boolean scannedFirstReceivers = false;
        for (int user : users) {
            // Skip users that have Shell restrictions, with exception of always permitted
            // Shell broadcasts
            if (callingUid == Process.SHELL_UID
                    && mUserController.hasUserRestriction(
                            UserManager.DISALLOW_DEBUGGING_FEATURES, user)
                    && !isPermittedShellBroadcast(intent)) {
                continue;
            }
            //调用PackageManagerService.queryIntentReceivers()方法
            List<ResolveInfo> newReceivers = AppGlobals.getPackageManager()
                    .queryIntentReceivers(intent, resolvedType, pmFlags, user).getList();
            if (user != UserHandle.USER_SYSTEM && newReceivers != null) {
                // If this is not the system user, we need to check for
                // any receivers that should be filtered out.
                for (int i=0; i<newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags&ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                        newReceivers.remove(i);
                        i--;
                    }
                }
            }
            if (newReceivers != null && newReceivers.size() == 0) {
                newReceivers = null;
            }
            if (receivers == null) {
                receivers = newReceivers;
            } else if (newReceivers != null) {
                // We need to concatenate the additional receivers
                // found with what we have do far.  This would be easy,
                // but we also need to de-dup any receivers that are
                // singleUser.
                if (!scannedFirstReceivers) {
                    // Collect any single user receivers we had already retrieved.
                    scannedFirstReceivers = true;
                    for (int i=0; i<receivers.size(); i++) {
                        ResolveInfo ri = receivers.get(i);
                        if ((ri.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
                            ComponentName cn = new ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name);
                            if (singleUserReceivers == null) {
                                singleUserReceivers = new HashSet<ComponentName>();
                            }
                            singleUserReceivers.add(cn);
                        }
                    }
                }
                // Add the new results to the existing results, tracking
                // and de-dupping single user receivers.
                for (int i=0; i<newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
                        ComponentName cn = new ComponentName(
                                ri.activityInfo.packageName, ri.activityInfo.name);
                        if (singleUserReceivers == null) {
                            singleUserReceivers = new HashSet<ComponentName>();
                        }
                        if (!singleUserReceivers.contains(cn)) {
                            singleUserReceivers.add(cn);
                            receivers.add(ri);
                        }
                    } else {
                        receivers.add(ri);
                    }
                }
            }
        }
    } catch (RemoteException ex) {}
    return receivers;
}
```    

#### `PackageManagerService.queryIntentReceivers()`方法
```java
@Override
public @NonNull ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent,
        String resolvedType, int flags, int userId) {
    return new ParceledListSlice<>(
            queryIntentReceiversInternal(intent, resolvedType, flags, userId));
}
```

#### `PackageManagerService.queryIntentReceiversInternal()`方法
```java
private @NonNull List<ResolveInfo> queryIntentReceiversInternal(Intent intent,
        String resolvedType, int flags, int userId) {
    if (!sUserManager.exists(userId)) return Collections.emptyList();
    flags = updateFlagsForResolve(flags, userId, intent);
    ComponentName comp = intent.getComponent();
    if (comp == null) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
            comp = intent.getComponent();
        }
    }
    if (comp != null) {
        List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
        ActivityInfo ai = getReceiverInfo(comp, flags, userId);
        if (ai != null) {
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = ai;
            list.add(ri);
        }
        return list;
    }

    // reader
    synchronized (mPackages) {
        String pkgName = intent.getPackage();
        if (pkgName == null) {
            return mReceivers.queryIntent(intent, resolvedType, flags, userId);
        }
        final PackageParser.Package pkg = mPackages.get(pkgName);
        if (pkg != null) {
            return mReceivers.queryIntentForPackage(intent, resolvedType, flags, pkg.receivers,
                    userId);
        }
        return Collections.emptyList();
    }
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
    }

    // If the intent does not specify any data -- either a MIME type or
    // a URI -- then we will only be looking for matches against empty
    // data.
    if (resolvedType == null && scheme == null && intent.getAction() != null) {
        firstTypeCut = mActionToFilter.get(intent.getAction());
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


### `BroadcastQueue.scheduleBroadcastsLocked()`方法
```java
public void scheduleBroadcastsLocked() {
    if (mBroadcastsScheduled) {
        return;
    }
    //调用Handler发送信息
    mHandler.sendMessage(mHandler.obtainMessage(BROADCAST_INTENT_MSG, this));
    mBroadcastsScheduled = true;
}
```

### `BroadcastQueue.BroadcastHandler.handleMessage()`
```java
private final class BroadcastHandler extends Handler {
    public BroadcastHandler(Looper looper) {
        super(looper, null, true);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case BROADCAST_INTENT_MSG: {
                //调用processNextBroadcast()方法
                processNextBroadcast(true);
            } break;
            case BROADCAST_TIMEOUT_MSG: {
                synchronized (mService) {
                    broadcastTimeoutLocked(true);
                }
            } break;
            case SCHEDULE_TEMP_WHITELIST_MSG: {
                DeviceIdleController.LocalService dic = mService.mLocalDeviceIdleController;
                if (dic != null) {
                    dic.addPowerSaveTempWhitelistAppDirect(UserHandle.getAppId(msg.arg1),
                            msg.arg2, true, (String)msg.obj);
                }
            } break;
        }
    }
}
```

### `BroadcastQueue.processNextBroadcast()`方法
```java
final void processNextBroadcast(boolean fromMsg) {
    synchronized(mService) {
        BroadcastRecord r;

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "processNextBroadcast ["
                + mQueueName + "]: "
                + mParallelBroadcasts.size() + " broadcasts, "
                + mOrderedBroadcasts.size() + " ordered broadcasts");

        mService.updateCpuStats();

        if (fromMsg) {
            mBroadcastsScheduled = false;
        }

        // First, deliver any non-serialized broadcasts right away.
        while (mParallelBroadcasts.size() > 0) {
            r = mParallelBroadcasts.remove(0);
            r.dispatchTime = SystemClock.uptimeMillis();
            r.dispatchClockTime = System.currentTimeMillis();
            final int N = r.receivers.size();
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing parallel broadcast ["
                    + mQueueName + "] " + r);
            for (int i=0; i<N; i++) {
                Object target = r.receivers.get(i);
                if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                        "Delivering non-ordered on [" + mQueueName + "] to registered "
                        + target + ": " + r);
                deliverToRegisteredReceiverLocked(r, (BroadcastFilter)target, false, i);
            }
            addBroadcastToHistoryLocked(r);
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Done with parallel broadcast ["
                    + mQueueName + "] " + r);
        }

        // Now take care of the next serialized one...

        // If we are waiting for a process to come up to handle the next
        // broadcast, then do nothing at this point.  Just in case, we
        // check that the process we're waiting for still exists.
        if (mPendingBroadcast != null) {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                    "processNextBroadcast [" + mQueueName + "]: waiting for "
                    + mPendingBroadcast.curApp);

            boolean isDead;
            synchronized (mService.mPidsSelfLocked) {
                ProcessRecord proc = mService.mPidsSelfLocked.get(mPendingBroadcast.curApp.pid);
                isDead = proc == null || proc.crashing;
            }
            if (!isDead) {
                // It's still alive, so keep waiting
                return;
            } else {
                Slog.w(TAG, "pending app  ["
                        + mQueueName + "]" + mPendingBroadcast.curApp
                        + " died before responding to broadcast");
                mPendingBroadcast.state = BroadcastRecord.IDLE;
                mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                mPendingBroadcast = null;
            }
        }

        boolean looped = false;

        do {
            if (mOrderedBroadcasts.size() == 0) {
                // No more broadcasts pending, so all done!
                mService.scheduleAppGcsLocked();
                if (looped) {
                    // If we had finished the last ordered broadcast, then
                    // make sure all processes have correct oom and sched
                    // adjustments.
                    mService.updateOomAdjLocked();
                }
                return;
            }
            r = mOrderedBroadcasts.get(0);
            boolean forceReceive = false;

            // Ensure that even if something goes awry with the timeout
            // detection, we catch "hung" broadcasts here, discard them,
            // and continue to make progress.
            //
            // This is only done if the system is ready so that PRE_BOOT_COMPLETED
            // receivers don't get executed with timeouts. They're intended for
            // one time heavy lifting after system upgrades and can take
            // significant amounts of time.
            int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
            if (mService.mProcessesReady && r.dispatchTime > 0) {
                long now = SystemClock.uptimeMillis();
                if ((numReceivers > 0) &&
                        (now > r.dispatchTime + (2*mTimeoutPeriod*numReceivers))) {
                    Slog.w(TAG, "Hung broadcast ["
                            + mQueueName + "] discarded after timeout failure:"
                            + " now=" + now
                            + " dispatchTime=" + r.dispatchTime
                            + " startTime=" + r.receiverTime
                            + " intent=" + r.intent
                            + " numReceivers=" + numReceivers
                            + " nextReceiver=" + r.nextReceiver
                            + " state=" + r.state);
                    broadcastTimeoutLocked(false); // forcibly finish this broadcast
                    forceReceive = true;
                    r.state = BroadcastRecord.IDLE;
                }
            }

            if (r.state != BroadcastRecord.IDLE) {
                if (DEBUG_BROADCAST) Slog.d(TAG_BROADCAST,
                        "processNextBroadcast("
                        + mQueueName + ") called when not idle (state="
                        + r.state + ")");
                return;
            }

            if (r.receivers == null || r.nextReceiver >= numReceivers
                    || r.resultAbort || forceReceive) {
                // No more receivers for this broadcast!  Send the final
                // result if requested...
                if (r.resultTo != null) {
                    try {
                        if (DEBUG_BROADCAST) Slog.i(TAG_BROADCAST,
                                "Finishing broadcast [" + mQueueName + "] "
                                + r.intent.getAction() + " app=" + r.callerApp);
                        performReceiveLocked(r.callerApp, r.resultTo,
                            new Intent(r.intent), r.resultCode,
                            r.resultData, r.resultExtras, false, false, r.userId);
                        // Set this to null so that the reference
                        // (local and remote) isn't kept in the mBroadcastHistory.
                        r.resultTo = null;
                    } catch (RemoteException e) {
                        r.resultTo = null;
                        Slog.w(TAG, "Failure ["
                                + mQueueName + "] sending broadcast result of "
                                + r.intent, e);

                    }
                }

                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Cancelling BROADCAST_TIMEOUT_MSG");
                cancelBroadcastTimeoutLocked();

                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                        "Finished with ordered broadcast " + r);

                // ... and on to the next...
                addBroadcastToHistoryLocked(r);
                if (r.intent.getComponent() == null && r.intent.getPackage() == null
                        && (r.intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    // This was an implicit broadcast... let's record it for posterity.
                    mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                            r.manifestCount, r.manifestSkipCount, r.finishTime-r.dispatchTime);
                }
                mOrderedBroadcasts.remove(0);
                r = null;
                looped = true;
                continue;
            }
        } while (r == null);

        // Get the next receiver...
        int recIdx = r.nextReceiver++;

        // Keep track of when this receiver started, and make sure there
        // is a timeout message pending to kill it if need be.
        r.receiverTime = SystemClock.uptimeMillis();
        if (recIdx == 0) {
            r.dispatchTime = r.receiverTime;
            r.dispatchClockTime = System.currentTimeMillis();
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing ordered broadcast ["
                    + mQueueName + "] " + r);
        }
        if (! mPendingBroadcastTimeoutMessage) {
            long timeoutTime = r.receiverTime + mTimeoutPeriod;
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "Submitting BROADCAST_TIMEOUT_MSG ["
                    + mQueueName + "] for " + r + " at " + timeoutTime);
            setBroadcastTimeoutLocked(timeoutTime);
        }

        final BroadcastOptions brOptions = r.options;
        final Object nextReceiver = r.receivers.get(recIdx);

        if (nextReceiver instanceof BroadcastFilter) {
            // Simple case: this is a registered receiver who gets
            // a direct call.
            BroadcastFilter filter = (BroadcastFilter)nextReceiver;
            if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                    "Delivering ordered ["
                    + mQueueName + "] to registered "
                    + filter + ": " + r);
            deliverToRegisteredReceiverLocked(r, filter, r.ordered, recIdx);
            if (r.receiver == null || !r.ordered) {
                // The receiver has already finished, so schedule to
                // process the next one.
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Quick finishing ["
                        + mQueueName + "]: ordered="
                        + r.ordered + " receiver=" + r.receiver);
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
            } else {
                if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
                    scheduleTempWhitelistLocked(filter.owningUid,
                            brOptions.getTemporaryAppWhitelistDuration(), r);
                }
            }
            return;
        }

        // Hard case: need to instantiate the receiver, possibly
        // starting its application process to host it.

        ResolveInfo info =
            (ResolveInfo)nextReceiver;
        ComponentName component = new ComponentName(
                info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name);

        boolean skip = false;
        if (brOptions != null &&
                (info.activityInfo.applicationInfo.targetSdkVersion
                        < brOptions.getMinManifestReceiverApiLevel() ||
                info.activityInfo.applicationInfo.targetSdkVersion
                        > brOptions.getMaxManifestReceiverApiLevel())) {
            skip = true;
        }
        int perm = mService.checkComponentPermission(info.activityInfo.permission,
                r.callingPid, r.callingUid, info.activityInfo.applicationInfo.uid,
                info.activityInfo.exported);
        if (!skip && perm != PackageManager.PERMISSION_GRANTED) {
            if (!info.activityInfo.exported) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid=" + r.callingPid
                        + ", uid=" + r.callingUid + ")"
                        + " is not exported from uid " + info.activityInfo.applicationInfo.uid
                        + " due to receiver " + component.flattenToShortString());
            } else {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid=" + r.callingPid
                        + ", uid=" + r.callingUid + ")"
                        + " requires " + info.activityInfo.permission
                        + " due to receiver " + component.flattenToShortString());
            }
            skip = true;
        } else if (!skip && info.activityInfo.permission != null) {
            final int opCode = AppOpsManager.permissionToOpCode(info.activityInfo.permission);
            if (opCode != AppOpsManager.OP_NONE
                    && mService.mAppOpsService.noteOperation(opCode, r.callingUid,
                            r.callerPackage) != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires appop " + AppOpsManager.permissionToOp(
                                info.activityInfo.permission)
                        + " due to registered receiver "
                        + component.flattenToShortString());
                skip = true;
            }
        }
        if (!skip && info.activityInfo.applicationInfo.uid != Process.SYSTEM_UID &&
            r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                try {
                    perm = AppGlobals.getPackageManager().
                            checkPermission(requiredPermission,
                                    info.activityInfo.applicationInfo.packageName,
                                    UserHandle
                                            .getUserId(info.activityInfo.applicationInfo.uid));
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString()
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                        && mService.mAppOpsService.noteOperation(appOp,
                        info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                        != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString()
                            + " requires appop " + AppOpsManager.permissionToOp(
                            requiredPermission)
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
            }
        }
        if (!skip && r.appOp != AppOpsManager.OP_NONE
                && mService.mAppOpsService.noteOperation(r.appOp,
                info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Appop Denial: receiving "
                    + r.intent + " to "
                    + component.flattenToShortString()
                    + " requires appop " + AppOpsManager.opToName(r.appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }
        if (!skip) {
            skip = !mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                    r.callingPid, r.resolvedType, info.activityInfo.applicationInfo.uid);
        }
        boolean isSingleton = false;
        try {
            isSingleton = mService.isSingleton(info.activityInfo.processName,
                    info.activityInfo.applicationInfo,
                    info.activityInfo.name, info.activityInfo.flags);
        } catch (SecurityException e) {
            Slog.w(TAG, e.getMessage());
            skip = true;
        }
        if ((info.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
            if (ActivityManager.checkUidPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    info.activityInfo.applicationInfo.uid)
                            != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: Receiver " + component.flattenToShortString()
                        + " requests FLAG_SINGLE_USER, but app does not hold "
                        + android.Manifest.permission.INTERACT_ACROSS_USERS);
                skip = true;
            }
        }
        if (!skip) {
            r.manifestCount++;
        } else {
            r.manifestSkipCount++;
        }
        if (r.curApp != null && r.curApp.crashing) {
            // If the target process is crashing, just skip it.
            Slog.w(TAG, "Skipping deliver ordered [" + mQueueName + "] " + r
                    + " to " + r.curApp + ": process crashing");
            skip = true;
        }
        if (!skip) {
            boolean isAvailable = false;
            try {
                isAvailable = AppGlobals.getPackageManager().isPackageAvailable(
                        info.activityInfo.packageName,
                        UserHandle.getUserId(info.activityInfo.applicationInfo.uid));
            } catch (Exception e) {
                // all such failures mean we skip this receiver
                Slog.w(TAG, "Exception getting recipient info for "
                        + info.activityInfo.packageName, e);
            }
            if (!isAvailable) {
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Skipping delivery to " + info.activityInfo.packageName + " / "
                        + info.activityInfo.applicationInfo.uid
                        + " : package no longer available");
                skip = true;
            }
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if ((mService.mPermissionReviewRequired
                || Build.PERMISSIONS_REVIEW_REQUIRED) && !skip) {
            if (!requestStartTargetPermissionsReviewIfNeededLocked(r,
                    info.activityInfo.packageName, UserHandle.getUserId(
                            info.activityInfo.applicationInfo.uid))) {
                skip = true;
            }
        }

        // This is safe to do even if we are skipping the broadcast, and we need
        // this information now to evaluate whether it is going to be allowed to run.
        final int receiverUid = info.activityInfo.applicationInfo.uid;
        // If it's a singleton, it needs to be the same app or a special app
        if (r.callingUid != Process.SYSTEM_UID && isSingleton
                && mService.isValidSingletonCall(r.callingUid, receiverUid)) {
            info.activityInfo = mService.getActivityInfoForUser(info.activityInfo, 0);
        }
        String targetProcess = info.activityInfo.processName;
        ProcessRecord app = mService.getProcessRecordLocked(targetProcess,
                info.activityInfo.applicationInfo.uid, false);

        if (!skip) {
            final int allowed = mService.checkAllowBackgroundLocked(
                    info.activityInfo.applicationInfo.uid, info.activityInfo.packageName, -1,
                    false);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                // We won't allow this receiver to be launched if the app has been
                // completely disabled from launches, or it was not explicitly sent
                // to it and the app is in a state that should not receive it
                // (depending on how checkAllowBackgroundLocked has determined that).
                if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                    Slog.w(TAG, "Background execution disabled: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString());
                    skip = true;
                } else if (((r.intent.getFlags()&Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                        || (r.intent.getComponent() == null
                            && r.intent.getPackage() == null
                            && ((r.intent.getFlags()
                                    & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0))) {
                    Slog.w(TAG, "Background execution not allowed: receiving "
                            + r.intent + " to "
                            + component.flattenToShortString());
                    skip = true;
                }
            }
        }

        if (skip) {
            if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                    "Skipping delivery of ordered [" + mQueueName + "] "
                    + r + " for whatever reason");
            r.delivery[recIdx] = BroadcastRecord.DELIVERY_SKIPPED;
            r.receiver = null;
            r.curFilter = null;
            r.state = BroadcastRecord.IDLE;
            scheduleBroadcastsLocked();
            return;
        }

        r.delivery[recIdx] = BroadcastRecord.DELIVERY_DELIVERED;
        r.state = BroadcastRecord.APP_RECEIVE;
        r.curComponent = component;
        r.curReceiver = info.activityInfo;
        if (DEBUG_MU && r.callingUid > UserHandle.PER_USER_RANGE) {
            Slog.v(TAG_MU, "Updated broadcast record activity info for secondary user, "
                    + info.activityInfo + ", callingUid = " + r.callingUid + ", uid = "
                    + info.activityInfo.applicationInfo.uid);
        }

        if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
            scheduleTempWhitelistLocked(receiverUid,
                    brOptions.getTemporaryAppWhitelistDuration(), r);
        }

        // Broadcast is being executed, its package can't be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.curComponent.getPackageName(), false, UserHandle.getUserId(r.callingUid));
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.curComponent.getPackageName() + ": " + e);
        }

        // Is this receiver's application already running?
        if (app != null && app.thread != null) {
            try {
                app.addPackage(info.activityInfo.packageName,
                        info.activityInfo.applicationInfo.versionCode, mService.mProcessStats);
                processCurBroadcastLocked(r, app);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when sending broadcast to "
                      + r.curComponent, e);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "Failed sending broadcast to "
                        + r.curComponent + " with " + r.intent, e);
                // If some unexpected exception happened, just skip
                // this broadcast.  At this point we are not in the call
                // from a client, so throwing an exception out from here
                // will crash the entire system instead of just whoever
                // sent the broadcast.
                logBroadcastReceiverDiscardLocked(r);
                finishReceiverLocked(r, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, false);
                scheduleBroadcastsLocked();
                // We need to reset the state if we failed to start the receiver.
                r.state = BroadcastRecord.IDLE;
                return;
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        // Not running -- get it started, to be executed when the app comes up.
        if (DEBUG_BROADCAST)  Slog.v(TAG_BROADCAST,
                "Need to start app ["
                + mQueueName + "] " + targetProcess + " for broadcast " + r);
        if ((r.curApp=mService.startProcessLocked(targetProcess,
                info.activityInfo.applicationInfo, true,
                r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                "broadcast", r.curComponent,
                (r.intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0, false, false))
                        == null) {
            // Ah, this recipient is unavailable.  Finish it if necessary,
            // and mark the broadcast record as ready for the next.
            Slog.w(TAG, "Unable to launch app "
                    + info.activityInfo.applicationInfo.packageName + "/"
                    + info.activityInfo.applicationInfo.uid + " for broadcast "
                    + r.intent + ": process is bad");
            logBroadcastReceiverDiscardLocked(r);
            finishReceiverLocked(r, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, false);
            scheduleBroadcastsLocked();
            r.state = BroadcastRecord.IDLE;
            return;
        }

        mPendingBroadcast = r;
        mPendingBroadcastRecvIndex = recIdx;
    }
}
```
