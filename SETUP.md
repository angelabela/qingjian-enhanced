# 接入步骤

## 1. build.gradle（版本号请取最新）
```
implementation("com.google.mlkit:translate:17.0.3")
implementation("com.google.mlkit:language-id:17.0.6")
```

## 2. AndroidManifest.xml
```xml
<service
    android:name=".SpriteTranslateService"
    android:exported="false"
    android:label="小精灵翻译"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/sprite_service" />
</service>
```

## 3. 资源
- `res/xml/sprite_service.xml`：已提供
- `res/values/strings.xml` 加：`<string name="sprite_service_desc">读取屏幕文字并翻译成英文</string>`
- `res/drawable/ic_sprite`：放你的小精灵图标

## 4. 启用
系统设置 → 无障碍 → 已下载的应用 → 小精灵翻译 → 打开。
无障碍服务自带悬浮层权限，不需要再申请"显示在其他应用上层"。
