# jaudiotagger 3.0.1 源码

- Maven 坐标：`net.jthink:jaudiotagger:3.0.1`。
- 本目录的 `jaudiotagger-3.0.1-sources.jar` 是 Maven Central 发布的原始源码包，未修改。
- 来源：<https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar>。
- 上游仓库：<https://github.com/ijabz/jaudiotagger>；发布 POM 标注源码标签 `v3.0.1`。
- 许可：LGPL-2.1-or-later，全文见 `app/src/main/assets/licenses/jaudiotagger-LGPL-2.1.txt`，应用内“设置 → 开源许可”也可阅读。
- 应用的 Android 适配代码位于 `app/src/main/java/com/example/xuebimc/MusicMetadataEmbedder.kt`；未修改 jaudiotagger 库本身。

源码包 SHA-256：

```text
d9c79a145944e6bc37579403843c9da84cd81459e42c9877351802ee284f5858
```

已通过 ZIP 完整性检查；SHA-1 与 Maven Central 公布值一致：`6c7c74cfafc64599ae3c709f1530921a375a72ed`。

## 重建和替换库

源码包内含发布 POM。安装 JDK 8 或更新版本、Maven 后，可从本工程根目录在独立工作目录中还原上游的 `src` 布局：

```sh
mkdir jaudiotagger-edit
cd jaudiotagger-edit
jar xf ../third_party/jaudiotagger-3.0.1/jaudiotagger-3.0.1-sources.jar
mkdir src
mv org src/
cp META-INF/maven/net.jthink/jaudiotagger/pom.xml pom.xml
mvn -Dmaven.test.skip=true package
```

需要修改时，编辑该工作目录下的 Java 源码后重新执行 Maven 命令。将生成的 `target/jaudiotagger-3.0.1.jar` 复制到应用的 `app/libs/jaudiotagger-custom.jar`，并在 `app/build.gradle.kts` 中将原 Maven 依赖替换为：

```kotlin
implementation(files("libs/jaudiotagger-custom.jar"))
```

不要同时保留原 Maven 依赖和替换 JAR，以免出现重复类。按工程根目录 README 配置 Android SDK/JDK，再从工程根目录执行 `./gradlew assembleDebug`，即可把替换库重新链接进应用。本说明未执行重建库操作。

使用自己的签名构建；源码包不包含发布私钥。签名不同的安装包无法直接覆盖原应用，可改用不同的 `applicationId` 并存测试，避免删除用户数据。再分发修改后的库时，请保留许可、对应源码及修改说明，并遵守 LGPL 的相关要求。
