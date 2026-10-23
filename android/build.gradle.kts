// 版本对齐关系是本工程最容易一次编不过的地方（本机没有 JDK/SDK，只能靠 CI）：
//   AGP 8.7.x 需要 Gradle 8.9+ 与 JDK 17；compileSdk 35 需要 AGP 8.6+。
// 升级其中任何一个之前先确认另外两个。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
