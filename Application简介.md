# Application 简介
* Application类可以看作是应用本身的抽象，一个应用进程只能有一个Application对象，在应用启动时由框架创建。
* Application本身没有实现太多的功能，主要是提供一些回调接口来通知应用进程状态的变化

1. `onCreate()`:应用创建时调用
2. `onTerminate()`:在应用销毁时调用
3. `onConfigurationChanged()`:在系统配置发生变化时调用
4. `onLowMemory()`:在系统内存不足时调用
5. `onTrimMemory(int level)`:在系统要求应用释放多余内存时调用

# Application 标签
**应用中可以定义Application的派生类，派生类必须在AndroidManifest.xml的<application>标签中定义，这样系统才能生成应用对象时使用子类来代替Application类**
```xml
<application
android:allowTaskReparenting=["true" | "false"]
android:allowBackup=["true" | "false"]
android:backupAgent="string"
android:debuggable=["true" | "false"]
android:description="string resource"
android:enabled=["true" | "false"]
android:hasCode=["true" | "false"]
android:hardwareAccelerated=["true" | "false"]
android:killAfterRestore=["true" | "false"]
android:largeHeap=["true" | "false"]
android:logo="drawable resource"
android:manageSpaceActivity="string"
android:name="string"
android:permission="string"
android:process="string"
android:restoreAnyVersion=["true" | "false"]
android:taskAffinity="string"
android:testOnly=["true" | "false"]
android:uiOptions=["none" | "splitActionBarWhenNarrow"]
android:vmSafeMode=["true" | "false"]
android:icon="drawable resource"
android:label="string resource"
android:supportsRtl=["true" | "false"]
android:theme="resource or theme">
</application>
```

1. `allowTaskReparenting`:
    指示Activity能否从启动它的task移动到有着密切关系并且即将被带到前台的task，默认值为false
2. `allowBackup`:
    指示应用数据能否被系统备份和还原，如果值为false，即使系统执行全备份也不会备份该应用的数据，缺省值为true
3. `backupAgent`:
    定义应用程序备份代理的实现类名称，这个类是BackupAgent类的一个子类，属性中定义的类名必须是全限定类名（包括包名）
4. `debuggable`:
    指示应用程序是否能调试，即使android系统以user模式运行，如果该属性设置为true，应用也能够被调试，默认为false
5. `description`:
    用户可读的，比lable更长、更多的有关应用的描述。它的值必须是一个字符串的引用，不能直接使用字符串
6. `enabled`:
    android系统是否可以实例化应用的组件，值为true表示可以，如果这个值为true，每个组件的enable属性决定了此组件是否可用
7. `hasCode`:
    指示应用中是否包含代码，当值为false时，启动组件时系统将不会尝试加载应用的任何代码，默认为true
8. `hardwareAccelerated`:
    是否为应用中所有Activities和Views开启图形渲染的硬件加速，值为true表示要开启，如果指定sdk>=14，默认为true
9. `icon`:
    应用的图标，也是每个应用组件的默认图标，它的值是drawable资源的引用，没有默认值
10. `killAfterRestore`:
    指定在全系统的恢复操作期间，应用的数据恢复以后，应用程序是否应该终止。单个包的恢复操作不会导致应用程序被关掉。默认值为true
11. `largeHeap`:
    指示是否可以在虚拟机中使用更大尺寸的堆，缺省值为false
12. `label`:
    一个简短的应用标签，也是每个组件的默认标签
13. `logo`:
    应用的logo，也是每个应用组件的默认logo，没有默认值
14. `manageSpaceActivity`:
    一个Activity子类的全限定名称，这个Activity可以被系统启动并用于管理此应用占有的存储空间
15. `name`:
    应用中实现的application子类的全限定名称。当应用启动时，这个类将在应用的其他组件之前被实例化
16. `permission`:
    定义权限字符串，和本应用交互的其他应用必须声明该权限，这个属性是为应用的所有组件设置权限的方便途径，可以被组件的permission属性覆盖
17. `persistent`:
    应用是否在所有时间下都保持运行。默认为false，应用不该设置，持久运行模式仅仅被几个系统应用使用
18. `process`:
    指定进程名称，应用程序的所有组件都应该运行在这个进程中，每个组件都能够设置自己的process属性来覆盖它。两个相同的应用程序的组件可以在同一个进程中运行，但仅限于这两个应用程序有相同的SharedUserId和签名。
19. `restoreAnyVersion`:
    表示应用程序准备尝试恢复任何备份的版本，即使该备份比设备上当前安装的应用程序的版本要新，默认为false
20. `supportsRtl`:
    指示应用是否支持右到做排列的布局，如果值设为true并且targetSdkVersion的值大于等于17，应用将能够使用RTL相关的api来展示右到左排列的布局，缺省值为false
21. `taskAffinity`:
    为应用中所有Activity指定一个亲缘关系名，
22. `test`:
    指示应用是否只能用于测试，指定为true后，应用程序只能通过adb安装
23. `theme`:
    定义应用中所有Activity的默认主题，每个Activity得主题也可以通过设置自己的theme来覆盖它
24. `uiOptions`:
    表示Activity的UI的额外选项，它的值为none，表示没有额外的UI选项，这也是缺省值。值为splitActionBarWhenNarrow表示在水平空间受到限制时，ActionBar将分成上下两部分，顶部用于导航，底部用于操作。
25. `vmSafeMode`:
    指示应用是否要求虚拟机运行在安全模式下，缺省值为false
