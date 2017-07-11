# Android应用启动流程

# Android应用启动流程图
```puml
Title : Android应用启动流程图
Launcher -> Launcher : startAppShortcutOrInfoActivity()
Launcher -> Launcher : startActivitySafely()
Launcher -> Launcher : startActivity()
Launcher -> ContextImpl : startActivity()
```

## `ContextImpl.startActivity()`方法
```java
public void startActivity(Intent intent, Bundle options) {
        warnIfCallingFromSystemProcess();

        // Calling start activity from outside an activity without FLAG_ACTIVITY_NEW_TASK is
        // generally not allowed, except if the caller specifies the task id the activity should
        // be launched in.
        if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) == 0
                && options != null && ActivityOptions.fromBundle(options).getLaunchTaskId() == -1) {
            throw new AndroidRuntimeException(
                    "Calling startActivity() from outside of an Activity "
                    + " context requires the FLAG_ACTIVITY_NEW_TASK flag."
                    + " Is this really what you want?");
        }
        mMainThread.getInstrumentation().execStartActivity(
                getOuterContext(), mMainThread.getApplicationThread(), null,
                (Activity) null, intent, -1, options);
    }
```
