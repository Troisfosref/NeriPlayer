# QQ 音乐首期接入

本补丁基于 `Troisfosref/NeriPlayer` 的 `ebaecd6b70bbe0d7d6a5d24df93c3e23f6e8df2d`。

## 使用

1. 打开“设置 → 第三方平台登录 → QQ 音乐”。
2. 使用手机 QQ 扫描二维码并确认，或粘贴包含音乐账号 ID 和音乐登录凭据的 Cookie。
3. 在该面板选择标准（128 kbps）、高品质（320 kbps）或无损作为首选音质。
4. 在探索页选择 QQ 音乐并输入歌名或歌手。支持分页加载、混合队列、加入本地歌单、歌词和下载。
5. QQ 歌曲的 `https://y.qq.com/n/ryqq/songDetail/MID` 与含 `songmid` 的网页链接可用于链接识别。

在线播放和下载使用平台为当前账号签发的票据。高档位没有可用完整音源时，依次尝试较低档位。
播放页显示实际解析到的档位。QQ 音质在账号面板设置，当前播放页没有即时切换 QQ 档位的菜单。
修改后在下一次解析时生效；已有歌曲缓存按首选档位区分。

账号凭据使用独立的 Android 加密存储，不进入歌单、历史、同步文件或分享链接。
扫码会话只存于当前面板内存中；关闭面板停止轮询，应用进入后台也暂停轮询。
QQConnect 的临时票据按域名、路径和有效期隔离；同名的过期 Cookie 不会覆盖其他域名下的有效票据。
刷新二维码会创建独立会话，临时扫码 Cookie 不会写入音乐账号凭据。

扫码确认后若仍失败，错误后会附简短编号：`QQ-01` 为状态查询，`QQ-02` 为登录票据，
`QQ-03` 为授权会话激活，`QQ-04` 为 OAuth 授权，`QQ-05` 为音乐账号换票。
编号不包含 Cookie 或授权码，反馈问题时可提供该编号。

## 首期范围

已接入 QQ 扫码和 Cookie 登录、歌曲搜索、歌曲详情、完整音源解析、标准 / 高品质 / 无损降档、
来源标识、歌词与翻译、本地歌单、下载及歌曲链接分享。

尚未接入 QQ 云端歌单、平台收藏双向同步、推荐首页、微信 / 短信登录、登录自动续期及一起听。
QQ 歌曲会从一起听分享队列中排除，避免被当作网易云歌曲处理。

## 实现

- `modules/model/.../qqmusic/QQMusicSongRef.kt`：目录 MID 和媒体文件 MID 分离，不使用兼容长整型 ID 请求平台接口。
- `modules/platform/.../qqmusic/api`：客户端协议、播放票据、音质规格和响应校验。
- `modules/platform/.../qqmusic/auth`：QQConnect OAuth、扫码会话和独立加密凭据。
- `modules/platform/.../qqmusic/QQMusicRepository.kt`：歌曲映射、分页、串行换票和音质降档。
- 宿主通过播放器、下载器已有接口接入，复用现有的本地歌单、队列、歌词解析和存储。

请求格式及 OAuth 顺序参考 MusicOne QQ 音源，来源与许可证见 `THIRD_PARTY_LICENSES.md`。

## 验证

完整 Android 环境中执行：

```bash
python3 -B tools_pub/quality/module_boundaries.py
./gradlew :platform:testDebugUnitTest --tests 'moe.ouom.neriplayer.platform.qqmusic.*'
./gradlew :app:testDebugUnitTest --tests '*ExploreScreenYouTubeGateTest' --tests '*SettingsAccountAuthControllerTest'
./gradlew :app:assembleDebug :app:lintDebug
```

本次已完成：

- 模块边界检查、补丁空白检查。
- 使用 Kotlin 2.4.10 编译新增 QQ 平台源码及其真实模型、网络依赖。
- 16 项 JVM 测试覆盖账号字段优先级、目录 / 媒体 MID、URL 校验、OAuth state、
  歌曲映射、同步身份恢复、逐档回退、登录失效，以及扫码确认后的完整模拟换票流程。
- 登录回归覆盖同名 Cookie 的跨域删除、授权跳转参数、会话隔离和错误编号；
  旧代码在相同模拟响应中复现“确认登录后请求失败”，修复后通过。
- 公共搜索、歌曲详情及扫码图片接口返回正常；这不等同于已完成用户扫码登录和授权播放验证。

完整 Android 工程打包与自动化回归结果见仓库 Actions 和接入 PR。
Compose 界面、加密存储实机行为、扫码确认后的授权播放、下载和后台恢复仍需结合实机检查。
