## Process 管理

在 Android 中，进程只是一个运行组件的容器，当系统需要运行一个组件时，启动包含它的进程，当组件不再使用时，进程也会被关闭。虽然在 Android 的应用开发中不再强调进程的概念，但是在 AMS 中还必须管理和调度进程。

**ActivityManagerService 对象进程的管理主要体现在两个方面：**

* 动态的调整在 mLruProcesses 列表的位置

* 调整进程的 oom_adj 的值



---

### 启动进程

AMS 中启动一个进程调用的是 `addAppLocked()` 方法

#### `addAppLocked()` 方法

```java

// ProcessRecord 类用于表示特定的进程的全部信息，可以理解为一个进程的实体类（数据结构）

final ProcessRecord addAppLocked(ApplicationInfo info, boolean isolated,
       String abiOverride) {
   ProcessRecord app;

   // isolated 为 true 表示要启动一个新的进程

   if (!isolated) {

       // 调用 getProcessRecordLocked() 方法，在已经启动的进程列表中查找
       app = getProcessRecordLocked(info.processName, info.uid, true);
   } else {
       app = null;
   }

   if (app == null) {

       // 调用 newProcessRecordLocked() 方法，创建一个 ProcessRecord 对象
       app = newProcessRecordLocked(info, null, isolated, 0);

       // 调用 updateLruProcessLocked() 方法，更新运行中的进程的状态
       updateLruProcessLocked(app, false, null);

       // 调用 updateOomAdjLocked() 方法，更新进程的优先级
       updateOomAdjLocked();
   }

   // This package really, really can not be stopped.
   try {
       AppGlobals.getPackageManager().setPackageStoppedState(
               info.packageName, false, UserHandle.getUserId(app.uid));
   } catch (RemoteException e) {
   } catch (IllegalArgumentException e) {
       Slog.w(TAG, "Failed trying to unstop package "
               + info.packageName + ": " + e);
   }

   if ((info.flags & PERSISTENT_MASK) == PERSISTENT_MASK) {
       app.persistent = true;
       app.maxAdj = ProcessList.PERSISTENT_PROC_ADJ;
   }
   if (app.thread == null && mPersistentStartingProcesses.indexOf(app) < 0) {
       mPersistentStartingProcesses.add(app);

       // 调用 startProcessLocked() 方法，启动进程
       startProcessLocked(app, "added application", app.processName, abiOverride,
               null /* entryPoint */, null /* entryPointArgs */);
   }

   return app;
}

```

</br>



#### `startProcessLocked()` 方法

该方法中很多步骤都会记录时间，超过 50ms 会打印log

```java

private final void startProcessLocked(ProcessRecord app, String hostingType,
       String hostingNameStr, String abiOverride, String entryPoint, String[] entryPointArgs) {
   long startTime = SystemClock.elapsedRealtime();
   if (app.pid > 0 && app.pid != MY_PID) {

       // 检查启动时间，超过 50ms 打印log
       checkTime(startTime, "startProcess: removing from pids map");
       synchronized (mPidsSelfLocked) {

           // 移除进程 id，防止重复
           mPidsSelfLocked.remove(app.pid);

           // 清除 PROC_START_TIMEOUT_MSG 消息，下面会利用这条消息计算启动时间
           mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
       }
       checkTime(startTime, "startProcess: done removing from pids map");
       app.setPid(0);
   }

   if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG_PROCESSES,
           "startProcessLocked removing on hold: " + app);
   mProcessesOnHold.remove(app);

   checkTime(startTime, "startProcess: starting to update cpu stats");

   // 调用 updateCpuStats() 方法
   updateCpuStats();
   checkTime(startTime, "startProcess: done updating cpu stats");

   try {
       try {
           final int userId = UserHandle.getUserId(app.uid);
           AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, userId);
       } catch (RemoteException e) {
           throw e.rethrowAsRuntimeException();
       }

       int uid = app.uid;
       int[] gids = null;
       int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
       if (!app.isolated) {
           int[] permGids = null;
           try {
               checkTime(startTime, "startProcess: getting gids from package manager");
               final IPackageManager pm = AppGlobals.getPackageManager();
               permGids = pm.getPackageGids(app.info.packageName,
                       MATCH_DEBUG_TRIAGED_MISSING, app.userId);

               // 检查进程权限，确定它是否能看见所有用户的存储空间

                MountServiceInternal mountServiceInternal = LocalServices.getService(
                       MountServiceInternal.class);
               mountExternal = mountServiceInternal.getExternalStorageMountMode(uid,
                       app.info.packageName);
           } catch (RemoteException e) {
               throw e.rethrowAsRuntimeException();
           }

           // 添加共享应用程序和配置文件GID，以便应用程序可以共享一些资源，如共享库和访问用户范围的资源
           if (ArrayUtils.isEmpty(permGids)) {
               gids = new int[2];
           } else {
               gids = new int[permGids.length + 2];
               System.arraycopy(permGids, 0, gids, 2, permGids.length);
           }
           gids[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
           gids[1] = UserHandle.getUserGid(UserHandle.getUserId(uid));
       }
       checkTime(startTime, "startProcess: building args");

       // 如果是工厂测试模式
       if (mFactoryTest != FactoryTest.FACTORY_TEST_OFF) {
           if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL
                   && mTopComponent != null
                   && app.processName.equals(mTopComponent.getPackageName())) {
               uid = 0;
           }
           if (mFactoryTest == FactoryTest.FACTORY_TEST_HIGH_LEVEL
                   && (app.info.flags&ApplicationInfo.FLAG_FACTORY_TEST) != 0) {
               uid = 0;
           }
       }
       int debugFlags = 0;
       if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
           debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
           // 也打开CheckJNI为可调试应用程序。

           debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
       }
       // 如果应用程序的清单请求安全模式或系统以安全模式启动，请以安全模式运行应用程序。

       if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 ||
           mSafeMode == true) {
           debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
       }
       if ("1".equals(SystemProperties.get("debug.checkjni"))) {
           debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
       }
       String genDebugInfoProperty = SystemProperties.get("debug.generate-debug-info");
       if ("true".equals(genDebugInfoProperty)) {
           debugFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO;
       }
       if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
           debugFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
       }
       if ("1".equals(SystemProperties.get("debug.assert"))) {
           debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
       }
       if (mNativeDebuggingApp != null && mNativeDebuggingApp.equals(app.processName)) {
           //启用 native 调试器所需的所有调试标志。

           debugFlags |= Zygote.DEBUG_ALWAYS_JIT;          // 不需要解释任何东西
           debugFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO; // 生成调试信息
           debugFlags |= Zygote.DEBUG_NATIVE_DEBUGGABLE;   // 禁用优化
           mNativeDebuggingApp = null;
       }

       String requiredAbi = (abiOverride != null) ? abiOverride : app.info.primaryCpuAbi;
       if (requiredAbi == null) {
           requiredAbi = Build.SUPPORTED_ABIS[0];
       }

       String instructionSet = null;
       if (app.info.primaryCpuAbi != null) {
           instructionSet = VMRuntime.getInstructionSet(app.info.primaryCpuAbi);
       }

       app.gids = gids;
       app.requiredAbi = requiredAbi;
       app.instructionSet = instructionSet;

       // 启动进程，如果成功将返回一个包含新进程的PID的结果，否则抛出一个RuntimeException。

       boolean isActivityProcess = (entryPoint == null);
       if (entryPoint == null) entryPoint = "android.app.ActivityThread";
       Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Start proc: " +
               app.processName);
       checkTime(startTime, "startProcess: asking zygote to start proc");

       // 调用 Process.start() 方法，启动应用
       Process.ProcessStartResult startResult = Process.start(entryPoint,
               app.processName, uid, uid, gids, debugFlags, mountExternal,
               app.info.targetSdkVersion, app.info.seinfo, requiredAbi, instructionSet,
               app.info.dataDir, entryPointArgs);
       checkTime(startTime, "startProcess: returned from zygote!");
       Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

       if (app.isolated) {
           mBatteryStatsService.addIsolatedUid(app.uid, app.info.uid);
       }
       mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);
       checkTime(startTime, "startProcess: done updating battery stats");

       EventLog.writeEvent(EventLogTags.AM_PROC_START,
               UserHandle.getUserId(uid), startResult.pid, uid,
               app.processName, hostingType,
               hostingNameStr != null ? hostingNameStr : "");

       try {
           AppGlobals.getPackageManager().logAppProcessStartIfNeeded(app.processName, app.uid,
                   app.info.seinfo, app.info.sourceDir, startResult.pid);
       } catch (RemoteException ex) {}

       if (app.persistent) {
           Watchdog.getInstance().processStarted(app.processName, startResult.pid);
       }

       checkTime(startTime, "startProcess: building log message");
       // ... 打印 log
       app.setPid(startResult.pid);
       app.usingWrapper = startResult.usingWrapper;
       app.removed = false;
       app.killed = false;
       app.killedByAm = false;
       checkTime(startTime, "startProcess: starting to update pids map");
       synchronized (mPidsSelfLocked) {

           // 发送一个定时消息，时间到了，应用还没启动完成就会出现 ANR

           this.mPidsSelfLocked.put(startResult.pid, app);

           if (isActivityProcess) {
               Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
               msg.obj = app;

               // 如果启动进程返回结果中的usingWrapper为true，超时时间为1200s，否则为10s
               mHandler.sendMessageDelayed(msg, startResult.usingWrapper
                       ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
           }
       }
       checkTime(startTime, "startProcess: done updating pids map");
   } catch (RuntimeException e) {
       Slog.e(TAG, "Failure starting process " + app.processName, e);
       forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid), false,
               false, true, false, false, UserHandle.getUserId(app.userId), "start failure");
   }
}

```

</br>



---

### 调整进程在 mLruProcesses 列表中的位置

AMS 代码中，经常调用 `updateLruProcessLocked()` 方法来调整某个进程在 **mLruProcesses** 列表中的位置，该列表是最近最少使用的进程列表。每当进程中的 Activity 或 Service 发生变化时，意味着进程发生了活动，因此要调用该方法调整该列表中进程的位置。

在 **mLruProcesses** 列表中，最近活动过的进程总是位于最高位置，同时拥有 Activity 的进程位置总是高于只有 Service 的进程位置

AMS 中的成员变量 **mLruProcessActivityStart** 指向列表中位置最高的带有 Activity 的进程

**mLruProcessServiceStart** 指向列表中位置最高的没有 Activity 的进程

</br>



#### `updateLruProcessLocked()` 方法

该方法中调整进程位置很重要的一个依据是该进程有没有活动的 Activity，除了调整该进程本身之外，还需要调用 updateLruProcessInternalLocked() 方法调整与该进程 service 或 ContentProvider 相关的进程；

如果一个进程有 Activity，通常会将它插到队列位置的最后，否则，只会将它放到所有没有 Activity 的进程的前面，这个位置正是变量 mLruProcessServiceStart 所指向的索引。

```java

final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
       ProcessRecord client) {

   // app.activities.size() > 0 表示本进程有活动的 activity

   // app.hasClientActivities 为 true 表示某个绑定了本进程中的 Service 的客户进程有 activity

   // app.treatLikeActivity 为 true 表示 Service 启动时带有 BIND_TREAT_LIKE_ACTIVITY
   final boolean hasActivity = app.activities.size() > 0 || app.hasClientActivities
           || app.treatLikeActivity;
   final boolean hasService = false; // not impl yet. app.services.size() > 0;

   if (!activityChange && hasActivity) {
       // 该进程具有activity，因此我们只允许基于activity的调整来移动它。

       //它应该保留在列表的前面，其他具有activity的进程，我们不希望改变它们的顺序，除非由于activity操作。

       return;
   }
   // 方法没调用一次，该变量加 1
   mLruSeq++;

   // 更新 lastActivityTime 中的时间
   final long now = SystemClock.uptimeMillis();
   app.lastActivityTime = now;

   // 如果继承已经初始化，而且在 mLruProcesses 列表的最后，这样本方法没什么可做的，退出
   if (hasActivity) {
       final int N = mLruProcesses.size();
       if (N > 0 && mLruProcesses.get(N-1) == app) {
           if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top activity: " + app);
           return;
       }
   } else {

       // 如果进程中没有 activity，且在 mLruProcesses 列表中合适的位置，退出
       if (mLruProcessServiceStart > 0
               && mLruProcesses.get(mLruProcessServiceStart-1) == app) {
           if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top other: " + app);
           return;
       }
   }

   int lrui = mLruProcesses.lastIndexOf(app);

   if (app.persistent && lrui >= 0) {
       // 带有 persistent 标志的进程不需要调整，只需要在列表中就行
       if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, persistent: " + app);
       return;
   }

   // 如果进程已经存在，调整 mLruProcessActivityStart 和 mLruProcessServiceStart，并且先从 mProcesses 列表中移除它。

   if (lrui >= 0) {
       if (lrui < mLruProcessActivityStart) {
           mLruProcessActivityStart--;
       }
       if (lrui < mLruProcessServiceStart) {
           mLruProcessServiceStart--;
       }
       mLruProcesses.remove(lrui);
   }

   int nextIndex;
   if (hasActivity) {
       final int N = mLruProcesses.size();
       if (app.activities.size() == 0 && mLruProcessActivityStart < (N - 1)) {
           // 进程中没有 Activity，但是 service 客户进程中有 activity，将进程插入到列表倒数第二个
           mLruProcesses.add(N - 1, app);
           // 如果从倒数第三项开始连续有进程的 uid 和插入的进程 uid 相同，把他们位置向上移动
           final int uid = app.info.uid;
           for (int i = N - 2; i > mLruProcessActivityStart; i--) {
               ProcessRecord subProc = mLruProcesses.get(i);
               if (subProc.info.uid == uid) {
                   if (mLruProcesses.get(i - 1).info.uid != uid) {
                       ProcessRecord tmp = mLruProcesses.get(i);
                       mLruProcesses.set(i, mLruProcesses.get(i - 1));
                       mLruProcesses.set(i - 1, tmp);
                       i--;
                   }
               } else {
                   break;
               }
           }
       } else {
           // 进程中有activity，加入到最后一项
           mLruProcesses.add(app);
       }
       nextIndex = mLruProcessServiceStart;
   } else if (hasService) {

       // hasService 总是为 false，这段不执行
       mLruProcesses.add(mLruProcessActivityStart, app);
       nextIndex = mLruProcessServiceStart;
       mLruProcessActivityStart++;
   } else  {

       // 如果进程只有 service，将进程插入到 mLruProcessServiceStart 指向的位置

       int index = mLruProcessServiceStart;
       if (client != null) {
           // 如果有一个客户端，不要让该进程在列表中比该客户端更高。client 多数为 null

           int clientIndex = mLruProcesses.lastIndexOf(client);
           if (clientIndex <= lrui) {
               // 不要允许客户端索引限制将其在列表中比它已经更远的位置推。

               clientIndex = lrui;
           }
           if (clientIndex >= 0 && index > clientIndex) {
               index = clientIndex;
           }
       }
       mLruProcesses.add(index, app);
       nextIndex = index-1;
       mLruProcessActivityStart++;
       mLruProcessServiceStart++;
   }

   // 调整将和本进程的 service 关联的客户进程位置
   for (int j=app.connections.size()-1; j>=0; j--) {
       ConnectionRecord cr = app.connections.valueAt(j);
       if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
               && cr.binding.service.app != null
               && cr.binding.service.app.lruSeq != mLruSeq
               && !cr.binding.service.app.persistent) {

           // 调用 updateLruProcessInternalLocked() 方法
           nextIndex = updateLruProcessInternalLocked(cr.binding.service.app, now, nextIndex,
                   "service connection", cr, app);
       }
   }

   // 调整和本进程 ContentProvider 关联的客户端进程的位置
   for (int j=app.conProviders.size()-1; j>=0; j--) {
       ContentProviderRecord cpr = app.conProviders.get(j).provider;
       if (cpr.proc != null && cpr.proc.lruSeq != mLruSeq && !cpr.proc.persistent) {
           nextIndex = updateLruProcessInternalLocked(cpr.proc, now, nextIndex,
                   "provider reference", cpr, app);
       }
   }
}

```

</br>



#### `updateLruProcessInternalLocked()` 方法

该方法主要用于调整和被调整的进程Service 或 ContentProvider 相关的进程

```java

private int updateLruProcessInternalLocked(ProcessRecord app, long now, int index,
       String what, Object obj, ProcessRecord srcApp) {
   app.lastActivityTime = now;

   if (app.activities.size() > 0) {
       // 如果有 Activity，不用调整位置，退出
       return index;
   }

   int lrui = mLruProcesses.lastIndexOf(app);
   if (lrui < 0) {

       // 如果进程不再 mLruProcesses 列表中，退出
       return index;
   }

   if (lrui >= index) {
       // 如果进程目前的位置高于要调整的位置，退出
       return index;
   }

   if (lrui >= mLruProcessActivityStart) {
       // 如果进程目前的位置比有 Activity 的进程还高，退出
       return index;
   }

   // 从列表中移除进程，并将它插入到 index-1 的位置
   mLruProcesses.remove(lrui);
   if (index > 0) {
       index--;
   }
   // 返回进程目前的位置
   mLruProcesses.add(index, app);
   return index;
}

```

</br>



---

### 调整进程 oom_adj 值

AMS 中调整进程 oom_adj 值的方法是 `updateOomAdjLocked()`

该方法通过调用 `computeOomAdjLocked()` 方法来计算 **oom_adj** 的值

#### `updateOomAdjLocked()` 方法

```java

final void updateOomAdjLocked() {

   // 调用 resumedAppLocked() 方法，获取位于前台的 Activity 和它所在的进程
   final ActivityRecord TOP_ACT = resumedAppLocked();
   final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
   final long now = SystemClock.uptimeMillis();
   final long nowElapsed = SystemClock.elapsedRealtime();
   final long oldTime = now - ProcessList.MAX_EMPTY_TIME;
   final int N = mLruProcesses.size();

   if (false) {
       RuntimeException e = new RuntimeException();
       e.fillInStackTrace();
       Slog.i(TAG, "updateOomAdj: top=" + TOP_ACT, e);
   }

   // Reset state in all uid records.
   for (int i=mActiveUids.size()-1; i>=0; i--) {
       final UidRecord uidRec = mActiveUids.valueAt(i);
       uidRec.reset();
   }

   mStackSupervisor.rankTaskLayersIfNeeded();

   mAdjSeq++;
   mNewNumServiceProcs = 0;
   mNewNumAServiceProcs = 0;

   final int emptyProcessLimit;
   final int cachedProcessLimit;
   if (mProcessLimit <= 0) {
       emptyProcessLimit = cachedProcessLimit = 0;
   } else if (mProcessLimit == 1) {
       emptyProcessLimit = 1;
       cachedProcessLimit = 0;
   } else {
       emptyProcessLimit = ProcessList.computeEmptyProcessLimit(mProcessLimit);
       cachedProcessLimit = mProcessLimit - emptyProcessLimit;
   }

   // 计算 cached 进程的 slog 数
   int numSlots = (ProcessList.CACHED_APP_MAX_ADJ
           - ProcessList.CACHED_APP_MIN_ADJ + 1) / 2;

   // 计算 empty 进程的数量
   int numEmptyProcs = N - mNumNonCachedProcs - mNumCachedHiddenProcs;
   if (numEmptyProcs > cachedProcessLimit) {
       // 如果 empty 进程的数量超过 cached 进程的限制值，更新它为 cached 进程的限制值
       numEmptyProcs = cachedProcessLimit;
   }

   // 计算平均每个 slot 的 empty 进程数
   int emptyFactor = numEmptyProcs/numSlots;
   if (emptyFactor < 1) emptyFactor = 1;

   // 计算平均每个 slot 的 cached 进程数
   int cachedFactor = (mNumCachedHiddenProcs > 0 ? mNumCachedHiddenProcs : 1)/numSlots;
   if (cachedFactor < 1) cachedFactor = 1;
   int stepCached = 0;
   int stepEmpty = 0;
   int numCached = 0;
   int numEmpty = 0;
   int numTrimming = 0;

   mNumNonCachedProcs = 0;
   mNumCachedHiddenProcs = 0;
   // 第一次更新每个应用进程的 oom_adj 基于他们的当前状态

   int curCachedAdj = ProcessList.CACHED_APP_MIN_ADJ;
   int nextCachedAdj = curCachedAdj+1;
   int curEmptyAdj = ProcessList.CACHED_APP_MIN_ADJ;

   // empty 进程比 cached 进程的 oom_adj 的起始值大1
   int nextEmptyAdj = curEmptyAdj+2;

   // 从后往前遍历 mLruProcesses 列表中的进程
   for (int i=N-1; i>=0; i--) {
       ProcessRecord app = mLruProcesses.get(i);
       if (!app.killedByAm && app.thread != null) {
           app.procStateChanged = false;

           // 调用 computeOomAdjLocked() 方法，计算进程的 oom_adj 值
           computeOomAdjLocked(app, ProcessList.UNKNOWN_ADJ, TOP_APP, true, now);

           // 如果计算后的 oom_adj 的值大于等于系统定义的最大 oom_adj 的值（要么是cached进程，要么是 empty进程）
           if (app.curAdj >= ProcessList.UNKNOWN_ADJ) {
               switch (app.curProcState) {

                   // 如果 ProcessRecord.curProcState 是以下两种 case 属于 cached 进程
                   case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                   case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                       // 如果进程属于 cached 进程类型
                       app.curRawAdj = curCachedAdj;
                       app.curAdj = app.modifyRawOomAdj(curCachedAdj);

                       if (curCachedAdj != nextCachedAdj) {
                           stepCached++;

                           // 统计本级别的进程数，进入下一个级别
                           if (stepCached >= cachedFactor) {
                               stepCached = 0;
                               curCachedAdj = nextCachedAdj;
                               nextCachedAdj += 2;
                               if (nextCachedAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                   nextCachedAdj = ProcessList.CACHED_APP_MAX_ADJ;
                               }
                           }
                       }
                       break;
                   default:
                       // 其他进程都属于 empty 进程
                       app.curRawAdj = curEmptyAdj;
                       app.curAdj = app.modifyRawOomAdj(curEmptyAdj);

                       if (curEmptyAdj != nextEmptyAdj) {
                           stepEmpty++;

                           // 统计本级别的进程数，进入下一个级别
                           if (stepEmpty >= emptyFactor) {
                               stepEmpty = 0;
                               curEmptyAdj = nextEmptyAdj;
                               nextEmptyAdj += 2;
                               if (nextEmptyAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                   nextEmptyAdj = ProcessList.CACHED_APP_MAX_ADJ;
                               }
                           }
                       }
                       break;
               }
           }
           // 调用 applyOomAdjLocked() 方法，更新进程的各种 oom_adj 值
           applyOomAdjLocked(app, true, now, nowElapsed);

           // 统计进程类型的数量
           switch (app.curProcState) {
               case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
               case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                   mNumCachedHiddenProcs++;
                   numCached++;
                   if (numCached > cachedProcessLimit) {

                       // 调用 ProcessRecord.kill() 方法杀死进程
                       app.kill("cached #" + numCached, true);
                   }
                   break;
               case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                   if (numEmpty > ProcessList.TRIM_EMPTY_APPS
                           && app.lastActivityTime < oldTime) {
                       app.kill("empty for "
                               + ((oldTime + ProcessList.MAX_EMPTY_TIME - app.lastActivityTime)
                               / 1000) + "s", true);
                   } else {
                       numEmpty++;
                       if (numEmpty > emptyProcessLimit) {
                           app.kill("empty #" + numEmpty, true);
                       }
                   }
                   break;
               default:
                   mNumNonCachedProcs++;
                   break;
           }

           if (app.isolated && app.services.size() <= 0) {
               app.kill("isolated not needed", true);
           } else {
               final UidRecord uidRec = app.uidRecord;
               if (uidRec != null && uidRec.curProcState > app.curProcState) {
                   uidRec.curProcState = app.curProcState;
               }
           }

           if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                   && !app.killedByAm) {
               numTrimming++;
           }
       }
   }

   mNumServiceProcs = mNewNumServiceProcs;

   final int numCachedAndEmpty = numCached + numEmpty;
   int memFactor;
   if (numCached <= ProcessList.TRIM_CACHED_APPS
           && numEmpty <= ProcessList.TRIM_EMPTY_APPS) {
       if (numCachedAndEmpty <= ProcessList.TRIM_CRITICAL_THRESHOLD) {
           memFactor = ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
       } else if (numCachedAndEmpty <= ProcessList.TRIM_LOW_THRESHOLD) {
           memFactor = ProcessStats.ADJ_MEM_FACTOR_LOW;
       } else {
           memFactor = ProcessStats.ADJ_MEM_FACTOR_MODERATE;
       }
   } else {
       memFactor = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
   }

   if (memFactor > mLastMemoryLevel) {
       if (!mAllowLowerMemLevel || mLruProcesses.size() >= mLastNumProcesses) {
           memFactor = mLastMemoryLevel;
       }
   }
   if (memFactor != mLastMemoryLevel) {
       EventLogTags.writeAmMemFactor(memFactor, mLastMemoryLevel);
   }
   mLastMemoryLevel = memFactor;
   mLastNumProcesses = mLruProcesses.size();
   boolean allChanged = mProcessStats.setMemFactorLocked(memFactor, !isSleepingLocked(), now);
   final int trackerMemFactor = mProcessStats.getMemFactorLocked();
   if (memFactor != ProcessStats.ADJ_MEM_FACTOR_NORMAL) {
       if (mLowRamStartTime == 0) {
           mLowRamStartTime = now;
       }
       int step = 0;
       int fgTrimLevel;
       switch (memFactor) {
           case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
               fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
               break;
           case ProcessStats.ADJ_MEM_FACTOR_LOW:
               fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
               break;
           default:
               fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
               break;
       }
       int factor = numTrimming/3;
       int minFactor = 2;
       if (mHomeProcess != null) minFactor++;
       if (mPreviousProcess != null) minFactor++;
       if (factor < minFactor) factor = minFactor;
       int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
       for (int i=N-1; i>=0; i--) {
           ProcessRecord app = mLruProcesses.get(i);
           if (allChanged || app.procStateChanged) {
               setProcessTrackerStateLocked(app, trackerMemFactor, now);
               app.procStateChanged = false;
           }
           if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                   && !app.killedByAm) {
               if (app.trimMemoryLevel < curLevel && app.thread != null) {
                   try {
                       app.thread.scheduleTrimMemory(curLevel);
                   } catch (RemoteException e) {
                   }
                   if (false) {
                       if (curLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                               && app != mHomeProcess && app != mPreviousProcess) {
                           mStackSupervisor.scheduleDestroyAllActivities(app, "trim");
                       }
                   }
               }
               app.trimMemoryLevel = curLevel;
               step++;
               if (step >= factor) {
                   step = 0;
                   switch (curLevel) {
                       case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                           curLevel = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                           break;
                       case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                           curLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                           break;
                   }
               }
           } else if (app.curProcState == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
               if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                       && app.thread != null) {
                   try {
                       app.thread.scheduleTrimMemory(
                               ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                   } catch (RemoteException e) {
                   }
               }
               app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
           } else {
               if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                       || app.systemNoUi) && app.pendingUiClean) {
                   final int level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                   if (app.trimMemoryLevel < level && app.thread != null) {
                       try {
                           app.thread.scheduleTrimMemory(level);
                       } catch (RemoteException e) {
                       }
                   }
                   app.pendingUiClean = false;
               }
               if (app.trimMemoryLevel < fgTrimLevel && app.thread != null) {
                   try {
                       app.thread.scheduleTrimMemory(fgTrimLevel);
                   } catch (RemoteException e) {
                   }
               }
               app.trimMemoryLevel = fgTrimLevel;
           }
       }
   } else {
       if (mLowRamStartTime != 0) {
           mLowRamTimeSinceLastIdle += now - mLowRamStartTime;
           mLowRamStartTime = 0;
       }
       for (int i=N-1; i>=0; i--) {
           ProcessRecord app = mLruProcesses.get(i);
           if (allChanged || app.procStateChanged) {
               setProcessTrackerStateLocked(app, trackerMemFactor, now);
               app.procStateChanged = false;
           }
           if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                   || app.systemNoUi) && app.pendingUiClean) {
               if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                       && app.thread != null) {
                   try {
                       app.thread.scheduleTrimMemory(
                               ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                   } catch (RemoteException e) {
                   }
               }
               app.pendingUiClean = false;
           }
           app.trimMemoryLevel = 0;
       }
   }

   if (mAlwaysFinishActivities) {
       mStackSupervisor.scheduleDestroyAllActivities(null, "always-finish");
   }

   if (allChanged) {
       requestPssAllProcsLocked(now, false, mProcessStats.isMemFactorLowered());
   }

   // Update from any uid changes.
   for (int i=mActiveUids.size()-1; i>=0; i--) {
       final UidRecord uidRec = mActiveUids.valueAt(i);
       int uidChange = UidRecord.CHANGE_PROCSTATE;
       if (uidRec.setProcState != uidRec.curProcState) {
           if (ActivityManager.isProcStateBackground(uidRec.curProcState)) {
               if (!ActivityManager.isProcStateBackground(uidRec.setProcState)) {
                   uidRec.lastBackgroundTime = nowElapsed;
                   if (!mHandler.hasMessages(IDLE_UIDS_MSG)) {
                       mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG, BACKGROUND_SETTLE_TIME);
                   }
               }
           } else {
               if (uidRec.idle) {
                   uidChange = UidRecord.CHANGE_ACTIVE;
                   uidRec.idle = false;
               }
               uidRec.lastBackgroundTime = 0;
           }
           uidRec.setProcState = uidRec.curProcState;
           enqueueUidChangeLocked(uidRec, -1, uidChange);
           noteUidProcessState(uidRec.uid, uidRec.curProcState);
       }
   }

   if (mProcessStats.shouldWriteNowLocked(now)) {
       mHandler.post(new Runnable() {
           @Override public void run() {
               synchronized (ActivityManagerService.this) {
                   mProcessStats.writeStateAsyncLocked();
               }
           }
       });
   }
}

```

</br>



---

### 计算进程 oom_adj 的值

**Step1：计算 oom_adj 的初始值，确定 FOREGROUND_APP_ADJ**

* 如果是当前进程（TOP_APP），即包含了当前显示的 Activity 的进程，则将它的 **oom_adj** 值设置为 **FOREGROUND_APP_ADJ**

* 如果进程有 **Instrumentation** 类的实例，说明这个进程正在测试中，把他的 **oom_adj** 值设置成 **FOREGROUND_APP_ADJ**

* 如果进程注册了广播接收器，把它的 **oom_adj** 值设置成 **FOREGROUND_APP_ADJ**

* 如果进程中有正在运行的 service 组件，把他的 **oom_adj** 值设置成 **FOREGROUND_APP_ADJ**

* 其他的进程先设置 **oom_adj** 值为参数 **cachedAdj**，在 **updateOomAdjLocked()** 方法中调用时传递的参数 cachedAdj 为 **UNKNOWN_ADJ**



**Step2：根据 Activity 向下调整 oom_adj 的值**

* 如果进程拥有可见的 Activity，将它的 **oom_adj** 值设置成 **VISIBLE_APP_ADJ**

* 如果进程有处于暂停或正在停止状态的 Activity，将它的 **oom_adj** 值设置成 **PERCEPTIBLE_APP_ADJ**

* 如果进程的 **oom_adj** 值大于 **PERCEPTIBLE_APP_ADJ**，但是进程中有设置为前台运行的 Service 或者进程被设置为前台运行，则将它的 **oom_adj** 值设置成 **PERCEPTIBLE_APP_ADJ**

* 如果进程是 **HeavyWeight** 进程，将它的 **oom_adj** 设置成 **HEAVY_WEIGHT_APP_ADJ**

* 如果进程是 **Home** 进程，将它的 **oom_adj** 设置成 **HOME_APP_ADJ**

* 如果进程是 **Previous** 进程（当前Activity的前一个Activity所在的进程），将它的 **oom_adj** 调整为 **PREVIOUS_APP_ADJ**

* 如果进程正在备份，将它的 **oom_adj** 值调整为 **BACKUP_APP_ADJ**



**Step3：根据 Service 向下调整 oom_adj 的值**

* 如果进程的 service 是显示要求启动的（Service 对象的 startRequested 为 true），而且进程还拥有 Activity，则将它的 oom_adj 值调整为 **SERVICE_ADJ**

* 重新计算和 Service 连接的其他进程的 oom_adj 的值，如果 Service 的连接标记中包含 BIND_ABOVE_CLIENT，则将当前进程的 oom_adj 值调整为客户进程的 oom_adj 值；如果标记中含有 BIND_NOT_VISIBLE，则将当前进程的 oom_adj 值调整为 PERCEPTIBLE_APP_ADJ，否则调整为 VISIBLE_APP_ADJ



**Step4：根据 ContentProvider 向下调整 oom_adj 的值**

* 如果 ContentProvider 连接的客户进程的 oom_adj 的值小于当前进程的值，将当前进程的 oom_adj 的值调整为客户进程的 oom_adj 的值

* 如果 ContentProvider 对象有一个应用进程和它连接，将当前进程的 **oom_adj** 值调整为 ** FOREGROUND_APP_ADJ**



**Step5： 最后调整**

* 如果计算得到的 oom_adj 的值大于该进程的最大值（maxAdj），将该进程的 **oom_adj** 调整为 **maxAdj** 的值





#### `computeOomAdjLocked()` 方法

```java


private final int computeOomAdjLocked(ProcessRecord app, int cachedAdj, ProcessRecord TOP_APP,
       boolean doingAll, long now) {
   if (mAdjSeq == app.adjSeq) {
       return app.curRawAdj;
   }

   if (app.thread == null) {
       app.adjSeq = mAdjSeq;
       app.curSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
       app.curProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
       return (app.curAdj=app.curRawAdj=ProcessList.CACHED_APP_MAX_ADJ);
   }

   app.adjTypeCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
   app.adjSource = null;
   app.adjTarget = null;
   app.empty = false;
   app.cached = false;

   final int activitiesSize = app.activities.size();

   if (app.maxAdj <= ProcessList.FOREGROUND_APP_ADJ) {
       app.adjType = "fixed";
       app.adjSeq = mAdjSeq;
       app.curRawAdj = app.maxAdj;
       app.foregroundActivities = false;
       app.curSchedGroup = ProcessList.SCHED_GROUP_DEFAULT;
       app.curProcState = ActivityManager.PROCESS_STATE_PERSISTENT;
       app.systemNoUi = true;
       if (app == TOP_APP) {
           app.systemNoUi = false;
           app.curSchedGroup = ProcessList.SCHED_GROUP_TOP_APP;
           app.adjType = "pers-top-activity";
       } else if (activitiesSize > 0) {
           for (int j = 0; j < activitiesSize; j++) {
               final ActivityRecord r = app.activities.get(j);
               if (r.visible) {
                   app.systemNoUi = false;
               }
           }
       }
       if (!app.systemNoUi) {
           app.curProcState = ActivityManager.PROCESS_STATE_PERSISTENT_UI;
       }
       return (app.curAdj=app.maxAdj);
   }

   app.systemNoUi = false;

   final int PROCESS_STATE_CUR_TOP = mTopProcessState;

   // 确定进程的重要性，从最重要到最不重要，分别适当的 oom_adj 值
   int adj;
   int schedGroup;
   int procState;
   boolean foregroundActivities = false;
   final ArraySet<BroadcastQueue> queues = new ArraySet<BroadcastQueue>();

   // 确定 FOREGROUND_APP_ADJ
   if (app == TOP_APP) {
       // mLruProcesses 列表中最后一个应用程序为前台应用程序
       adj = ProcessList.FOREGROUND_APP_ADJ;
       schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
       app.adjType = "top-activity";
       foregroundActivities = true;
       procState = PROCESS_STATE_CUR_TOP;
   } else if (app.instrumentationClass != null) {
       adj = ProcessList.FOREGROUND_APP_ADJ;
       schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
       app.adjType = "instrumentation";
       procState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;

   } else if (isReceivingBroadcastLocked(app, queues)) {

       // 调用 isReceivingBroadcastLocked() 方法
       adj = ProcessList.FOREGROUND_APP_ADJ;
       schedGroup = (queues.contains(mFgBroadcastQueue))
               ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
       app.adjType = "broadcast";
       procState = ActivityManager.PROCESS_STATE_RECEIVER;
   } else if (app.executingServices.size() > 0) {
       adj = ProcessList.FOREGROUND_APP_ADJ;
       schedGroup = app.execServicesFg ?
               ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
       app.adjType = "exec-service";
       procState = ActivityManager.PROCESS_STATE_SERVICE;
   } else {
       schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
       adj = cachedAdj;
       procState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
       app.cached = true;
       app.empty = true;
       app.adjType = "cch-empty";
   }

   // 没有前台，检查所有的 Activity
   if (!foregroundActivities && activitiesSize > 0) {
       int minLayer = ProcessList.VISIBLE_APP_LAYER_MAX;
       for (int j = 0; j < activitiesSize; j++) {
           final ActivityRecord r = app.activities.get(j);
           if (r.app != app) {
               if (r.app == null || (r.app.uid == app.uid)) {
                   r.app = app;
               } else {
                   continue;
               }
           }
           if (r.visible) {
               // 如果有可见的 Activity，将 oom_adj 调整为 VISIBLE_APP_ADJ
               if (adj > ProcessList.VISIBLE_APP_ADJ) {
                   adj = ProcessList.VISIBLE_APP_ADJ;
                   app.adjType = "visible";
               }
               if (procState > PROCESS_STATE_CUR_TOP) {
                   procState = PROCESS_STATE_CUR_TOP;
               }
               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
               app.cached = false;
               app.empty = false;
               foregroundActivities = true;
               if (r.task != null && minLayer > 0) {
                   final int layer = r.task.mLayerRank;
                   if (layer >= 0 && minLayer > layer) {
                       minLayer = layer;
                   }
               }
               break;

           // 如果 Activity 的状态为暂停状态，将 oom_adj 调整为 PERCEPTIBLE_APP_ADJ
           } else if (r.state == ActivityState.PAUSING || r.state == ActivityState.PAUSED) {
               if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                   adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                   app.adjType = "pausing";
               }
               if (procState > PROCESS_STATE_CUR_TOP) {
                   procState = PROCESS_STATE_CUR_TOP;
               }
               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
               app.cached = false;
               app.empty = false;
               foregroundActivities = true;

           // 如果 Activity 的状态为正在停止状态，将 oom_adj 调整为 PERCEPTIBLE_APP_ADJ

           } else if (r.state == ActivityState.STOPPING) {
               if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                   adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                   app.adjType = "stopping";
               }
               if (!r.finishing) {
                   if (procState > ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                       procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
                   }
               }
               app.cached = false;
               app.empty = false;
               foregroundActivities = true;
           } else {
               if (procState > ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                   procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
                   app.adjType = "cch-act";
               }
           }
       }
       if (adj == ProcessList.VISIBLE_APP_ADJ) {
           adj += minLayer;
       }
   }

   if (adj > ProcessList.PERCEPTIBLE_APP_ADJ
           || procState > ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
       if (app.foregroundServices) {
           // 有前台 service 用户知道这个应用程序，设置 oom_adj 为 PERCEPTIBLE_APP_ADJ
           adj = ProcessList.PERCEPTIBLE_APP_ADJ;
           procState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
           app.cached = false;
           app.adjType = "fg-service";
           schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
       } else if (app.forcingToForeground != null) {
           adj = ProcessList.PERCEPTIBLE_APP_ADJ;
           procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
           app.cached = false;
           app.adjType = "force-fg";
           app.adjSource = app.forcingToForeground;
           schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
       }
   }

   // 如果进程是 HeavyWeight 进程，设置 oom_adj 为 HEAVY_WEIGHT_APP_ADJ
   if (app == mHeavyWeightProcess) {
       if (adj > ProcessList.HEAVY_WEIGHT_APP_ADJ) {
           adj = ProcessList.HEAVY_WEIGHT_APP_ADJ;
           schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
           app.cached = false;
           app.adjType = "heavy";
       }
       if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
           procState = ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
       }
   }

   // 如果当前进程是 Home 进程，设置 oom_adj 为 HOME_APP_ADJ
   if (app == mHomeProcess) {
       if (adj > ProcessList.HOME_APP_ADJ) {
           adj = ProcessList.HOME_APP_ADJ;
           schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
           app.cached = false;
           app.adjType = "home";
       }
       if (procState > ActivityManager.PROCESS_STATE_HOME) {
           procState = ActivityManager.PROCESS_STATE_HOME;
       }
   }

   // 如果当前进程是 Previous 进程，设置 oom_adj 为 PREVIOUS_APP_ADJ
   if (app == mPreviousProcess && app.activities.size() > 0) {
       if (adj > ProcessList.PREVIOUS_APP_ADJ) {
           adj = ProcessList.PREVIOUS_APP_ADJ;
           schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
           app.cached = false;
           app.adjType = "previous";
       }
       if (procState > ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
           procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
       }
   }


   app.adjSeq = mAdjSeq;
   app.curRawAdj = adj;
   app.hasStartedServices = false;

   // 如果进程正在备份，将oom_adj 设置为 BACKUP_APP_ADJ
   if (mBackupTarget != null && app == mBackupTarget.app) {
       if (adj > ProcessList.BACKUP_APP_ADJ) {
           adj = ProcessList.BACKUP_APP_ADJ;
           if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
               procState = ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
           }
           app.adjType = "backup";
           app.cached = false;
       }
       if (procState > ActivityManager.PROCESS_STATE_BACKUP) {
           procState = ActivityManager.PROCESS_STATE_BACKUP;
       }
   }

   boolean mayBeTop = false;

   // 遍历进程中的 service
   for (int is = app.services.size()-1;
           is >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                   || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                   || procState > ActivityManager.PROCESS_STATE_TOP);
           is--) {
       ServiceRecord s = app.services.valueAt(is);
       if (s.startRequested) {
           app.hasStartedServices = true;
           if (procState > ActivityManager.PROCESS_STATE_SERVICE) {
               procState = ActivityManager.PROCESS_STATE_SERVICE;
           }
           if (app.hasShownUi && app != mHomeProcess) {
               if (adj > ProcessList.SERVICE_ADJ) {
                   app.adjType = "cch-started-ui-services";
               }
           } else {
               if (now < (s.lastActivity + ActiveServices.MAX_SERVICE_INACTIVITY)) {
                   if (adj > ProcessList.SERVICE_ADJ) {
                       adj = ProcessList.SERVICE_ADJ;
                       app.adjType = "started-services";
                       app.cached = false;
                   }
               }
               if (adj > ProcessList.SERVICE_ADJ) {
                   app.adjType = "cch-started-services";
               }
           }
       }

       for (int conni = s.connections.size()-1;
               conni >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                       || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                       || procState > ActivityManager.PROCESS_STATE_TOP);
               conni--) {
           ArrayList<ConnectionRecord> clist = s.connections.valueAt(conni);
           for (int i = 0;
                   i < clist.size() && (adj > ProcessList.FOREGROUND_APP_ADJ
                           || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                           || procState > ActivityManager.PROCESS_STATE_TOP);
                   i++) {
               ConnectionRecord cr = clist.get(i);
               if (cr.binding.client == app) {
                   continue;
               }

               if ((cr.flags&Context.BIND_WAIVE_PRIORITY) == 0) {
                   ProcessRecord client = cr.binding.client;
                   int clientAdj = computeOomAdjLocked(client, cachedAdj,
                           TOP_APP, doingAll, now);
                   int clientProcState = client.curProcState;
                   if (clientProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                       clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                   }
                   String adjType = null;
                   if ((cr.flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
                       if (app.hasShownUi && app != mHomeProcess) {
                           if (adj > clientAdj) {
                               adjType = "cch-bound-ui-services";
                           }
                           app.cached = false;
                           clientAdj = adj;
                           clientProcState = procState;
                       } else {
                           if (now >= (s.lastActivity
                                   + ActiveServices.MAX_SERVICE_INACTIVITY)) {
                               if (adj > clientAdj) {
                                   adjType = "cch-bound-services";
                               }
                               clientAdj = adj;
                           }
                       }
                   }
                   if (adj > clientAdj) {
                       if (app.hasShownUi && app != mHomeProcess
                               && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                           adjType = "cch-bound-ui-services";
                       } else {
                           if ((cr.flags&(Context.BIND_ABOVE_CLIENT
                                   |Context.BIND_IMPORTANT)) != 0) {
                               adj = clientAdj >= ProcessList.PERSISTENT_SERVICE_ADJ
                                       ? clientAdj : ProcessList.PERSISTENT_SERVICE_ADJ;
                           } else if ((cr.flags&Context.BIND_NOT_VISIBLE) != 0
                                   && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                   && adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                               adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                           } else if (clientAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
                               adj = clientAdj;
                           } else {
                               if (adj > ProcessList.VISIBLE_APP_ADJ) {
                                   adj = Math.max(clientAdj, ProcessList.VISIBLE_APP_ADJ);
                               }
                           }
                           if (!client.cached) {
                               app.cached = false;
                           }
                           adjType = "service";
                       }
                   }
                   if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                       if (client.curSchedGroup > schedGroup) {
                           if ((cr.flags&Context.BIND_IMPORTANT) != 0) {
                               schedGroup = client.curSchedGroup;
                           } else {
                               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                           }
                       }
                       if (clientProcState <= ActivityManager.PROCESS_STATE_TOP) {
                           if (clientProcState == ActivityManager.PROCESS_STATE_TOP) {
                               mayBeTop = true;
                               clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                           } else {
                               if ((cr.flags&Context.BIND_FOREGROUND_SERVICE) != 0) {
                                   clientProcState =
                                           ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                               } else if (mWakefulness
                                               == PowerManagerInternal.WAKEFULNESS_AWAKE &&
                                       (cr.flags&Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE)
                                               != 0) {
                                   clientProcState =
                                           ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                               } else {
                                   clientProcState =
                                           ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                               }
                           }
                       }
                   } else {
                       if (clientProcState <
                               ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
                           clientProcState =
                                   ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
                       }
                   }
                   if (procState > clientProcState) {
                       procState = clientProcState;
                   }
                   if (procState < ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                           && (cr.flags&Context.BIND_SHOWING_UI) != 0) {
                       app.pendingUiClean = true;
                   }
                   if (adjType != null) {
                       app.adjType = adjType;
                       app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                               .REASON_SERVICE_IN_USE;
                       app.adjSource = cr.binding.client;
                       app.adjSourceProcState = clientProcState;
                       app.adjTarget = s.name;
                   }
               }
               if ((cr.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                   app.treatLikeActivity = true;
               }
               final ActivityRecord a = cr.activity;
               if ((cr.flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
                   if (a != null && adj > ProcessList.FOREGROUND_APP_ADJ &&
                       (a.visible || a.state == ActivityState.RESUMED ||
                        a.state == ActivityState.PAUSING)) {
                       adj = ProcessList.FOREGROUND_APP_ADJ;
                       if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                           if ((cr.flags&Context.BIND_IMPORTANT) != 0) {
                               schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
                           } else {
                               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                           }
                       }
                       app.cached = false;
                       app.adjType = "service";
                       app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                               .REASON_SERVICE_IN_USE;
                       app.adjSource = a;
                       app.adjSourceProcState = procState;
                       app.adjTarget = s.name;
                   }
               }
           }
       }
   }

   // 遍历该进程中的 ContentProvider
   for (int provi = app.pubProviders.size()-1;
           provi >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                   || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                   || procState > ActivityManager.PROCESS_STATE_TOP);
           provi--) {
       ContentProviderRecord cpr = app.pubProviders.valueAt(provi);
       for (int i = cpr.connections.size()-1;
               i >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                       || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                       || procState > ActivityManager.PROCESS_STATE_TOP);
               i--) {
           ContentProviderConnection conn = cpr.connections.get(i);
           ProcessRecord client = conn.client;
           if (client == app) {
               continue;
           }
           int clientAdj = computeOomAdjLocked(client, cachedAdj, TOP_APP, doingAll, now);
           int clientProcState = client.curProcState;
           if (clientProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
               clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
           }
           if (adj > clientAdj) {
               if (app.hasShownUi && app != mHomeProcess
                       && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                   app.adjType = "cch-ui-provider";
               } else {
                   adj = clientAdj > ProcessList.FOREGROUND_APP_ADJ
                           ? clientAdj : ProcessList.FOREGROUND_APP_ADJ;
                   app.adjType = "provider";
               }
               app.cached &= client.cached;
               app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                       .REASON_PROVIDER_IN_USE;
               app.adjSource = client;
               app.adjSourceProcState = clientProcState;
               app.adjTarget = cpr.name;
           }
           if (clientProcState <= ActivityManager.PROCESS_STATE_TOP) {
               if (clientProcState == ActivityManager.PROCESS_STATE_TOP) {
                   mayBeTop = true;
                   clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
               } else {
                   clientProcState =
                           ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
               }
           }
           if (procState > clientProcState) {
               procState = clientProcState;
           }
           if (client.curSchedGroup > schedGroup) {
               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
           }
       }
       if (cpr.hasExternalProcessHandles()) {
           if (adj > ProcessList.FOREGROUND_APP_ADJ) {
               adj = ProcessList.FOREGROUND_APP_ADJ;
               schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
               app.cached = false;
               app.adjType = "provider";
               app.adjTarget = cpr.name;
           }
           if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
               procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
           }
       }
   }

   if (app.lastProviderTime > 0 && (app.lastProviderTime+CONTENT_PROVIDER_RETAIN_TIME) > now) {
       if (adj > ProcessList.PREVIOUS_APP_ADJ) {
           adj = ProcessList.PREVIOUS_APP_ADJ;
           schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
           app.cached = false;
           app.adjType = "provider";
       }
       if (procState > ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
           procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
       }
   }

   if (mayBeTop && procState > ActivityManager.PROCESS_STATE_TOP) {
       switch (procState) {
           case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
           case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
           case ActivityManager.PROCESS_STATE_SERVICE:
               procState = ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
               break;
           default:
               procState = ActivityManager.PROCESS_STATE_TOP;
               break;
       }
   }

   if (procState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
       if (app.hasClientActivities) {
           procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
           app.adjType = "cch-client-act";
       } else if (app.treatLikeActivity) {
           procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
           app.adjType = "cch-as-act";
       }
   }

   if (adj == ProcessList.SERVICE_ADJ) {
       if (doingAll) {
           app.serviceb = mNewNumAServiceProcs > (mNumServiceProcs/3);
           mNewNumServiceProcs++;
           if (!app.serviceb) {
               if (mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                       && app.lastPss >= mProcessList.getCachedRestoreThresholdKb()) {
                   app.serviceHighRam = true;
                   app.serviceb = true;
               } else {
                   mNewNumAServiceProcs++;
               }
           } else {
               app.serviceHighRam = false;
           }
       }
       if (app.serviceb) {
           adj = ProcessList.SERVICE_B_ADJ;
       }
   }

   app.curRawAdj = adj;

   // 如果 oom_adj 大于 maxAdj，将 oom_adj 设置为 maxAdj
   if (adj > app.maxAdj) {
       adj = app.maxAdj;
       if (app.maxAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
           schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
       }
   }

   // 调用 ProcessRecord.modifyRawOomAdj() 方法，更新当前的 adj
   app.curAdj = app.modifyRawOomAdj(adj);
   app.curSchedGroup = schedGroup;
   app.curProcState = procState;
   app.foregroundActivities = foregroundActivities;

   return app.curRawAdj;
}

```

</br>



---

### ProcessList 类中的常量

* **empty进程**

如果一个进程中不包含任何组件，这个进程被认为是 **empty进程**，例如：一个进程启动时只有一个 Activity，当这个 Activity 销毁时，该进程就变成了一个 **empty进程**。

* **cached进程**

当 Android 结束一个进程时，并不会将一个进程立即从系统中删除，而是把它标记为 **cached进程**，再启动新进程时，会优先使用 **cached进程**，这样就能加快应用的启动速度。

</br>



**ProcessList 类中定义了大量的 AMS 中用到的常量**

```java

final class ProcessList {
   // 定义发生 crash 的最小时间间隔，如果进程在小于该时间内发生 crash，会被认为是坏进程
   static final int MIN_CRASH_INTERVAL = 60*1000;

   // 任何主要或次要字段的未初始化值

   static final int INVALID_ADJ = -10000;

   // 处于某种不可知状态的进程的 oom_adj 值
   static final int UNKNOWN_ADJ = 1001;

   // cached 进程的 oom_adj 的最大值和最小值
   static final int CACHED_APP_MAX_ADJ = 906;
   static final int CACHED_APP_MIN_ADJ = 900;

   // 位于 B 列表的服务进程的 oom_adj 的值，位于 B 列表的都是一些旧的，过时的服务进程
   static final int SERVICE_B_ADJ = 800;

   // 当前 Activity 的前一个 Activity 所处进程的 oom_adj 的值
   static final int PREVIOUS_APP_ADJ = 700;

   // Home 进程的 oom_adj 的值
   static final int HOME_APP_ADJ = 600;

   // 只包含组件 Service 的进程的 oom_adj 的值
   static final int SERVICE_ADJ = 500;

   // heavy-weight 进程的 oom_adj 的值
   static final int HEAVY_WEIGHT_APP_ADJ = 400;

   // 正在执行 backup 的进程的 oom_adj 的值
   static final int BACKUP_APP_ADJ = 300;

   // 不在前台但是包含用户可感知组件的进程的 oom_adj 的值（例如播放音乐的后台进程）
   static final int PERCEPTIBLE_APP_ADJ = 200;

   // 仅包含 Activity 的可见进程的 oom_adj 的值
   static final int VISIBLE_APP_ADJ = 100;
   static final int VISIBLE_APP_LAYER_MAX = PERCEPTIBLE_APP_ADJ - VISIBLE_APP_ADJ - 1;

   // 前台进程的 oom_adj 的值
   static final int FOREGROUND_APP_ADJ = 0;

   // 带有 persistent 标记且还有组件 Service 的进程的 oom_adj 的值
   static final int PERSISTENT_SERVICE_ADJ = -700;

   // 死亡后会重启的 persistent 进程的 oom_adj 的值
   static final int PERSISTENT_PROC_ADJ = -800;

   // 系统进程的 oom_adj 的值
   static final int SYSTEM_ADJ = -900;

   // 包含 native 层代码的进程的 oom_adj 的值
   static final int NATIVE_ADJ = -1000;

   // 定义内存页面大小为 4 KB
   static final int PAGE_SIZE = 4*1024;

   // 系统最少处于 cached 状态的进程的数量
   static final int MIN_CACHED_APPS = 2;

   // 系统最大处于 cached 状态的进程的数量
   static final int MAX_CACHED_APPS = 32;

   // 定义空进程最大保存时间为 30 分钟
   static final long MAX_EMPTY_TIME = 30*60*1000;

   // 系统最大的空进程数量，它是 MAX_CACHED_APPS 的一半
   private static final int MAX_EMPTY_APPS = computeEmptyProcessLimit(MAX_CACHED_APPS);

   // 开始内存回收的空进程的阈值，系统中的空进程数量低于这个值不会执行内存回收
   static final int TRIM_EMPTY_APPS = MAX_EMPTY_APPS/2;

   // 开始内存回收的 cached 进程的阈值，系统中 cached 进程数量低于这个值不会执行内存回收
   static final int TRIM_CACHED_APPS = (MAX_CACHED_APPS-MAX_EMPTY_APPS)/3;

   // 定义用于内存回收的 oom_adj 的阈值
   private final int[] mOomAdj = new int[] {
           FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
           BACKUP_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_MAX_ADJ
   };


   // 定义用于低配置（HVGA 或更低，或内存小于 512 MB）设备内存回收的内存阈值
   private final int[] mOomMinFreeLow = new int[] {
           12288, 18432, 24576,
           36864, 43008, 49152
   };


   // 定义用于高配置（高分辨率或内存 1 GB左右）设备内存回收的内存阈值
   private final int[] mOomMinFreeHigh = new int[] {
           73728, 92160, 110592,
           129024, 147456, 184320
   };

}

```

<br/>



---

#### 相关的类

ProcessList



ProcessRecord



ActivityManagerService
