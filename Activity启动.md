# Activity启动
```puml
Title : Activity启动过程
Activity -> Activity : startActivityForResult()
Activity -> ContextImpl : startActivity()
ContextImpl -> ActivityThread : getInstrumentation()
Note left of ActivityThread : 返回 Instrumentation 对象
ActivityThread --> ContextImpl : Instrumentation
ContextImpl -> Instrumentation : execStartActivity()
Instrumentation -> ActivityManagerService : startActivity()
```

# 涉及的类
* Instrumentation
实施应用程序代码的基础类，在应用程序之前实例化，用于监视所有系统与应用程序的交互。

## `ActivityThread.handleResumeActivity()` 方法
```
final void handleResumeActivity(IBinder token,
        boolean clearHide, boolean isForward, boolean reallyResume, int seq, String reason) {
    ActivityClientRecord r = mActivities.get(token);
    if (!checkAndUpdateLifecycleSeq(seq, r, "resumeActivity")) {
        return;
    }
    unscheduleGcIdler();
    mSomeActivitiesChanged = true;

    // 调用performResumeActivity()方法
    r = performResumeActivity(token, clearHide, reason);
    if (r != null) {
        final Activity a = r.activity;
        final int forwardBit = isForward ?
                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION : 0;
        boolean willBeVisible = !a.mStartedActivity;
        if (!willBeVisible) {
            try {
                willBeVisible = ActivityManagerNative.getDefault().willActivityBeVisible(
                        a.getActivityToken());
            } catch (RemoteException e) {...}
        }
        if (r.window == null && !a.mFinished && willBeVisible) {
            r.window = r.activity.getWindow();
            View decor = r.window.getDecorView();
            decor.setVisibility(View.INVISIBLE);

            // 获取ViewManager对象通过Activity.getWindowManager()方法获得，这里其实是WindowManagerImpl对象
            // WindowManagerImpl ---> extends WindowManager ---> extends ViewManager
            ViewManager wm = a.getWindowManager();
            WindowManager.LayoutParams l = r.window.getAttributes();
            a.mDecor = decor;
            l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
            l.softInputMode |= forwardBit;
            if (r.mPreserveWindow) {
                a.mWindowAdded = true;
                r.mPreserveWindow = false;

                // 通过DecorView.getViewRootImpl()方法获取ViewRootImpl对象
                ViewRootImpl impl = decor.getViewRootImpl();
                if (impl != null) {
                    impl.notifyChildRebuilt();
                }
            }
            if (a.mVisibleFromClient && !a.mWindowAdded) {
                a.mWindowAdded = true;

                // 调用WindowManagerImpl.addView()方法，传入的是DecorView对象和WindowManager.LayoutParams对象
                wm.addView(decor, l);
            }
        } else if (!willBeVisible) {
            r.hideForNow = true;
        }

        cleanUpPendingRemoveWindows(r, false /* force */);

        if (!r.activity.mFinished && willBeVisible
                && r.activity.mDecor != null && !r.hideForNow) {
            if (r.newConfig != null) {
                performConfigurationChangedForActivity(r, r.newConfig, REPORT_TO_ACTIVITY);
                r.newConfig = null;
            }

            WindowManager.LayoutParams l = r.window.getAttributes();
            if ((l.softInputMode
                    & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION)
                    != forwardBit) {
                l.softInputMode = (l.softInputMode
                        & (~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION))
                        | forwardBit;
                if (r.activity.mVisibleFromClient) {
                    ViewManager wm = a.getWindowManager();
                    View decor = r.window.getDecorView();
                    wm.updateViewLayout(decor, l);
                }
            }
            r.activity.mVisibleFromServer = true;
            mNumVisibleActivities++;
            if (r.activity.mVisibleFromClient) {
                r.activity.makeVisible();
            }
        }

        if (!r.onlyLocalRequest) {
            r.nextIdle = mNewActivities;
            mNewActivities = r;
            Looper.myQueue().addIdleHandler(new Idler());
        }
        r.onlyLocalRequest = false;

        if (reallyResume) {
            try {
                ActivityManagerNative.getDefault().activityResumed(token);
            } catch (RemoteException ex) {...}
        }
    } else {
        try {
            ActivityManagerNative.getDefault()
                .finishActivity(token, Activity.RESULT_CANCELED, null,
                        Activity.DONT_FINISH_TASK_WITH_ACTIVITY);
        } catch (RemoteException ex) {...}
    }
}
```

## `WindowManagerImpl.addView()` 方法
```
public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
    applyDefaultToken(params);
    // 调用WindowManagerGlobal.addView()方法
    mGlobal.addView(view, params, mContext.getDisplay(), mParentWindow);
}
```

## `WindowManagerGlobal.addView()` 方法
```
public void addView(View view, ViewGroup.LayoutParams params,
        Display display, Window parentWindow) {
    if (view == null) {
        throw new IllegalArgumentException("view must not be null");
    }
    if (display == null) {
        throw new IllegalArgumentException("display must not be null");
    }
    if (!(params instanceof WindowManager.LayoutParams)) {
        throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
    }

    final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
    if (parentWindow != null) {
        parentWindow.adjustLayoutParamsForSubWindow(wparams);
    } else {
        final Context context = view.getContext();
        if (context != null
                && (context.getApplicationInfo().flags
                        & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
            wparams.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
    }

    ViewRootImpl root;
    View panelParentView = null;
    synchronized (mLock) {
        if (mSystemPropertyUpdater == null) {
            mSystemPropertyUpdater = new Runnable() {
                @Override public void run() {
                    synchronized (mLock) {
                        for (int i = mRoots.size() - 1; i >= 0; --i) {
                            mRoots.get(i).loadSystemProperties();
                        }
                    }
                }
            };
            SystemProperties.addChangeCallback(mSystemPropertyUpdater);
        }

        int index = findViewLocked(view, false);
        if (index >= 0) {
            if (mDyingViews.contains(view)) {
                mRoots.get(index).doDie();
            } else {...}
        }

        if (wparams.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                wparams.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
            final int count = mViews.size();
            for (int i = 0; i < count; i++) {
                if (mRoots.get(i).mWindow.asBinder() == wparams.token) {
                    panelParentView = mViews.get(i);
                }
            }
        }

        // 创建一个ViewRootImpl对象
        root = new ViewRootImpl(view.getContext(), display);
        view.setLayoutParams(wparams);
        mViews.add(view);
        mRoots.add(root);
        mParams.add(wparams);

        try {
            // 调用ViewRootImpl.setView()方法
            root.setView(view, wparams, panelParentView);
        } catch (RuntimeException e) {
            if (index >= 0) {
                removeViewLocked(index, true);
            }
            throw e;
        }
    }
}
```

## `ViewRootImpl.setView()` 方法
```
public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
    synchronized (this) {
        if (mView == null) {
            mView = view;

            mAttachInfo.mDisplayState = mDisplay.getState();
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);

            mViewLayoutDirectionInitial = mView.getRawLayoutDirection();
            mFallbackEventHandler.setView(view);
            mWindowAttributes.copyFrom(attrs);
            if (mWindowAttributes.packageName == null) {
                mWindowAttributes.packageName = mBasePackageName;
            }
            attrs = mWindowAttributes;
            setTag();

            mClientWindowLayoutFlags = attrs.flags;

            setAccessibilityFocus(null, null);

            if (view instanceof RootViewSurfaceTaker) {
                mSurfaceHolderCallback =
                        ((RootViewSurfaceTaker)view).willYouTakeTheSurface();
                if (mSurfaceHolderCallback != null) {
                    mSurfaceHolder = new TakenSurfaceHolder();
                    mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                }
            }

            if (!attrs.hasManualSurfaceInsets) {
                attrs.setSurfaceInsets(view, false /*manual*/, true /*preservePrevious*/);
            }

            CompatibilityInfo compatibilityInfo =
                    mDisplay.getDisplayAdjustments().getCompatibilityInfo();
            mTranslator = compatibilityInfo.getTranslator();

            if (mSurfaceHolder == null) {
                enableHardwareAcceleration(attrs);
            }

            boolean restore = false;
            if (mTranslator != null) {
                mSurface.setCompatibilityTranslator(mTranslator);
                restore = true;
                attrs.backup();
                mTranslator.translateWindowLayout(attrs);
            }

            if (!compatibilityInfo.supportsScreen()) {
                attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = true;
            }

            mSoftInputMode = attrs.softInputMode;
            mWindowAttributesChanged = true;
            mWindowAttributesChangesFlag = WindowManager.LayoutParams.EVERYTHING_CHANGED;
            mAttachInfo.mRootView = view;
            mAttachInfo.mScalingRequired = mTranslator != null;
            mAttachInfo.mApplicationScale =
                    mTranslator == null ? 1.0f : mTranslator.applicationScale;
            if (panelParentView != null) {
                mAttachInfo.mPanelParentWindowToken
                        = panelParentView.getApplicationWindowToken();
            }
            mAdded = true;
            int res;

            // 调用requestLayout()方法
            requestLayout();
            if ((mWindowAttributes.inputFeatures
                    & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                mInputChannel = new InputChannel();
            }
            mForceDecorViewVisibility = (mWindowAttributes.privateFlags
                    & PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY) != 0;
            try {
                mOrigWindowType = mWindowAttributes.type;
                mAttachInfo.mRecomputeGlobalAttributes = true;
                collectViewAttributes();
                res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                        getHostVisibility(), mDisplay.getDisplayId(),
                        mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                        mAttachInfo.mOutsets, mInputChannel);
            } catch (RemoteException e) {
                mAdded = false;
                mView = null;
                mAttachInfo.mRootView = null;
                mInputChannel = null;
                mFallbackEventHandler.setView(null);
                unscheduleTraversals();
                setAccessibilityFocus(null, null);
                throw new RuntimeException("Adding window failed", e);
            } finally {
                if (restore) {
                    attrs.restore();
                }
            }

            if (mTranslator != null) {
                mTranslator.translateRectInScreenToAppWindow(mAttachInfo.mContentInsets);
            }
            mPendingOverscanInsets.set(0, 0, 0, 0);
            mPendingContentInsets.set(mAttachInfo.mContentInsets);
            mPendingStableInsets.set(mAttachInfo.mStableInsets);
            mPendingVisibleInsets.set(0, 0, 0, 0);
            mAttachInfo.mAlwaysConsumeNavBar =
                    (res & WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_NAV_BAR) != 0;
            mPendingAlwaysConsumeNavBar = mAttachInfo.mAlwaysConsumeNavBar;

            // 错误处理
            if (res < WindowManagerGlobal.ADD_OKAY) {
                mAttachInfo.mRootView = null;
                mAdded = false;
                mFallbackEventHandler.setView(null);
                unscheduleTraversals();
                setAccessibilityFocus(null, null);
                switch (res) {
                    case WindowManagerGlobal.ADD_BAD_APP_TOKEN:
                    case WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN:
                        throw new WindowManager.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not valid; is your activity running?");
                    case WindowManagerGlobal.ADD_NOT_APP_TOKEN:
                        throw new WindowManager.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not for an application");
                    case WindowManagerGlobal.ADD_APP_EXITING:
                        throw new WindowManager.BadTokenException(
                                "Unable to add window -- app for token " + attrs.token
                                + " is exiting");
                    case WindowManagerGlobal.ADD_DUPLICATE_ADD:
                        throw new WindowManager.BadTokenException(
                                "Unable to add window -- window " + mWindow
                                + " has already been added");
                    case WindowManagerGlobal.ADD_STARTING_NOT_NEEDED:
                        return;
                    case WindowManagerGlobal.ADD_MULTIPLE_SINGLETON:
                        throw new WindowManager.BadTokenException("Unable to add window "
                                + mWindow + " -- another window of type "
                                + mWindowAttributes.type + " already exists");
                    case WindowManagerGlobal.ADD_PERMISSION_DENIED:
                        throw new WindowManager.BadTokenException("Unable to add window "
                                + mWindow + " -- permission denied for window type "
                                + mWindowAttributes.type);
                    case WindowManagerGlobal.ADD_INVALID_DISPLAY:
                        throw new WindowManager.InvalidDisplayException("Unable to add window "
                                + mWindow + " -- the specified display can not be found");
                    case WindowManagerGlobal.ADD_INVALID_TYPE:
                        throw new WindowManager.InvalidDisplayException("Unable to add window "
                                + mWindow + " -- the specified window type "
                                + mWindowAttributes.type + " is not valid");
                }
                throw new RuntimeException(
                        "Unable to add window -- unknown error code " + res);
            }

            if (view instanceof RootViewSurfaceTaker) {
                mInputQueueCallback =
                    ((RootViewSurfaceTaker)view).willYouTakeTheInputQueue();
            }
            if (mInputChannel != null) {
                if (mInputQueueCallback != null) {
                    mInputQueue = new InputQueue();
                    mInputQueueCallback.onInputQueueCreated(mInputQueue);
                }
                mInputEventReceiver = new WindowInputEventReceiver(mInputChannel,
                        Looper.myLooper());
            }

            view.assignParent(this);
            mAddedTouchMode = (res & WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE) != 0;
            mAppVisible = (res & WindowManagerGlobal.ADD_FLAG_APP_VISIBLE) != 0;

            if (mAccessibilityManager.isEnabled()) {
                mAccessibilityInteractionConnectionManager.ensureConnection();
            }

            if (view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            }

            // 设置输入相关
            CharSequence counterSuffix = attrs.getTitle();
            mSyntheticInputStage = new SyntheticInputStage();
            InputStage viewPostImeStage = new ViewPostImeInputStage(mSyntheticInputStage);
            InputStage nativePostImeStage = new NativePostImeInputStage(viewPostImeStage,
                    "aq:native-post-ime:" + counterSuffix);
            InputStage earlyPostImeStage = new EarlyPostImeInputStage(nativePostImeStage);
            InputStage imeStage = new ImeInputStage(earlyPostImeStage,
                    "aq:ime:" + counterSuffix);
            InputStage viewPreImeStage = new ViewPreImeInputStage(imeStage);
            InputStage nativePreImeStage = new NativePreImeInputStage(viewPreImeStage,
                    "aq:native-pre-ime:" + counterSuffix);

            mFirstInputStage = nativePreImeStage;
            mFirstPostImeInputStage = earlyPostImeStage;
            mPendingInputEventQueueLengthCounterName = "aq:pending:" + counterSuffix;
        }
    }
}
```

## `ViewRootImpl.requestLayout()` 方法
```
public void requestLayout() {
    if (!mHandlingLayoutInLayoutRequest) {
        checkThread();
        mLayoutRequested = true;
        // 调用scheduleTraversals()方法
        scheduleTraversals();
    }
}
```

## `ViewRootImpl.scheduleTraversals()` 方法
```
void scheduleTraversals() {
    if (!mTraversalScheduled) {
        mTraversalScheduled = true;
        mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
        //调用Choreographer.postCallback()方法注册mTraversalRunnable这个回调
        mChoreographer.postCallback(
                Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        if (!mUnbufferedInputDispatch) {
            scheduleConsumeBatchedInput();
        }
        notifyRendererOfFramePending();
        pokeDrawLockIfNeeded();
    }
}
```

### `ViewRootImpl.TraversalRunnable` 类
```
final class TraversalRunnable implements Runnable {
    @Override
    public void run() {
        doTraversal();
    }
}
```

## `ViewRootImpl.doTraversal()` 方法
```
void doTraversal() {
    if (mTraversalScheduled) {
        mTraversalScheduled = false;
        mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
        if (mProfile) {
            Debug.startMethodTracing("ViewAncestor");
        }

        // 调用performTraversals()方法
        performTraversals();
        if (mProfile) {
            Debug.stopMethodTracing();
            mProfile = false;
        }
    }
}
```

## `ViewRootImpl.performTraversals()` 方法
```
private void performTraversals() {
    final View host = mView;
    if (host == null || !mAdded)
        return;

    mIsInTraversal = true;
    mWillDrawSoon = true;
    boolean windowSizeMayChange = false;
    boolean newSurface = false;
    boolean surfaceChanged = false;
    WindowManager.LayoutParams lp = mWindowAttributes;

    int desiredWindowWidth;
    int desiredWindowHeight;

    final int viewVisibility = getHostVisibility();
    final boolean viewVisibilityChanged = !mFirst
            && (mViewVisibility != viewVisibility || mNewSurfaceNeeded);

    WindowManager.LayoutParams params = null;
    if (mWindowAttributesChanged) {
        mWindowAttributesChanged = false;
        surfaceChanged = true;
        params = lp;
    }
    CompatibilityInfo compatibilityInfo =
            mDisplay.getDisplayAdjustments().getCompatibilityInfo();
    if (compatibilityInfo.supportsScreen() == mLastInCompatMode) {
        params = lp;
        mFullRedrawNeeded = true;
        mLayoutRequested = true;
        if (mLastInCompatMode) {
            params.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
            mLastInCompatMode = false;
        } else {
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
            mLastInCompatMode = true;
        }
    }

    mWindowAttributesChangesFlag = 0;

    Rect frame = mWinFrame;
    if (mFirst) {
        mFullRedrawNeeded = true;
        mLayoutRequested = true;

        if (shouldUseDisplaySize(lp)) {
            Point size = new Point();
            mDisplay.getRealSize(size);
            desiredWindowWidth = size.x;
            desiredWindowHeight = size.y;
        } else {
            Configuration config = mContext.getResources().getConfiguration();
            desiredWindowWidth = dipToPx(config.screenWidthDp);
            desiredWindowHeight = dipToPx(config.screenHeightDp);
        }

        mAttachInfo.mUse32BitDrawingCache = true;
        mAttachInfo.mHasWindowFocus = false;
        mAttachInfo.mWindowVisibility = viewVisibility;
        mAttachInfo.mRecomputeGlobalAttributes = false;
        mLastConfiguration.setTo(host.getResources().getConfiguration());
        mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
        if (mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
            host.setLayoutDirection(mLastConfiguration.getLayoutDirection());
        }
        host.dispatchAttachedToWindow(mAttachInfo, 0);
        mAttachInfo.mTreeObserver.dispatchOnWindowAttachedChange(true);
        dispatchApplyInsets(host);
    } else {
        desiredWindowWidth = frame.width();
        desiredWindowHeight = frame.height();
        if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
            mFullRedrawNeeded = true;
            mLayoutRequested = true;
            windowSizeMayChange = true;
        }
    }

    if (viewVisibilityChanged) {
        mAttachInfo.mWindowVisibility = viewVisibility;
        host.dispatchWindowVisibilityChanged(viewVisibility);
        host.dispatchVisibilityAggregated(viewVisibility == View.VISIBLE);
        if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
            endDragResizing();
            destroyHardwareResources();
        }
        if (viewVisibility == View.GONE) {
            mHasHadWindowFocus = false;
        }
    }

    if (mAttachInfo.mWindowVisibility != View.VISIBLE) {
        host.clearAccessibilityFocus();
    }

    getRunQueue().executeActions(mAttachInfo.mHandler);
    boolean insetsChanged = false;
    boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
    if (layoutRequested) {
        final Resources res = mView.getContext().getResources();
        if (mFirst) {
            mAttachInfo.mInTouchMode = !mAddedTouchMode;
            ensureTouchModeLocally(mAddedTouchMode);
        } else {
            if (!mPendingOverscanInsets.equals(mAttachInfo.mOverscanInsets)) {
                insetsChanged = true;
            }
            if (!mPendingContentInsets.equals(mAttachInfo.mContentInsets)) {
                insetsChanged = true;
            }
            if (!mPendingStableInsets.equals(mAttachInfo.mStableInsets)) {
                insetsChanged = true;
            }
            if (!mPendingVisibleInsets.equals(mAttachInfo.mVisibleInsets)) {
                mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                if (DEBUG_LAYOUT) Log.v(mTag, "Visible insets changing to: "
                        + mAttachInfo.mVisibleInsets);
            }
            if (!mPendingOutsets.equals(mAttachInfo.mOutsets)) {
                insetsChanged = true;
            }
            if (mPendingAlwaysConsumeNavBar != mAttachInfo.mAlwaysConsumeNavBar) {
                insetsChanged = true;
            }
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                    || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                windowSizeMayChange = true;

                if (shouldUseDisplaySize(lp)) {
                    Point size = new Point();
                    mDisplay.getRealSize(size);
                    desiredWindowWidth = size.x;
                    desiredWindowHeight = size.y;
                } else {
                    Configuration config = res.getConfiguration();
                    desiredWindowWidth = dipToPx(config.screenWidthDp);
                    desiredWindowHeight = dipToPx(config.screenHeightDp);
                }
            }
        }
        windowSizeMayChange |= measureHierarchy(host, lp, res,
                desiredWindowWidth, desiredWindowHeight);
    }

    if (collectViewAttributes()) {
        params = lp;
    }
    if (mAttachInfo.mForceReportNewAttributes) {
        mAttachInfo.mForceReportNewAttributes = false;
        params = lp;
    }

    if (mFirst || mAttachInfo.mViewVisibilityChanged) {
        mAttachInfo.mViewVisibilityChanged = false;
        int resizeMode = mSoftInputMode &
                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

        if (resizeMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
            final int N = mAttachInfo.mScrollContainers.size();
            for (int i=0; i<N; i++) {
                if (mAttachInfo.mScrollContainers.get(i).isShown()) {
                    resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                }
            }
            if (resizeMode == 0) {
                resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            }
            if ((lp.softInputMode &
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != resizeMode) {
                lp.softInputMode = (lp.softInputMode &
                        ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) |
                        resizeMode;
                params = lp;
            }
        }
    }

    if (params != null) {
        if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
            if (!PixelFormat.formatHasAlpha(params.format)) {
                params.format = PixelFormat.TRANSLUCENT;
            }
        }
        mAttachInfo.mOverscanRequested = (params.flags
                & WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN) != 0;
    }

    if (mApplyInsetsRequested) {
        mApplyInsetsRequested = false;
        mLastOverscanRequested = mAttachInfo.mOverscanRequested;
        dispatchApplyInsets(host);
        if (mLayoutRequested) {
            windowSizeMayChange |= measureHierarchy(host, lp,
                    mView.getContext().getResources(),
                    desiredWindowWidth, desiredWindowHeight);
        }
    }

    if (layoutRequested) {
        mLayoutRequested = false;
    }

    boolean windowShouldResize = layoutRequested && windowSizeMayChange
        && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
            || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                    frame.width() < desiredWindowWidth && frame.width() != mWidth)
            || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                    frame.height() < desiredWindowHeight && frame.height() != mHeight));
    windowShouldResize |= mDragResizing && mResizeMode == RESIZE_MODE_FREEFORM;
    windowShouldResize |= mActivityRelaunched;
    final boolean computesInternalInsets =
            mAttachInfo.mTreeObserver.hasComputeInternalInsetsListeners()
            || mAttachInfo.mHasNonEmptyGivenInternalInsets;

    boolean insetsPending = false;
    int relayoutResult = 0;
    boolean updatedConfiguration = false;

    final int surfaceGenerationId = mSurface.getGenerationId();

    final boolean isViewVisible = viewVisibility == View.VISIBLE;
    if (mFirst || windowShouldResize || insetsChanged ||
            viewVisibilityChanged || params != null || mForceNextWindowRelayout) {
        mForceNextWindowRelayout = false;

        if (isViewVisible) {
            insetsPending = computesInternalInsets && (mFirst || viewVisibilityChanged);
        }

        if (mSurfaceHolder != null) {
            mSurfaceHolder.mSurfaceLock.lock();
            mDrawingAllowed = true;
        }

        boolean hwInitialized = false;
        boolean framesChanged = false;
        boolean hadSurface = mSurface.isValid();

        try {
            if (mAttachInfo.mHardwareRenderer != null) {
                if (mAttachInfo.mHardwareRenderer.pauseSurface(mSurface)) {
                    mDirty.set(0, 0, mWidth, mHeight);
                }
                mChoreographer.mFrameInfo.addFlags(FrameInfo.FLAG_WINDOW_LAYOUT_CHANGED);
            }

            //调用relayoutWindow()方法
            relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);

            if (mPendingConfiguration.seq != 0) {
                updateConfiguration(new Configuration(mPendingConfiguration), !mFirst);
                mPendingConfiguration.seq = 0;
                updatedConfiguration = true;
            }

            final boolean overscanInsetsChanged = !mPendingOverscanInsets.equals(
                    mAttachInfo.mOverscanInsets);
            boolean contentInsetsChanged = !mPendingContentInsets.equals(
                    mAttachInfo.mContentInsets);
            final boolean visibleInsetsChanged = !mPendingVisibleInsets.equals(
                    mAttachInfo.mVisibleInsets);
            final boolean stableInsetsChanged = !mPendingStableInsets.equals(
                    mAttachInfo.mStableInsets);
            final boolean outsetsChanged = !mPendingOutsets.equals(mAttachInfo.mOutsets);
            final boolean surfaceSizeChanged = (relayoutResult
                    & WindowManagerGlobal.RELAYOUT_RES_SURFACE_RESIZED) != 0;
            final boolean alwaysConsumeNavBarChanged =
                    mPendingAlwaysConsumeNavBar != mAttachInfo.mAlwaysConsumeNavBar;
            if (contentInsetsChanged) {
                mAttachInfo.mContentInsets.set(mPendingContentInsets);
            }
            if (overscanInsetsChanged) {
                mAttachInfo.mOverscanInsets.set(mPendingOverscanInsets);
                contentInsetsChanged = true;
            }
            if (stableInsetsChanged) {
                mAttachInfo.mStableInsets.set(mPendingStableInsets);
                contentInsetsChanged = true;
            }
            if (alwaysConsumeNavBarChanged) {
                mAttachInfo.mAlwaysConsumeNavBar = mPendingAlwaysConsumeNavBar;
                contentInsetsChanged = true;
            }
            if (contentInsetsChanged || mLastSystemUiVisibility !=
                    mAttachInfo.mSystemUiVisibility || mApplyInsetsRequested
                    || mLastOverscanRequested != mAttachInfo.mOverscanRequested
                    || outsetsChanged) {
                mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                mLastOverscanRequested = mAttachInfo.mOverscanRequested;
                mAttachInfo.mOutsets.set(mPendingOutsets);
                mApplyInsetsRequested = false;
                dispatchApplyInsets(host);
            }
            if (visibleInsetsChanged) {
                mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
            }

            framesChanged = overscanInsetsChanged
                    || contentInsetsChanged
                    || stableInsetsChanged
                    || visibleInsetsChanged
                    || outsetsChanged;
            if (mAdded && mView != null && framesChanged) {
                forceLayout(mView);
            }

            if (!hadSurface) {
                if (mSurface.isValid()) {
                    newSurface = true;
                    mFullRedrawNeeded = true;
                    mPreviousTransparentRegion.setEmpty();
                    if (mAttachInfo.mHardwareRenderer != null) {
                        try {
                            hwInitialized = mAttachInfo.mHardwareRenderer.initialize(
                                    mSurface);
                            if (hwInitialized && (host.mPrivateFlags
                                    & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0) {
                                mSurface.allocateBuffers();
                            }
                        } catch (OutOfResourcesException e) {
                            handleOutOfResourcesException(e);
                            return;
                        }
                    }
                }
            } else if (!mSurface.isValid()) {
                if (mLastScrolledFocus != null) {
                    mLastScrolledFocus.clear();
                }
                mScrollY = mCurScrollY = 0;
                if (mView instanceof RootViewSurfaceTaker) {
                    ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
                }
                if (mScroller != null) {
                    mScroller.abortAnimation();
                }
                if (mAttachInfo.mHardwareRenderer != null &&
                        mAttachInfo.mHardwareRenderer.isEnabled()) {
                    mAttachInfo.mHardwareRenderer.destroy();
                }
            } else if ((surfaceGenerationId != mSurface.getGenerationId()
                    || surfaceSizeChanged)
                    && mSurfaceHolder == null
                    && mAttachInfo.mHardwareRenderer != null) {
                mFullRedrawNeeded = true;
                try {
                    mAttachInfo.mHardwareRenderer.updateSurface(mSurface);
                } catch (OutOfResourcesException e) {
                    handleOutOfResourcesException(e);
                    return;
                }
            }

            final boolean freeformResizing = (relayoutResult
                    & WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_FREEFORM) != 0;
            final boolean dockedResizing = (relayoutResult
                    & WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_DOCKED) != 0;
            final boolean dragResizing = freeformResizing || dockedResizing;
            if (mDragResizing != dragResizing) {
                if (dragResizing) {
                    mResizeMode = freeformResizing
                            ? RESIZE_MODE_FREEFORM
                            : RESIZE_MODE_DOCKED_DIVIDER;
                    startDragResizing(mPendingBackDropFrame,
                            mWinFrame.equals(mPendingBackDropFrame), mPendingVisibleInsets,
                            mPendingStableInsets, mResizeMode);
                } else {
                    endDragResizing();
                }
            }
            if (!USE_MT_RENDERER) {
                if (dragResizing) {
                    mCanvasOffsetX = mWinFrame.left;
                    mCanvasOffsetY = mWinFrame.top;
                } else {
                    mCanvasOffsetX = mCanvasOffsetY = 0;
                }
            }
        } catch (RemoteException e) {}

        mAttachInfo.mWindowLeft = frame.left;
        mAttachInfo.mWindowTop = frame.top;

        if (mWidth != frame.width() || mHeight != frame.height()) {
            mWidth = frame.width();
            mHeight = frame.height();
        }

        if (mSurfaceHolder != null) {
            if (mSurface.isValid()) {
                mSurfaceHolder.mSurface = mSurface;
            }
            mSurfaceHolder.setSurfaceFrameSize(mWidth, mHeight);
            mSurfaceHolder.mSurfaceLock.unlock();
            if (mSurface.isValid()) {
                if (!hadSurface) {
                    mSurfaceHolder.ungetCallbacks();

                    mIsCreating = true;
                    mSurfaceHolderCallback.surfaceCreated(mSurfaceHolder);
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceCreated(mSurfaceHolder);
                        }
                    }
                    surfaceChanged = true;
                }
                if (surfaceChanged || surfaceGenerationId != mSurface.getGenerationId()) {
                    mSurfaceHolderCallback.surfaceChanged(mSurfaceHolder,
                            lp.format, mWidth, mHeight);
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceChanged(mSurfaceHolder, lp.format,
                                    mWidth, mHeight);
                        }
                    }
                }
                mIsCreating = false;
            } else if (hadSurface) {
                mSurfaceHolder.ungetCallbacks();
                SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                mSurfaceHolderCallback.surfaceDestroyed(mSurfaceHolder);
                if (callbacks != null) {
                    for (SurfaceHolder.Callback c : callbacks) {
                        c.surfaceDestroyed(mSurfaceHolder);
                    }
                }
                mSurfaceHolder.mSurfaceLock.lock();
                try {
                    mSurfaceHolder.mSurface = new Surface();
                } finally {
                    mSurfaceHolder.mSurfaceLock.unlock();
                }
            }
        }

        final ThreadedRenderer hardwareRenderer = mAttachInfo.mHardwareRenderer;
        if (hardwareRenderer != null && hardwareRenderer.isEnabled()) {
            if (hwInitialized
                    || mWidth != hardwareRenderer.getWidth()
                    || mHeight != hardwareRenderer.getHeight()
                    || mNeedsHwRendererSetup) {
                hardwareRenderer.setup(mWidth, mHeight, mAttachInfo,
                        mWindowAttributes.surfaceInsets);
                mNeedsHwRendererSetup = false;
            }
        }

        if (!mStopped || mReportNextDraw) {
            boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                    (relayoutResult&WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE) != 0);
            if (focusChangedDueToTouchMode || mWidth != host.getMeasuredWidth()
                    || mHeight != host.getMeasuredHeight() || framesChanged ||
                    updatedConfiguration) {
                int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);

                // 调用performMeasure()方法
                performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);

                int width = host.getMeasuredWidth();
                int height = host.getMeasuredHeight();
                boolean measureAgain = false;

                if (lp.horizontalWeight > 0.0f) {
                    width += (int) ((mWidth - width) * lp.horizontalWeight);
                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                            MeasureSpec.EXACTLY);
                    measureAgain = true;
                }
                if (lp.verticalWeight > 0.0f) {
                    height += (int) ((mHeight - height) * lp.verticalWeight);
                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                            MeasureSpec.EXACTLY);
                    measureAgain = true;
                }

                if (measureAgain) {
                    // 调用performMeasure()方法
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                }

                layoutRequested = true;
            }
        }
    } else {
        maybeHandleWindowMove(frame);
    }

    final boolean didLayout = layoutRequested && (!mStopped || mReportNextDraw);
    boolean triggerGlobalLayoutListener = didLayout
            || mAttachInfo.mRecomputeGlobalAttributes;
    if (didLayout) {

        // 调用performLayout()方法
        performLayout(lp, mWidth, mHeight);
        if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
            host.getLocationInWindow(mTmpLocation);
            mTransparentRegion.set(mTmpLocation[0], mTmpLocation[1],
                    mTmpLocation[0] + host.mRight - host.mLeft,
                    mTmpLocation[1] + host.mBottom - host.mTop);

            host.gatherTransparentRegion(mTransparentRegion);
            if (mTranslator != null) {
                mTranslator.translateRegionInWindowToScreen(mTransparentRegion);
            }

            if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                mPreviousTransparentRegion.set(mTransparentRegion);
                mFullRedrawNeeded = true;
                try {
                    mWindowSession.setTransparentRegion(mWindow, mTransparentRegion);
                } catch (RemoteException e) {}
            }
        }
    }

    if (triggerGlobalLayoutListener) {
        mAttachInfo.mRecomputeGlobalAttributes = false;
        mAttachInfo.mTreeObserver.dispatchOnGlobalLayout();
    }

    if (computesInternalInsets) {
        final ViewTreeObserver.InternalInsetsInfo insets = mAttachInfo.mGivenInternalInsets;
        insets.reset();
        mAttachInfo.mTreeObserver.dispatchOnComputeInternalInsets(insets);
        mAttachInfo.mHasNonEmptyGivenInternalInsets = !insets.isEmpty();

        if (insetsPending || !mLastGivenInsets.equals(insets)) {
            mLastGivenInsets.set(insets);
            final Rect contentInsets;
            final Rect visibleInsets;
            final Region touchableRegion;
            if (mTranslator != null) {
                contentInsets = mTranslator.getTranslatedContentInsets(insets.contentInsets);
                visibleInsets = mTranslator.getTranslatedVisibleInsets(insets.visibleInsets);
                touchableRegion = mTranslator.getTranslatedTouchableArea(insets.touchableRegion);
            } else {
                contentInsets = insets.contentInsets;
                visibleInsets = insets.visibleInsets;
                touchableRegion = insets.touchableRegion;
            }

            try {
                mWindowSession.setInsets(mWindow, insets.mTouchableInsets,
                        contentInsets, visibleInsets, touchableRegion);
            } catch (RemoteException e) {}
        }
    }

    if (mFirst) {
        if (mView != null) {
            if (!mView.hasFocus()) {
                mView.requestFocus(View.FOCUS_FORWARD);
            }
        }
    }

    final boolean changedVisibility = (viewVisibilityChanged || mFirst) && isViewVisible;
    final boolean hasWindowFocus = mAttachInfo.mHasWindowFocus && isViewVisible;
    final boolean regainedFocus = hasWindowFocus && mLostWindowFocus;
    if (regainedFocus) {
        mLostWindowFocus = false;
    } else if (!hasWindowFocus && mHadWindowFocus) {
        mLostWindowFocus = true;
    }

    if (changedVisibility || regainedFocus) {
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    mFirst = false;
    mWillDrawSoon = false;
    mNewSurfaceNeeded = false;
    mActivityRelaunched = false;
    mViewVisibility = viewVisibility;
    mHadWindowFocus = hasWindowFocus;

    if (hasWindowFocus && !isInLocalFocusMode()) {
        final boolean imTarget = WindowManager.LayoutParams
                .mayUseInputMethod(mWindowAttributes.flags);
        if (imTarget != mLastWasImTarget) {
            mLastWasImTarget = imTarget;
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null && imTarget) {
                imm.onPreWindowFocus(mView, hasWindowFocus);
                imm.onPostWindowFocus(mView, mView.findFocus(),
                        mWindowAttributes.softInputMode,
                        !mHasHadWindowFocus, mWindowAttributes.flags);
            }
        }
    }

    if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
        mReportNextDraw = true;
    }

    boolean cancelDraw = mAttachInfo.mTreeObserver.dispatchOnPreDraw() || !isViewVisible;

    if (!cancelDraw && !newSurface) {
        if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
            for (int i = 0; i < mPendingTransitions.size(); ++i) {
                mPendingTransitions.get(i).startChangingAnimations();
            }
            mPendingTransitions.clear();
        }

        // 调用performDraw()方法
        performDraw();
    } else {
        if (isViewVisible) {
            scheduleTraversals();
        } else if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
            for (int i = 0; i < mPendingTransitions.size(); ++i) {
                mPendingTransitions.get(i).endChangingAnimations();
            }
            mPendingTransitions.clear();
        }
    }

    mIsInTraversal = false;
}
```

## `ViewRootImpl.relayoutWindow()` 方法
```
private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,
        boolean insetsPending) throws RemoteException {

    float appScale = mAttachInfo.mApplicationScale;
    boolean restore = false;
    if (params != null && mTranslator != null) {
        restore = true;
        params.backup();
        mTranslator.translateWindowLayout(params);
    }

    mPendingConfiguration.seq = 0;
    if (params != null && mOrigWindowType != params.type) {
        if (mTargetSdkVersion < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            params.type = mOrigWindowType;
        }
    }
    int relayoutResult = mWindowSession.relayout(
            mWindow, mSeq, params,
            (int) (mView.getMeasuredWidth() * appScale + 0.5f),
            (int) (mView.getMeasuredHeight() * appScale + 0.5f),
            viewVisibility, insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0,
            mWinFrame, mPendingOverscanInsets, mPendingContentInsets, mPendingVisibleInsets,
            mPendingStableInsets, mPendingOutsets, mPendingBackDropFrame, mPendingConfiguration,
            mSurface);

    mPendingAlwaysConsumeNavBar =
            (relayoutResult & WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR) != 0;

    if (restore) {
        params.restore();
    }

    if (mTranslator != null) {
        mTranslator.translateRectInScreenToAppWinFrame(mWinFrame);
        mTranslator.translateRectInScreenToAppWindow(mPendingOverscanInsets);
        mTranslator.translateRectInScreenToAppWindow(mPendingContentInsets);
        mTranslator.translateRectInScreenToAppWindow(mPendingVisibleInsets);
        mTranslator.translateRectInScreenToAppWindow(mPendingStableInsets);
    }
    return relayoutResult;
}
```

## `ViewRootImpl.performMeasure()` 方法
```
private void performMeasure(int childWidthMeasureSpec, int childHeightMeasureSpec) {
    Trace.traceBegin(Trace.TRACE_TAG_VIEW, "measure");
    try {
        // 调用View.measure()方法
        mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    } finally {
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }
}
```

## `ViewRootImpl.performLayout()` 方法
```
private void performLayout(WindowManager.LayoutParams lp, int desiredWindowWidth,
        int desiredWindowHeight) {
    mLayoutRequested = false;
    mScrollMayChange = true;
    mInLayout = true;

    final View host = mView;

    Trace.traceBegin(Trace.TRACE_TAG_VIEW, "layout");
    try {
        host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

        mInLayout = false;
        int numViewsRequestingLayout = mLayoutRequesters.size();
        if (numViewsRequestingLayout > 0) {
            ArrayList<View> validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters,
                    false);
            if (validLayoutRequesters != null) {
                mHandlingLayoutInLayoutRequest = true;

                int numValidRequests = validLayoutRequesters.size();
                for (int i = 0; i < numValidRequests; ++i) {
                    final View view = validLayoutRequesters.get(i);
                    view.requestLayout();
                }
                measureHierarchy(host, lp, mView.getContext().getResources(),
                        desiredWindowWidth, desiredWindowHeight);
                mInLayout = true;
                host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

                mHandlingLayoutInLayoutRequest = false;

                validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters, true);
                if (validLayoutRequesters != null) {
                    final ArrayList<View> finalRequesters = validLayoutRequesters;
                    getRunQueue().post(new Runnable() {
                        @Override
                        public void run() {
                            int numValidRequests = finalRequesters.size();
                            for (int i = 0; i < numValidRequests; ++i) {
                                final View view = finalRequesters.get(i);
                                view.requestLayout();
                            }
                        }
                    });
                }
            }

        }
    } finally {
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }
    mInLayout = false;
}
```

## `ViewRootImpl.performDraw()` 方法
```
private void performDraw() {
    if (mAttachInfo.mDisplayState == Display.STATE_OFF && !mReportNextDraw) {
        return;
    }
    final boolean fullRedrawNeeded = mFullRedrawNeeded;
    mFullRedrawNeeded = false;
    mIsDrawing = true;
    Trace.traceBegin(Trace.TRACE_TAG_VIEW, "draw");
    try {
        // 调用draw()方法
        draw(fullRedrawNeeded);
    } finally {
        mIsDrawing = false;
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    if (mAttachInfo.mPendingAnimatingRenderNodes != null) {
        final int count = mAttachInfo.mPendingAnimatingRenderNodes.size();
        for (int i = 0; i < count; i++) {
            mAttachInfo.mPendingAnimatingRenderNodes.get(i).endAllAnimators();
        }
        mAttachInfo.mPendingAnimatingRenderNodes.clear();
    }

    if (mReportNextDraw) {
        mReportNextDraw = false;

        if (mWindowDrawCountDown != null) {
            try {
                mWindowDrawCountDown.await();
            } catch (InterruptedException e) {...}
            mWindowDrawCountDown = null;
        }

        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.fence();
            mAttachInfo.mHardwareRenderer.setStopped(mStopped);
        }

        if (mSurfaceHolder != null && mSurface.isValid()) {
            mSurfaceHolderCallback.surfaceRedrawNeeded(mSurfaceHolder);
            SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
            if (callbacks != null) {
                for (SurfaceHolder.Callback c : callbacks) {
                    if (c instanceof SurfaceHolder.Callback2) {
                        ((SurfaceHolder.Callback2)c).surfaceRedrawNeeded(mSurfaceHolder);
                    }
                }
            }
        }
        try {
            mWindowSession.finishDrawing(mWindow);
        } catch (RemoteException e) {}
    }
}
```

## `ViewRootImpl.draw()` 方法
```
private void draw(boolean fullRedrawNeeded) {
    Surface surface = mSurface;
    if (!surface.isValid()) {
        return;
    }

    if (!sFirstDrawComplete) {
        synchronized (sFirstDrawHandlers) {
            sFirstDrawComplete = true;
            final int count = sFirstDrawHandlers.size();
            for (int i = 0; i< count; i++) {
                mHandler.post(sFirstDrawHandlers.get(i));
            }
        }
    }

    scrollToRectOrFocus(null, false);

    if (mAttachInfo.mViewScrollChanged) {
        mAttachInfo.mViewScrollChanged = false;
        mAttachInfo.mTreeObserver.dispatchOnScrollChanged();
    }

    boolean animating = mScroller != null && mScroller.computeScrollOffset();
    final int curScrollY;
    if (animating) {
        curScrollY = mScroller.getCurrY();
    } else {
        curScrollY = mScrollY;
    }
    if (mCurScrollY != curScrollY) {
        mCurScrollY = curScrollY;
        fullRedrawNeeded = true;
        if (mView instanceof RootViewSurfaceTaker) {
            ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
        }
    }

    final float appScale = mAttachInfo.mApplicationScale;
    final boolean scalingRequired = mAttachInfo.mScalingRequired;

    int resizeAlpha = 0;

    final Rect dirty = mDirty;
    if (mSurfaceHolder != null) {
        dirty.setEmpty();
        if (animating && mScroller != null) {
            mScroller.abortAnimation();
        }
        return;
    }

    if (fullRedrawNeeded) {
        mAttachInfo.mIgnoreDirtyState = true;
        dirty.set(0, 0, (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
    }

    mAttachInfo.mTreeObserver.dispatchOnDraw();

    int xOffset = -mCanvasOffsetX;
    int yOffset = -mCanvasOffsetY + curScrollY;
    final WindowManager.LayoutParams params = mWindowAttributes;
    final Rect surfaceInsets = params != null ? params.surfaceInsets : null;
    if (surfaceInsets != null) {
        xOffset -= surfaceInsets.left;
        yOffset -= surfaceInsets.top;
        dirty.offset(surfaceInsets.left, surfaceInsets.right);
    }

    boolean accessibilityFocusDirty = false;
    final Drawable drawable = mAttachInfo.mAccessibilityFocusDrawable;
    if (drawable != null) {
        final Rect bounds = mAttachInfo.mTmpInvalRect;
        final boolean hasFocus = getAccessibilityFocusedRect(bounds);
        if (!hasFocus) {
            bounds.setEmpty();
        }
        if (!bounds.equals(drawable.getBounds())) {
            accessibilityFocusDirty = true;
        }
    }

    mAttachInfo.mDrawingTime =
            mChoreographer.getFrameTimeNanos() / TimeUtils.NANOS_PER_MS;

    if (!dirty.isEmpty() || mIsAnimating || accessibilityFocusDirty) {
        if (mAttachInfo.mHardwareRenderer != null && mAttachInfo.mHardwareRenderer.isEnabled()) {
            boolean invalidateRoot = accessibilityFocusDirty || mInvalidateRootRequested;
            mInvalidateRootRequested = false;
            mIsAnimating = false;
            if (mHardwareYOffset != yOffset || mHardwareXOffset != xOffset) {
                mHardwareYOffset = yOffset;
                mHardwareXOffset = xOffset;
                invalidateRoot = true;
            }
            if (invalidateRoot) {
                mAttachInfo.mHardwareRenderer.invalidateRoot();
            }
            dirty.setEmpty();
            final boolean updated = updateContentDrawBounds();
            if (mReportNextDraw) {
                mAttachInfo.mHardwareRenderer.setStopped(false);
            }

            if (updated) {
                requestDrawWindow();
            }
            // 调用 ThreadedRenderer.draw()方法
            mAttachInfo.mHardwareRenderer.draw(mView, mAttachInfo, this);
        } else {
            if (mAttachInfo.mHardwareRenderer != null &&
                    !mAttachInfo.mHardwareRenderer.isEnabled() &&
                    mAttachInfo.mHardwareRenderer.isRequested()) {
                try {
                    mAttachInfo.mHardwareRenderer.initializeIfNeeded(
                            mWidth, mHeight, mAttachInfo, mSurface, surfaceInsets);
                } catch (OutOfResourcesException e) {
                    handleOutOfResourcesException(e);
                    return;
                }

                mFullRedrawNeeded = true;
                scheduleTraversals();
                return;
            }

            if (!drawSoftware(surface, mAttachInfo, xOffset, yOffset, scalingRequired, dirty)) {
                return;
            }
        }
    }

    if (animating) {
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }
}
```

## `ThreadedRenderer.draw()` 方法
```
void draw(View view, AttachInfo attachInfo, HardwareDrawCallbacks callbacks) {
    attachInfo.mIgnoreDirtyState = true;
    final Choreographer choreographer = attachInfo.mViewRootImpl.mChoreographer;
    choreographer.mFrameInfo.markDrawStart();
    updateRootDisplayList(view, callbacks);
    attachInfo.mIgnoreDirtyState = false;

    if (attachInfo.mPendingAnimatingRenderNodes != null) {
        final int count = attachInfo.mPendingAnimatingRenderNodes.size();
        for (int i = 0; i < count; i++) {
            registerAnimatingRenderNode(
                    attachInfo.mPendingAnimatingRenderNodes.get(i));
        }
        attachInfo.mPendingAnimatingRenderNodes.clear();
        attachInfo.mPendingAnimatingRenderNodes = null;
    }

    final long[] frameInfo = choreographer.mFrameInfo.mFrameInfo;

    // 调用nSyncAndDrawFrame()方法
    int syncResult = nSyncAndDrawFrame(mNativeProxy, frameInfo, frameInfo.length);
    if ((syncResult & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
        setEnabled(false);
        attachInfo.mViewRootImpl.mSurface.release();
        attachInfo.mViewRootImpl.invalidate();
    }
    if ((syncResult & SYNC_INVALIDATE_REQUIRED) != 0) {
        attachInfo.mViewRootImpl.invalidate();
    }
}
```

## `android_view_ThreadedRenderer_syncAndDrawFrame()` 函数
```
static int android_view_ThreadedRenderer_syncAndDrawFrame(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlongArray frameInfo, jint frameInfoSize) {

    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    ScopedRemovedRenderNodeObserver observer(env);
    env->GetLongArrayRegion(frameInfo, 0, frameInfoSize, proxy->frameInfo());

    return proxy->syncAndDrawFrame(&observer);
}
```

## `RenderProxy::syncAndDrawFrame()` 函数
```
int RenderProxy::syncAndDrawFrame(TreeObserver* observer) {
    return mDrawFrameTask.drawFrame(observer);
}
```
