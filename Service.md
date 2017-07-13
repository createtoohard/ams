# Service

## Service的启动过程
* Context中提供两个接口来启动Service，分别是`startService()`和`bindService()`两个方法。
* ActivityManagerService中对于Service的管理是通过 **ActiveServices** 类来进行的


## 启动Service流程图--`startService()`方式
```puml
ContextImpl -> ContextImpl : startService()
ContextImpl -> ContextImpl : startServiceCommon()
ContextImpl -> AMS : startService()
AMS -> ActiveServices : startServiceLocked()
ActiveServices -> ActiveServices : startServiceInnerLocked()
```

### `ContextImpl.startService()`方法
```java
@Override
public ComponentName startService(Intent service) {
    warnIfCallingFromSystemProcess();
    return startServiceCommon(service, mUser);
}
```


### `ContextImpl.startServiceCommon()`方法
```java
private ComponentName startServiceCommon(Intent service, UserHandle user) {
    try {
        validateServiceIntent(service);
        service.prepareToLeaveProcess(this);
        ComponentName cn = ActivityManagerNative.getDefault().startService(
            mMainThread.getApplicationThread(), service, service.resolveTypeIfNeeded(
                        getContentResolver()), getOpPackageName(), user.getIdentifier());
        if (cn != null) {
            if (cn.getPackageName().equals("!")) {
                throw new SecurityException(
                        "Not allowed to start service " + service
                        + " without permission " + cn.getClassName());
            } else if (cn.getPackageName().equals("!!")) {
                throw new SecurityException(
                        "Unable to start service " + service
                        + ": " + cn.getClassName());
            }
        }
        return cn;
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

#### `ContextImpl.validateServiceIntent()`方法
启动service的Intent如果没有指定组件名称，在Android5.0之后会抛出异常

```java
private void validateServiceIntent(Intent service) {
    if (service.getComponent() == null && service.getPackage() == null) {
        if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
            IllegalArgumentException ex = new IllegalArgumentException(...);
            throw ex;
        }
    }
}
```


### `ActivityManagerService.startService()`方法
```java
final ActiveServices mServices;

@Override
public ComponentName startService(IApplicationThread caller, Intent service,
        String resolvedType, String callingPackage, int userId)
        throws TransactionTooLargeException {
    enforceNotIsolatedCaller("startService");
    // Intent不能泄露文件描述符
    if (service != null && service.hasFileDescriptors() == true) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }
    if (callingPackage == null) {
        throw new IllegalArgumentException("callingPackage cannot be null");
    }

    synchronized(this) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();

        //调用ActiveServices.startServiceLocked()方法
        ComponentName res = mServices.startServiceLocked(caller, service,
                resolvedType, callingPid, callingUid, callingPackage, userId);
        Binder.restoreCallingIdentity(origId);
        return res;
    }
}
```


### `ActiveServices.startServiceLocked()`方法
```java
ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
        int callingPid, int callingUid, String callingPackage, final int userId)
        throws TransactionTooLargeException {

    final boolean callerFg;
    if (caller != null) {
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (callerApp == null) {
            throw new SecurityException(...);
        }
        callerFg = callerApp.setSchedGroup != ProcessList.SCHED_GROUP_BACKGROUND;
    } else {
        callerFg = true;
    }


    ServiceLookupResult res =
        retrieveServiceLocked(service, resolvedType, callingPackage,
                callingPid, callingUid, userId, true, callerFg, false);
    if (res == null) {
        return null;
    }
    if (res.record == null) {
        return new ComponentName("!", res.permission != null
                ? res.permission : "private to package");
    }

    ServiceRecord r = res.record;

    if (!mAm.mUserController.exists(r.userId)) {
        return null;
    }

    if (!r.startRequested) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Before going further -- if this app is not allowed to run in the
            // background, then at this point we aren't going to let it period.
            final int allowed = mAm.checkAllowBackgroundLocked(
                    r.appInfo.uid, r.packageName, callingPid, true);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    NeededUriGrants neededGrants = mAm.checkGrantUriPermissionFromIntentLocked(
            callingUid, r.packageName, service, service.getFlags(), null, r.userId);

    // If permissions need a review before any of the app components can run,
    // we do not start the service and launch a review activity if the calling app
    // is in the foreground passing it a pending intent to start the service when
    // review is completed.
    if (mAm.mPermissionReviewRequired || Build.PERMISSIONS_REVIEW_REQUIRED) {
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r, callingPackage,
                callingUid, service, callerFg, userId)) {
            return null;
        }
    }

    if (unscheduleServiceRestartLocked(r, callingUid, false)) {
    }
    r.lastActivity = SystemClock.uptimeMillis();
    r.startRequested = true;
    r.delayedStop = false;
    r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
            service, neededGrants));

    final ServiceMap smap = getServiceMap(r.userId);
    boolean addToStarting = false;
    if (!callerFg && r.app == null
            && mAm.mUserController.hasStartedUserState(r.userId)) {
        ProcessRecord proc = mAm.getProcessRecordLocked(r.processName, r.appInfo.uid, false);
        if (proc == null || proc.curProcState > ActivityManager.PROCESS_STATE_RECEIVER) {
            // If this is not coming from a foreground caller, then we may want
            // to delay the start if there are already other background services
            // that are starting.  This is to avoid process start spam when lots
            // of applications are all handling things like connectivity broadcasts.
            // We only do this for cached processes, because otherwise an application
            // can have assumptions about calling startService() for a service to run
            // in its own process, and for that process to not be killed before the
            // service is started.  This is especially the case for receivers, which
            // may start a service in onReceive() to do some additional work and have
            // initialized some global state as part of that.
            if (r.delayed) {
                // This service is already scheduled for a delayed start; just leave
                // it still waiting.
                return r.name;
            }
            if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                // Something else is starting, delay!
                smap.mDelayedStartList.add(r);
                r.delayed = true;
                return r.name;
            }
            addToStarting = true;
        } else if (proc.curProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
            // We slightly loosen when we will enqueue this new service as a background
            // starting service we are waiting for, to also include processes that are
            // currently running other services or receivers.
            addToStarting = true;
        }
    }

    return startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
}
```


### `ActiveServices.startServiceInnerLocked()`方法
```java
ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r,
        boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
    ServiceState stracker = r.getTracker();
    if (stracker != null) {
        stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
    }
    r.callStart = false;
    synchronized (r.stats.getBatteryStats()) {
        r.stats.startRunningLocked();
    }
    String error = bringUpServiceLocked(r, service.getFlags(), callerFg, false, false);
    if (error != null) {
        return new ComponentName("!!", error);
    }

    if (r.startRequested && addToStarting) {
        boolean first = smap.mStartingBackground.size() == 0;
        smap.mStartingBackground.add(r);
        r.startingBgTimeout = SystemClock.uptimeMillis() + BG_START_TIMEOUT;
        if (DEBUG_DELAYED_SERVICE) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r, here);
        } else if (DEBUG_DELAYED_STARTS) {
            Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r);
        }
        if (first) {
            smap.rescheduleDelayedStarts();
        }
    } else if (callerFg) {
        smap.ensureNotStartingBackground(r);
    }

    return r.name;
}
```



## 启动Service--`bindService()`方式流程图
```puml
ContextImpl -> ContextImpl : bindService()
ContextImpl -> ContextImpl : bindServiceCommon()
ContextImpl -> AMS : bindService()
```


### `ContextImpl.bindService()`方法
```java
@Override
public boolean bindService(Intent service, ServiceConnection conn,
        int flags) {
    warnIfCallingFromSystemProcess();
    return bindServiceCommon(service, conn, flags, mMainThread.getHandler(),
            Process.myUserHandle());
}
```

### `ContextImpl.bindServiceCommon()`方法
```java
private boolean bindServiceCommon(Intent service, ServiceConnection conn, int flags, Handler
        handler, UserHandle user) {
    IServiceConnection sd;
    if (conn == null) {
        throw new IllegalArgumentException("connection is null");
    }
    if (mPackageInfo != null) {
        sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
    } else {
        throw new RuntimeException("Not supported in system context");
    }
    validateServiceIntent(service);
    try {
        IBinder token = getActivityToken();
        if (token == null && (flags&BIND_AUTO_CREATE) == 0 && mPackageInfo != null
                && mPackageInfo.getApplicationInfo().targetSdkVersion
                < android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            flags |= BIND_WAIVE_PRIORITY;
        }
        service.prepareToLeaveProcess(this);
        int res = ActivityManagerNative.getDefault().bindService(
            mMainThread.getApplicationThread(), getActivityToken(), service,
            service.resolveTypeIfNeeded(getContentResolver()),
            sd, flags, getOpPackageName(), user.getIdentifier());
        if (res < 0) {
            throw new SecurityException(...);
        }
        return res != 0;
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```

### `ActivityManagerService.bindService()`方法
```java
public int bindService(IApplicationThread caller, IBinder token, Intent service,
        String resolvedType, IServiceConnection connection, int flags, String callingPackage,
        int userId) throws TransactionTooLargeException {
    enforceNotIsolatedCaller("bindService");
    // 不允许有文件描述符
    if (service != null && service.hasFileDescriptors() == true) {
        throw new IllegalArgumentException("File descriptors passed in Intent");
    }

    if (callingPackage == null) {
        throw new IllegalArgumentException("callingPackage cannot be null");
    }

    synchronized(this) {
        //调用ActiveServices.bindServiceLocked()方法
        return mServices.bindServiceLocked(caller, token, service,
                resolvedType, connection, flags, callingPackage, userId);
    }
}
```

### `ActiveServices.bindServiceLocked()`方法
```java
int bindServiceLocked(IApplicationThread caller, IBinder token, Intent service,
        String resolvedType, final IServiceConnection connection, int flags,
        String callingPackage, final int userId) throws TransactionTooLargeException {
    final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
    if (callerApp == null) {
        throw new SecurityException(...);
    }

    ActivityRecord activity = null;
    if (token != null) {
        activity = ActivityRecord.isInStackLocked(token);
        if (activity == null) {
            return 0;
        }
    }

    int clientLabel = 0;
    PendingIntent clientIntent = null;
    final boolean isCallerSystem = callerApp.info.uid == Process.SYSTEM_UID;

    if (isCallerSystem) {
        // Hacky kind of thing -- allow system stuff to tell us
        // what they are, so we can report this elsewhere for
        // others to know why certain services are running.
        service.setDefusable(true);
        clientIntent = service.getParcelableExtra(Intent.EXTRA_CLIENT_INTENT);
        if (clientIntent != null) {
            clientLabel = service.getIntExtra(Intent.EXTRA_CLIENT_LABEL, 0);
            if (clientLabel != 0) {
                // There are no useful extras in the intent, trash them.
                // System code calling with this stuff just needs to know
                // this will happen.
                service = service.cloneFilter();
            }
        }
    }

    if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
        mAm.enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "BIND_TREAT_LIKE_ACTIVITY");
    }

    if ((flags & Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0 && !isCallerSystem) {
        throw new SecurityException(...);
    }

    final boolean callerFg = callerApp.setSchedGroup != ProcessList.SCHED_GROUP_BACKGROUND;
    final boolean isBindExternal = (flags & Context.BIND_EXTERNAL_SERVICE) != 0;

    ServiceLookupResult res =
        retrieveServiceLocked(service, resolvedType, callingPackage, Binder.getCallingPid(),
                Binder.getCallingUid(), userId, true, callerFg, isBindExternal);
    if (res == null) {
        return 0;
    }
    if (res.record == null) {
        return -1;
    }
    ServiceRecord s = res.record;

    boolean permissionsReviewRequired = false;

    // If permissions need a review before any of the app components can run,
    // we schedule binding to the service but do not start its process, then
    // we launch a review activity to which is passed a callback to invoke
    // when done to start the bound service's process to completing the binding.
    if (mAm.mPermissionReviewRequired || Build.PERMISSIONS_REVIEW_REQUIRED) {
        if (mAm.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                s.packageName, s.userId)) {

            permissionsReviewRequired = true;

            // Show a permission review UI only for binding from a foreground app
            if (!callerFg) {
                return 0;
            }

            final ServiceRecord serviceRecord = s;
            final Intent serviceIntent = service;

            RemoteCallback callback = new RemoteCallback(
                    new RemoteCallback.OnResultListener() {
                @Override
                public void onResult(Bundle result) {
                    synchronized(mAm) {
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            if (!mPendingServices.contains(serviceRecord)) {
                                return;
                            }
                            // If there is still a pending record, then the service
                            // binding request is still valid, so hook them up. We
                            // proceed only if the caller cleared the review requirement
                            // otherwise we unbind because the user didn't approve.
                            if (!mAm.getPackageManagerInternalLocked()
                                    .isPermissionsReviewRequired(
                                            serviceRecord.packageName,
                                            serviceRecord.userId)) {
                                try {
                                    bringUpServiceLocked(serviceRecord,
                                            serviceIntent.getFlags(),
                                            callerFg, false, false);
                                } catch (RemoteException e) {
                                    /* ignore - local call */
                                }
                            } else {
                                unbindServiceLocked(connection);
                            }
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                }
            });

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, s.packageName);
            intent.putExtra(Intent.EXTRA_REMOTE_CALLBACK, callback);

            mAm.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAm.mContext.startActivityAsUser(intent, new UserHandle(userId));
                }
            });
        }
    }

    final long origId = Binder.clearCallingIdentity();

    try {
        if (unscheduleServiceRestartLocked(s, callerApp.info.uid, false)) {
        }

        if ((flags&Context.BIND_AUTO_CREATE) != 0) {
            s.lastActivity = SystemClock.uptimeMillis();
            if (!s.hasAutoCreateConnections()) {
                // This is the first binding, let the tracker know.
                ServiceState stracker = s.getTracker();
                if (stracker != null) {
                    stracker.setBound(true, mAm.mProcessStats.getMemFactorLocked(),
                            s.lastActivity);
                }
            }
        }

        mAm.startAssociationLocked(callerApp.uid, callerApp.processName, callerApp.curProcState,
                s.appInfo.uid, s.name, s.processName);

        AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
        ConnectionRecord c = new ConnectionRecord(b, activity,
                connection, flags, clientLabel, clientIntent);

        IBinder binder = connection.asBinder();
        ArrayList<ConnectionRecord> clist = s.connections.get(binder);
        if (clist == null) {
            clist = new ArrayList<ConnectionRecord>();
            s.connections.put(binder, clist);
        }
        clist.add(c);
        b.connections.add(c);
        if (activity != null) {
            if (activity.connections == null) {
                activity.connections = new HashSet<ConnectionRecord>();
            }
            activity.connections.add(c);
        }
        b.client.connections.add(c);
        if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
            b.client.hasAboveClient = true;
        }
        if ((c.flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
            s.whitelistManager = true;
        }
        if (s.app != null) {
            updateServiceClientActivitiesLocked(s.app, c, true);
        }
        clist = mServiceConnections.get(binder);
        if (clist == null) {
            clist = new ArrayList<ConnectionRecord>();
            mServiceConnections.put(binder, clist);
        }
        clist.add(c);

        if ((flags&Context.BIND_AUTO_CREATE) != 0) {
            s.lastActivity = SystemClock.uptimeMillis();
            if (bringUpServiceLocked(s, service.getFlags(), callerFg, false,
                    permissionsReviewRequired) != null) {
                return 0;
            }
        }

        if (s.app != null) {
            if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                s.app.treatLikeActivity = true;
            }
            if (s.whitelistManager) {
                s.app.whitelistManager = true;
            }
            // This could have made the service more important.
            mAm.updateLruProcessLocked(s.app, s.app.hasClientActivities
                    || s.app.treatLikeActivity, b.client);
            mAm.updateOomAdjLocked(s.app);
        }

        if (s.app != null && b.intent.received) {
            // Service is already running, so we can immediately
            // publish the connection.
            try {
                c.conn.connected(s.name, b.intent.binder);
            } catch (Exception e) {}

            // If this is the first app connected back to this binding,
            // and the service had previously asked to be told when
            // rebound, then do so.
            if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                requestServiceBindingLocked(s, b.intent, callerFg, true);
            }
        } else if (!b.intent.requested) {
            requestServiceBindingLocked(s, b.intent, callerFg, false);
        }

        getServiceMap(s.userId).ensureNotStartingBackground(s);

    } finally {
        Binder.restoreCallingIdentity(origId);
    }

    return 1;
}
```
