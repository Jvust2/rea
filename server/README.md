# 可选的 FlowSave 私人下载服务器

本目录是可部署的示例，**尚未为你部署**。默认APK使用直连。部署者需要自有Linux服务器、指向它的域名、Docker Compose，以及能访问目标平台的服务器网络。仅供本人使用，不应开放为匿名下载站。

## 部署

从完整源码包解压，根目录应有 `android-app/app/src/main/python/yt_dlp/`。从Git仓库自行构建时，先将 `Jvust2/yt-dlp` 对应版本的 `yt_dlp` 目录复制到该位置（构建清单记录了版本）。

在 `server/` 下创建 `.env`，不要提交到Git：

```
FLOWSAVE_DOMAIN=download.your-domain.example
FLOWSAVE_TOKEN=这里替换为自己生成的至少32字符随机令牌
```

生成随机令牌：`python -c 'import secrets; print(secrets.token_urlsafe(48))'`。

在 `server/` 执行：

```
docker compose up --build -d
```

Caddy负责HTTPS证书，须配置正确域名并允许80/443入口。应用8080端口只在容器网络暴露，不应映射到公网。打开APK → 网络设置 → 自建服务器，填写 `https://你的域名` 和同一个令牌。

## 协议与数据

- `POST /v1/jobs`：仅接受 `action,url,media,quality`，返回任务ID。
- `GET /v1/jobs/{id}`：返回状态与文件清单。
- `GET /v1/jobs/{id}/files/{index}`：传输文件，手机验证长度和SHA256。
- `DELETE /v1/jobs/{id}`：取消并删除任务数据。
- 所有任务接口均需 `Authorization: Bearer <令牌>`。

不接收手机的Cookies、代理凭据、执行命令或任意运行参数。服务器模式默认仅下载可公开访问的内容；需要登录的内容可能失败。服务器操作者能够看到提交的链接及文件，不要使用不可信的他人服务器。

## 安全与资源边界

单工作进程，最多4个未完成任务；CPU/时间/内存/文件大小受限。任务最多保留1小时，手机下载结束会请求删除。初始URL只允许六个平台域；工作进程检查实际连接的IP，拒绝回环、私网、链路本地、云元数据地址及重定向到这些地址。媒体合并只允许本地文件协议。

这些是纵深防御，不是完整沙箱。生产部署仍须使用出站防火墙阻止RFC1918、链路本地、元数据网段和不需要的端口，保持系统与依赖更新，监控磁盘容量并设置HTTPS入口速率限制。共享令牌适合一个可信用户，不提供多用户隔离。无认证的 `/health` 只返回服务名和版本。

在可达的服务器上配置并不等于能绕过目标站的地区、账号、验证码、会员或DRM限制。本程序不提供这些绕过功能。
