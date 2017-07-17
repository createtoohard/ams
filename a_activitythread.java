【ActivityManagerService】

【涉及到的类】
[---ActivityThread]
    frameworks/base/core/java/android/app/ActivityThread.java

[---ActivityClientRecord]
    //ActivityThread的静态内部类
    frameworks/base/core/java/android/app/ActivityThread.java

[---ProviderClientRecord]
    //ActivityThread的静态内部类
    frameworks/base/core/java/android/app/ActivityThread.java

[---ApplicationThread]
    /**
     *1.ActivityThread的内部类，继承ApplicationThreadNative类
     * 而ApplicationThreadNative类继承Binder类，实现了IApplicationThread类
     * 从而导致了ApplicationThread类是一个Binder服务类
     *
     *2.ActivityManagerService操作应用就是通过ApplicationThread提供的结构完成的
     *
     *3.ActivityThread类定义了大量的接口，但是接口的实现都是把Binder调用转换成消息队列处理
     * 这样能防止应用处理消息的时间过长而影响整个系统的运行
     * 而ActivityThread处理消息的类H中，都是调用ActivityThread中的某个方法来完成的
     *
     *4.ApplicationThread中的接口方法都是以“schedule”开头，而ActivityThread中对象的处理方法则以“handle”开头
     */
    frameworks/base/core/java/android/app/ActivityThread.java

[---Context]
    frameworks/base/core/java/android/content/Context.java

[---ContextWrapper]
    frameworks/base/core/java/android/content/ContextWrapper.java

[---ContextImpl]
    frameworks/base/core/java/android/app/ContextImpl.java

[---Application]
    frameworks/base/core/java/android/app/Application.java




【应用进程的组成】
android应用进程的核心是ActivityThread类，这个类包含了应用框架中其他重要的类

[---ActivityThread][ActivityThread]
//成员变量mActivities、mServices、mProviderMap3个变量分别保存了应用中所有的Activity对象、Service对象、ContentProvider对象
//没有数据结构来保存BroadcastReceiver对象，因为BroadcastReceiver对象的生命周期很短暂，
//属于调用一次运行一次，因此不需要保存其对象
public final class ActivityThread {

    [---mActivities][ActivityThread]
    //保存应用中所有Activity对象，ActivityClientRecord为ActivityThreads的静态内部类
    final ArrayMap<IBinder, ActivityClientRecord> mActivities = new ArrayMap<>();

    [---mServices][ActivityThread]
    //保存应用中所有Service对象，
    final ArrayMap<IBinder, Service> mServices = new ArrayMap<>();

    [---mProviderMap][ActivityThread]
    //保存应用中所有ContentProvider对象，ProviderClientRecord为ActivityThread的静态内部类
    final ArrayMap<ProviderKey, ProviderClientRecord> mProviderMap
        = new ArrayMap<ProviderKey, ProviderClientRecord>();

    [---mInitialApplication][ActivityThread]
    //mInitialApplication是一个Application对象。应用中Application对象只有一个，
    //如果某个应用从Application类派生了自己的类，mInitialApplication对象将是应用中自定义类的实例对象
    Application mInitialApplication;

    [---mAppThread][ActivityThread]
    //mAppThread是ApplicationThread的实例对象，ApplicationThread是ActivityThread的内部类
    //ApplicationThread是一个Binder实体对象，ActivityManagerService通过它来调用接口
    final ApplicationThread mAppThread = new ApplicationThread();

    [---mPackages][ActivityThread]
    //包含代码的apk文件保存在变量mPackages中，通过应用标签<application>的属性hasCode来区分apk中是否包含代码
    final ArrayMap<String, WeakReference<LoadedApk>> mPackages
            = new ArrayMap<String, WeakReference<LoadedApk>>();

    [---mResourcePackages][ActivityThread]
    //只包含资源的apk文件保存在mResourcePackages变量中
    final ArrayMap<String, WeakReference<LoadedApk>> mResourcePackages
            = new ArrayMap<String, WeakReference<LoadedApk>>();
}


【Context】
[---Context][Context]

1.应用的上层代码通过Context类提供的接口来操作Android的四大组件和资源，Android的应用本质上是一个四大组件加上资源文件的容器
2.Context是一个抽象类，它有两个直接子类，分别是 ContextWrapper 和 ContextImpl
3.Context的真正实现的类是ContextImpl，ContextWrapper是一个代理类，方法的实现还是通过调用ContextImpl对应的方法来实现的
4.ContextWrapper有3个子类，分别是Service、ContextThemeWrapper和Application
5.Activity则是ContextThremeWrapper的子类，这样实现的目的是为了实现Activity中单独的Theme
6.一个应用中可以有一套全局的Theme，同时每个Activity还可以有自己的Theme，ContextThemeWrapper类的成员变量中保存Theme资源，所以子类Activity就能拥有独立的Theme



















































ActivityManagerService(AMS)是Android提供的一个用于管理Activity（和其他组件）运行状态的系统进程，也是我们编写APK应用程序时使用得最频繁的一个系统服务。
1.AMS功能概述
AMS和WMS一样，也是寄存与systemServer中的，它会在系统启动时，创建一个线程来循环处理客户的请求。AMS会向ServiceManager登记多种Binder Server，只有第一个“activity”才是AMS的“主业”，并由ActivityManagerervice实现。



2.AMS提供的功能（ActivityManager.java）
（1）组件状态管理
组件（四大组件）的状态管理包括组件的开启、关闭等一系列操作，如：startActivity，startActivityAndWait,activityPaused,startService,stopService,removeContentProvider等。
（2）组件状态查询
这类函数用于查询组件当前的运行情况，如getCallingActivity，getServices等。
（3）Task相关
Task相关的函数包括removeSubTask,removeTask,moveTaskBackwards,moveTaskToFront等
（4）其他
AMS还提供了不少辅助功能，如系统运行时信息的查询（getMemoryInfo,setDebugApp等）。

3.管理当前系统中Activity状态（ActivityStack）
