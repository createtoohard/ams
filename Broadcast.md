# Broadcast的种类
1. 普通广播：
    * 通过Context的`sendBroadcast()`和`sendBroadcastAsUser()`方法发送的广播属于普通广播
    * 普通广播的特点是发送给系统当前的所有注册的接收者（BroadcastReceiver）
    * 广播接收者接收广播的顺序是不确定的
2. 有序广播：
    * 通过Context的`sendOrderedBroadcast()`和`sendOrderedBroadcastAsUser()`方法发送的广播属于有序广播
    * 有序广播接收者接收广播时是有顺序的，发送广播的顺序按照接收者的优先级来确定的
        * 优先级不同：
            * 优先级高的比优先级低的接收者先接收到广播
        * 优先级相同：
            * 先注册的广播接收者先接收到广播，但是动态注册的比静态注册的接收者先收到广播
    * 有序广播可以打断广播的发送，如果接收者不希望广播继续传递，可以通过返回值来终止传递
    * 有序广播的接收者可以修改广播的内容
3. 粘性广播：
    * 粘性广播能发送给系统中以后注册的接收者，甚至是新安装的应用中的接收者。
    * 普通粘性广播：通过Context的`sendStickyBroadcast()`方法发送
    * 有序粘性广播：通过Context的`sendStickyOrderedBroadcast()`方法发送


# BroadcastReceiver的种类
1. 静态接收者：指在AndroidManifest.xml中通过标签`<receiver></receiver>`指定的接收者
2. 动态接收者：指通过AMS的`registerReceiver()`方法注册的接收者
    * 动态注册的好处是使用灵活，在不需要接收广播的使用可以通过`unregisterReceiver()`方法取消注册

# Broadcast的数据结构
* `mRegisteredReceivers`
    * 在AMS中，所有注册的接收者放在成员变量`mRegisteredReceivers`中，定义如下：
    `final HashMap<IBinder, ReceiverList> mRegisteredReceivers`
    * `mRegisteredReceivers`是一个HashMap，使用广播接收者的IBinder作为key来存储接收者的对象ReceiverList。
    * `ReceiverList`继承ArrayList，是一个列表，因为一个接收者种可能会接收多个广播，有多个IntentFilter。

* `ReceiverList`
    * `ReceiverList`继承自ArrayList，定义如下：
    ```java
    final class ReceiverList extends ArrayList<BroadcastFilter> implements IBinder.DeathRecipient {
        final ActivityManagerService owner;
        public final IIntentReceiver receiver;//用户进程中定义的IntentReceiver
        public final ProcessRecord app;//所属用户进程的ProcessRecord
        public final int pid;//所属用户进程的pid
        public final int uid;//所属用户进程的uid
        public final int userId;//userid
        BroadcastRecord curBroadcast = null;//一个广播结构
        boolean linkedToDeath = false;//是否注册了死亡通知
    }
    ```
    * `ReceiverList`实现了`IBinder.DeathRecipient`，如果注册了receiver的应用发生崩溃，AMS中的ReceiverList对象就能收到通知，去处这个进程的receiver
    * 发送广播时，AMS中收到的广播消息首先保存在`mBroadcastQueues`对象中，然后在发送给用户进程中的接收者。`mBroadcastQueues`是一个只有两个元素的数组，定义如下：
    `final BroadcastQueue[] mBroadcastQueues = new BroadcastQueue[2];`
    * AMS中还定义了两个BroadcastQueue类型的变量，定义如下
    ```java
    BroadcastQueue mFgBroadcastQueue;
    BroadcastQueue mBgBroadcastQueue;
    ```
    * AMS初始化时将`mFgBroadcastQueue`赋给了`mBroadcastQueues[0]`，将`mBgBroadcastQueue`赋给了`mBroadcastQueues[1]`。
        * `mFgBroadcastQueue`用来保存带有`FLAG_RECEIVER_FOREGROUND`表示的广播，它要求接收者进程以foreground优先级运行，这样执行的更快。
    * AMS中还定义了广播的超时信息，定义如下
    ```java
    static final int BROADCAST_FG_TIMEOUT = 10*1000;//10秒
    static final int BROADCAST_BG_TIMEOUT = 60*1000;//60秒
    ```

* `BroadcastFilter`
    * `BroadcastFilter`继承自IntentFilter，定义如下：
    ```java
    final class BroadcastFilter extends IntentFilter {
        final ReceiverList receiverList;//所属receiver的应用
        final String packageName;//所在应用的包名
        final String requiredPermission;//权限字符串
        final int owningUid;//所在应用的uid
        final int owningUserId;//所在应用的UserId
    }
    ```

* `BroadcastQueue`
    * `BroadcastQueue`就是广播队列，主要成员变量如下：
    ```java
    //用来保存所有普通广播
    final ArrayList<BroadcastRecord> mParallelBroadcasts
    //用来保存所有有序广播
    final ArrayList<BroadcastRecord> mOrderedBroadcasts
    //用来保存所有发送过的广播
    final BroadcastRecord[] mBroadcastHistory
    ```

* `mStickyBroadcasts`
    * 定义在AMS中，用来保存系统所有的粘性广播，定义如下：
    `final SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts`
    * `mStickyBroadcasts`是一个稀疏数组，使用用户id作为索引，保存的是ArrayMap对象，这个存储的是某个用户发送(接收？？)的所有粘性广播
        * ArrayMap中，每条记录以Action字符串为索引，保存的内容是一个ArrayList对象，其中保存的是包含该action的所有Intent
    * `([key]userId, [value]ArrayMap([key]action, [value]ArrayList(Intent)))`

# Broadcast的注册过程
* 应用中动态注册广播通过Context类中的`registerReceiver()`或者`registerReceiverAsUser()`方法

## Broadcast的注册过程流程图
```puml
ContextImpl -> ContextImpl : registerReceiver()
ContextImpl -> ContextImpl : registerReceiverInternal()
ContextImpl -> AMS : registerReceiver()
```

### `ContextImpl.registerReceiver()`方法
* 无论是`registerReceiver()`方法还是`registerReceiverAsUser()`方法都会调用`registerReceiverInternal()`方法
* `String broadcastPermission`如果传入该参数，表示发送广播时需要拥有这个权限，该receiver才能接收到相应的广播
* `Handler scheduler`BroadcastReceiver是在主线程执行`onReceiver()`方法的，如果指定该参数会在相应的线程执行该方法
```java
@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
    return registerReceiver(receiver, filter, null, null);
}

@Override
public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
        String broadcastPermission, Handler scheduler) {
    return registerReceiverInternal(receiver, getUserId(),
            filter, broadcastPermission, scheduler, getOuterContext());
}

@Override
public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
        IntentFilter filter, String broadcastPermission, Handler scheduler) {
    return registerReceiverInternal(receiver, user.getIdentifier(),
            filter, broadcastPermission, scheduler, getOuterContext());
}
```

### `ContextImpl.registerReceiverInternal()`方法
```java
private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
        IntentFilter filter, String broadcastPermission,
        Handler scheduler, Context context) {
    IIntentReceiver rd = null;
    if (receiver != null) {
        if (mPackageInfo != null && context != null) {
            if (scheduler == null) {
                scheduler = mMainThread.getHandler();
            }
            rd = mPackageInfo.getReceiverDispatcher(
                receiver, context, scheduler,
                mMainThread.getInstrumentation(), true);
        } else {
            if (scheduler == null) {
                scheduler = mMainThread.getHandler();
            }
            rd = new LoadedApk.ReceiverDispatcher(
                    receiver, context, scheduler, null, true).getIIntentReceiver();
        }
    }
    try {
        //调用AMS的registerReceiver()方法
        final Intent intent = ActivityManagerNative.getDefault().registerReceiver(
                mMainThread.getApplicationThread(), mBasePackageName,
                rd, filter, broadcastPermission, userId);
        if (intent != null) {
            intent.setExtrasClassLoader(getClassLoader());
            intent.prepareToEnterProcess();
        }
        return intent;
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

### `ActivityManagerService.registerReceiver()`方法
```java
public Intent registerReceiver(IApplicationThread caller, String callerPackage,
        IIntentReceiver receiver, IntentFilter filter, String permission, int userId) {
    enforceNotIsolatedCaller("registerReceiver");
    ArrayList<Intent> stickyIntents = null;
    ProcessRecord callerApp = null;
    int callingUid;
    int callingPid;
    synchronized(this) {
        if (caller != null) {
            //通过getRecordForAppLocked()方法获取调用进程是否是系统中正在运行的进程
            callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(...);
            }
            if (callerApp.info.uid != Process.SYSTEM_UID &&
                    !callerApp.pkgList.containsKey(callerPackage) &&
                    !"android".equals(callerPackage)) {
                throw new SecurityException(...);
            }
            //从进程对象中获得uid和pid
            callingUid = callerApp.info.uid;
            callingPid = callerApp.pid;
        } else {
            //如果没有传入调用者信息，则通过Binder的到uid和pid
            callerPackage = null;
            callingUid = Binder.getCallingUid();
            callingPid = Binder.getCallingPid();
        }
        //检查参数中传入的userId是否和调用进程所属的userId一致
        //通常只有root用户或system用户才能以其他用户的身份注册receiver
        userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

        //查询系统中和参数IntentFilter匹配的粘性广播
        Iterator<String> actions = filter.actionsIterator();
        if (actions == null) {
            ArrayList<String> noAction = new ArrayList<String>(1);
            noAction.add(null);
            actions = noAction.iterator();
        }

        //收集用户的粘性广播
        int[] userIds = { UserHandle.USER_ALL, UserHandle.getUserId(callingUid) };
        while (actions.hasNext()) {
            String action = actions.next();
            for (int id : userIds) {
                ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(id);
                if (stickies != null) {
                    ArrayList<Intent> intents = stickies.get(action);
                    if (intents != null) {
                        if (stickyIntents == null) {
                            stickyIntents = new ArrayList<Intent>();
                        }
                        stickyIntents.addAll(intents);
                    }
                }
            }
        }
    }

    ArrayList<Intent> allSticky = null;
    if (stickyIntents != null) {
        final ContentResolver resolver = mContext.getContentResolver();
        //寻找任何匹配的粘性广播
        for (int i = 0, N = stickyIntents.size(); i < N; i++) {
            Intent intent = stickyIntents.get(i);
            if (filter.match(resolver, intent, true, TAG) >= 0) {
                if (allSticky == null) {
                    allSticky = new ArrayList<Intent>();
                }
                allSticky.add(intent);
            }
        }
    }

    //如果receiver为null，列表中的第一个粘性广播直接返回给客户端
    Intent sticky = allSticky != null ? allSticky.get(0) : null;
    if (receiver == null) {
        return sticky;
    }

    synchronized (this) {
        if (callerApp != null && (callerApp.thread == null
                || callerApp.thread.asBinder() != caller.asBinder())) {
            //调用进程挂了
            return null;
        }
        //检查参数中的receiver是否已经注册过了，如果注册了还可以增加新的IntentFilter
        ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
        if (rl == null) {
            //没有注册创建新的对象
            rl = new ReceiverList(this, callerApp, callingPid, callingUid,
                    userId, receiver);
            if (rl.app != null) {
                //如果指定了进程对象，则把receiver保存在进程对象中，这样进程销毁时能释放receiver
                rl.app.receivers.add(rl);
            } else {
                //如果没有指定进程对象，则给receiver注册死亡通知
                try {
                    receiver.asBinder().linkToDeath(rl, 0);
                } catch (RemoteException e) {
                    return sticky;
                }
                rl.linkedToDeath = true;
            }
            //如果receiver没有注册过，添加到mRegisteredReceivers数组中
            mRegisteredReceivers.put(receiver.asBinder(), rl);
        } else if (rl.uid != callingUid) {
            throw new IllegalArgumentException(...);
        } else if (rl.pid != callingPid) {
            throw new IllegalArgumentException(...);
        } else if (rl.userId != userId) {
            throw new IllegalArgumentException(...);
        }
        //uid和pid以及userId不同会抛出异常，只有一样才会增加新的BroadcastFilter
        BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage,
                permission, callingUid, userId);
        rl.add(bf);
        //把BroadcastFilter对象也加入到mReceiverResolver列表中
        mReceiverResolver.addFilter(bf);

        //处理和Intent匹配的stick广播
        if (allSticky != null) {
            ArrayList receivers = new ArrayList();
            receivers.add(bf);

            final int stickyCount = allSticky.size();
            for (int i = 0; i < stickyCount; i++) {
                Intent intent = allSticky.get(i);
                //根据Intent中的标志得到发送队列BroadcastQueue对象，调用broadcastQueueForIntent()方法
                BroadcastQueue queue = broadcastQueueForIntent(intent);
                BroadcastRecord r = new BroadcastRecord(queue, intent, null,
                        null, -1, -1, null, null, AppOpsManager.OP_NONE, null, receivers,
                        null, 0, null, null, false, true, true, -1);
                //向队列中加入广播记录
                queue.enqueueParallelBroadcastLocked(r);
                //发送Intent到指定的进程
                queue.scheduleBroadcastsLocked();
            }
        }
        return sticky;
    }
}
```


# Broadcast的发送过程
* 应用发送广播调用的是Context类中的方法

## Broadcast发送过程流程图
```puml
ContextImpl -> ContextImpl : sendBroadcast()
ContextImpl -> AMS : broadcastIntent()
AMS -> AMS : broadcastIntentLocked()
AMS -> BroadcastQueue : scheduleBroadcastsLocked()
BroadcastQueue -> BroadcastHandler : sendMessage()
BroadcastHandler -> BroadcastHandler : handleMessage()
BroadcastHandler -> BroadcastQueue : processNextBroadcast()
BroadcastQueue -> BroadcastQueue : deliverToRegisteredReceiverLocked()
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
        //对一些特殊flag的广播的合法性进行检查
        intent = verifyBroadcastLocked(intent);
        //获取广播发送者的信息
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
2. 如果是一些系统广播，特殊广播则调用相应的方法处理（根据相应的action进行处理）
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
    //默认不包含已经停止的应用（即应用处于stop状态，该广播不发送给它）即该BroadcastReceiver所在的进程无法接收到该广播
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
        //粘性广播不能指定组件
        if (intent.getComponent() != null) {
            throw new SecurityException(...);
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
1. 处理所有非序列化广播
2. 处理需要进程启动的广播
3. 处理有序广播

```java
final void processNextBroadcast(boolean fromMsg) {
    synchronized(mService) {
        BroadcastRecord r;

        mService.updateCpuStats();

        if (fromMsg) {
            mBroadcastsScheduled = false;
        }

        // 发送所有非序列化广播
        while (mParallelBroadcasts.size() > 0) {
            r = mParallelBroadcasts.remove(0);
            r.dispatchTime = SystemClock.uptimeMillis();
            r.dispatchClockTime = System.currentTimeMillis();
            final int N = r.receivers.size();
            for (int i=0; i<N; i++) {
                Object target = r.receivers.get(i);
                //调用deliverToRegisteredReceiverLocked()方法，给广播的所有接收者发送消息
                deliverToRegisteredReceiverLocked(r, (BroadcastFilter)target, false, i);
            }
            //调用addBroadcastToHistoryLocked()方法
            addBroadcastToHistoryLocked(r);
        }

        //处理序列化广播
        // 处理等待接收进程启动的广播，如果进程没有启动需要等待
        if (mPendingBroadcast != null) {

            boolean isDead;
            synchronized (mService.mPidsSelfLocked) {
                //判断进程是否启动失败
                ProcessRecord proc = mService.mPidsSelfLocked.get(mPendingBroadcast.curApp.pid);
                isDead = proc == null || proc.crashing;
            }
            if (!isDead) {
                // 如果没有启动失败，返回继续等待
                return;
            } else {
                //如果启动失败，忽略这条广播
                mPendingBroadcast.state = BroadcastRecord.IDLE;
                mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                mPendingBroadcast = null;
            }
        }

        boolean looped = false;

        //处理有序广播
        do {
            //如果没有有序广播了
            if (mOrderedBroadcasts.size() == 0) {
                //没有等待的广播了，调用AMS的scheduleAppGcsLocked()方法
                mService.scheduleAppGcsLocked();
                if (looped) {
                    //如果处理完成了最后一次广播，调用AMS的updateOomAdjLocked()方法，处理进程adj值
                    mService.updateOomAdjLocked();
                }
                return;
            }
            r = mOrderedBroadcasts.get(0);
            boolean forceReceive = false;

            //如果有序广播
            //处理超时的有序广播
            int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
            if (mService.mProcessesReady && r.dispatchTime > 0) {
                long now = SystemClock.uptimeMillis();
                if ((numReceivers > 0) &&
                        (now > r.dispatchTime + (2*mTimeoutPeriod*numReceivers))) {
                    //如果广播超时，调用broadcastTimeoutLocked()方法强制终止广播
                    broadcastTimeoutLocked(false);
                    forceReceive = true;
                    r.state = BroadcastRecord.IDLE;
                }
            }

            //有序广播没有超时
            if (r.state != BroadcastRecord.IDLE) {
                return;
            }

            if (r.receivers == null || r.nextReceiver >= numReceivers
                    || r.resultAbort || forceReceive) {
                //如果广播没有receiver了，把发送的结果传递给发送广播的进程
                if (r.resultTo != null) {
                    try {
                        performReceiveLocked(r.callerApp, r.resultTo,
                            new Intent(r.intent), r.resultCode,
                            r.resultData, r.resultExtras, false, false, r.userId);
                        r.resultTo = null;
                    } catch (RemoteException e) {
                        r.resultTo = null;
                    }
                }

                cancelBroadcastTimeoutLocked();

                addBroadcastToHistoryLocked(r);
                if (r.intent.getComponent() == null && r.intent.getPackage() == null
                        && (r.intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                            r.manifestCount, r.manifestSkipCount, r.finishTime-r.dispatchTime);
                }
                mOrderedBroadcasts.remove(0);
                r = null;
                looped = true;
                continue;
            }
        } while (r == null);

        //获取下一个接收者
        int recIdx = r.nextReceiver++;

        //保持跟踪这个receiver什么时候开始的，并确保已经超时的信息，在需要的时候杀死它
        r.receiverTime = SystemClock.uptimeMillis();
        if (recIdx == 0) {
            r.dispatchTime = r.receiverTime;
            r.dispatchClockTime = System.currentTimeMillis();
        }
        if (! mPendingBroadcastTimeoutMessage) {
            long timeoutTime = r.receiverTime + mTimeoutPeriod;
            setBroadcastTimeoutLocked(timeoutTime);
        }

        final BroadcastOptions brOptions = r.options;
        final Object nextReceiver = r.receivers.get(recIdx);

        //如果是动态接收者，可以直接调用发送消息
        if (nextReceiver instanceof BroadcastFilter) {
            BroadcastFilter filter = (BroadcastFilter)nextReceiver;
            //调用deliverToRegisteredReceiverLocked()方法
            deliverToRegisteredReceiverLocked(r, filter, r.ordered, recIdx);
            if (r.receiver == null || !r.ordered) {
                //如果该广播不需要返回结果或者不是有序广播，直接处理下一条广播
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
            } else {
                //等待进程返回处理结果再继续
                if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
                    scheduleTempWhitelistLocked(filter.owningUid,
                            brOptions.getTemporaryAppWhitelistDuration(), r);
                }
            }
            return;
        }

        //处理静态注册的接收者
        ResolveInfo info =
            (ResolveInfo)nextReceiver;
        ComponentName component = new ComponentName(
                info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name);

        //处理权限等相关问题
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
            //......
            skip = true;
        } else if (!skip && info.activityInfo.permission != null) {
            final int opCode = AppOpsManager.permissionToOpCode(info.activityInfo.permission);
            if (opCode != AppOpsManager.OP_NONE
                    && mService.mAppOpsService.noteOperation(opCode, r.callingUid,
                            r.callerPackage) != AppOpsManager.MODE_ALLOWED) {
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
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                        && mService.mAppOpsService.noteOperation(appOp,
                        info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                        != AppOpsManager.MODE_ALLOWED) {
                    skip = true;
                    break;
                }
            }
        }
        if (!skip && r.appOp != AppOpsManager.OP_NONE
                && mService.mAppOpsService.noteOperation(r.appOp,
                info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                != AppOpsManager.MODE_ALLOWED) {
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
                skip = true;
            }
        }
        if (!skip) {
            r.manifestCount++;
        } else {
            r.manifestSkipCount++;
        }
        if (r.curApp != null && r.curApp.crashing) {
            //如果目标进程已经crash，跳过
            skip = true;
        }
        if (!skip) {
            boolean isAvailable = false;
            try {
                isAvailable = AppGlobals.getPackageManager().isPackageAvailable(
                        info.activityInfo.packageName,
                        UserHandle.getUserId(info.activityInfo.applicationInfo.uid));
            } catch (Exception e) {}
            if (!isAvailable) {
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
                    skip = true;
                } else if (((r.intent.getFlags()&Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                        || (r.intent.getComponent() == null
                            && r.intent.getPackage() == null
                            && ((r.intent.getFlags()
                                    & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0))) {
                    skip = true;
                }
            }
        }

        if (skip) {
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

        if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
            scheduleTempWhitelistLocked(receiverUid,
                    brOptions.getTemporaryAppWhitelistDuration(), r);
        }

        // 广播正在执行，它的package无法停止
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.curComponent.getPackageName(), false, UserHandle.getUserId(r.callingUid));
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {}

        // 如果接收者应用还在运行
        if (app != null && app.thread != null) {
            try {
                app.addPackage(info.activityInfo.packageName,
                        info.activityInfo.applicationInfo.versionCode, mService.mProcessStats);
                //调用processCurBroadcastLocked()方法
                processCurBroadcastLocked(r, app);
                return;
            } catch (RemoteException e) {} catch (RuntimeException e) {
                logBroadcastReceiverDiscardLocked(r);
                finishReceiverLocked(r, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, false);
                scheduleBroadcastsLocked();
                // We need to reset the state if we failed to start the receiver.
                r.state = BroadcastRecord.IDLE;
                return;
            }
        }

        // 没有运行，尝试启动它，在应用程序出现时执行它
        if ((r.curApp=mService.startProcessLocked(targetProcess,
                info.activityInfo.applicationInfo, true,
                r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                "broadcast", r.curComponent,
                (r.intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0, false, false))
                        == null) {
            // 如果启动失败，处理下一条广播
            logBroadcastReceiverDiscardLocked(r);
            finishReceiverLocked(r, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, false);
            scheduleBroadcastsLocked();
            r.state = BroadcastRecord.IDLE;
            return;
        }
        //把正在等待启动进程的广播放到mPendingBroadcast列表中
        mPendingBroadcast = r;
        mPendingBroadcastRecvIndex = recIdx;
    }
}
```

### `BroadcastQueue.deliverToRegisteredReceiverLocked()`方法
```java
private void deliverToRegisteredReceiverLocked(BroadcastRecord r,
        BroadcastFilter filter, boolean ordered, int index) {
    boolean skip = false;
    if (filter.requiredPermission != null) {
        int perm = mService.checkComponentPermission(filter.requiredPermission,
                r.callingPid, r.callingUid, -1, true);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Permission Denial: broadcasting "
                    + r.intent.toString()
                    + " from " + r.callerPackage + " (pid="
                    + r.callingPid + ", uid=" + r.callingUid + ")"
                    + " requires " + filter.requiredPermission
                    + " due to registered receiver " + filter);
            skip = true;
        } else {
            final int opCode = AppOpsManager.permissionToOpCode(filter.requiredPermission);
            if (opCode != AppOpsManager.OP_NONE
                    && mService.mAppOpsService.noteOperation(opCode, r.callingUid,
                            r.callerPackage) != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires appop " + AppOpsManager.permissionToOp(
                                filter.requiredPermission)
                        + " due to registered receiver " + filter);
                skip = true;
            }
        }
    }
    if (!skip && r.requiredPermissions != null && r.requiredPermissions.length > 0) {
        for (int i = 0; i < r.requiredPermissions.length; i++) {
            String requiredPermission = r.requiredPermissions[i];
            int perm = mService.checkComponentPermission(requiredPermission,
                    filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " requires " + requiredPermission
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
                break;
            }
            int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
            if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                    && mService.mAppOpsService.noteOperation(appOp,
                    filter.receiverList.uid, filter.packageName)
                    != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " requires appop " + AppOpsManager.permissionToOp(
                        requiredPermission)
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
                break;
            }
        }
    }
    if (!skip && (r.requiredPermissions == null || r.requiredPermissions.length == 0)) {
        int perm = mService.checkComponentPermission(null,
                filter.receiverList.pid, filter.receiverList.uid, -1, true);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Permission Denial: security check failed when receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }
    }
    if (!skip && r.appOp != AppOpsManager.OP_NONE
            && mService.mAppOpsService.noteOperation(r.appOp,
            filter.receiverList.uid, filter.packageName)
            != AppOpsManager.MODE_ALLOWED) {
        Slog.w(TAG, "Appop Denial: receiving "
                + r.intent.toString()
                + " to " + filter.receiverList.app
                + " (pid=" + filter.receiverList.pid
                + ", uid=" + filter.receiverList.uid + ")"
                + " requires appop " + AppOpsManager.opToName(r.appOp)
                + " due to sender " + r.callerPackage
                + " (uid " + r.callingUid + ")");
        skip = true;
    }
    if (!skip) {
        final int allowed = mService.checkAllowBackgroundLocked(filter.receiverList.uid,
                filter.packageName, -1, true);
        if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
            Slog.w(TAG, "Background execution not allowed: receiving "
                    + r.intent
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")");
            skip = true;
        }
    }

    if (!mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
            r.callingPid, r.resolvedType, filter.receiverList.uid)) {
        skip = true;
    }

    if (!skip && (filter.receiverList.app == null || filter.receiverList.app.crashing)) {
        Slog.w(TAG, "Skipping deliver [" + mQueueName + "] " + r
                + " to " + filter.receiverList + ": process crashing");
        skip = true;
    }

    if (skip) {
        r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
        return;
    }

    // If permissions need a review before any of the app components can run, we drop
    // the broadcast and if the calling app is in the foreground and the broadcast is
    // explicit we launch the review UI passing it a pending intent to send the skipped
    // broadcast.
    if (mService.mPermissionReviewRequired || Build.PERMISSIONS_REVIEW_REQUIRED) {
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r, filter.packageName,
                filter.owningUserId)) {
            r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
            return;
        }
    }

    r.delivery[index] = BroadcastRecord.DELIVERY_DELIVERED;

    // If this is not being sent as an ordered broadcast, then we
    // don't want to touch the fields that keep track of the current
    // state of ordered broadcasts.
    if (ordered) {
        r.receiver = filter.receiverList.receiver.asBinder();
        r.curFilter = filter;
        filter.receiverList.curBroadcast = r;
        r.state = BroadcastRecord.CALL_IN_RECEIVE;
        if (filter.receiverList.app != null) {
            // Bump hosting application to no longer be in background
            // scheduling class.  Note that we can't do that if there
            // isn't an app...  but we can only be in that case for
            // things that directly call the IActivityManager API, which
            // are already core system stuff so don't matter for this.
            r.curApp = filter.receiverList.app;
            filter.receiverList.app.curReceivers.add(r);
            mService.updateOomAdjLocked(r.curApp);
        }
    }
    try {
        if (DEBUG_BROADCAST_LIGHT) Slog.i(TAG_BROADCAST,
                "Delivering to " + filter + " : " + r);
        if (filter.receiverList.app != null && filter.receiverList.app.inFullBackup) {
            // Skip delivery if full backup in progress
            // If it's an ordered broadcast, we need to continue to the next receiver.
            if (ordered) {
                skipReceiverLocked(r);
            }
        } else {
            performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                    new Intent(r.intent), r.resultCode, r.resultData,
                    r.resultExtras, r.ordered, r.initialSticky, r.userId);
        }
        if (ordered) {
            r.state = BroadcastRecord.CALL_DONE_RECEIVE;
        }
    } catch (RemoteException e) {
        Slog.w(TAG, "Failure sending broadcast " + r.intent, e);
        if (ordered) {
            r.receiver = null;
            r.curFilter = null;
            filter.receiverList.curBroadcast = null;
            if (filter.receiverList.app != null) {
                filter.receiverList.app.curReceivers.remove(r);
            }
        }
    }
}
```
