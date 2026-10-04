# Cloudflare R2 发布同步

> **English summary:** Node.js 22 validates published release assets, uploads them to R2 using curl SigV4, verifies anonymous downloads and updates the latest version pointer last.

`Build` 工作流创建正式 GitHub Release 后，调用 `Publish Release to R2`，把签名 APK、AAB、SHA256SUMS 和版本 JSON 同步到 R2。官网从 R2 直接下载正式安装包。

## Cloudflare 配置

1. 开通 R2，创建仅存放公开发行文件的桶，存储类型选择 **Standard**。下面的工作流使用默认 jurisdiction 的 S3 endpoint。
2. 桶的 **Settings → Custom Domains** 绑定下载域名，例如 `download.termish.dev`，等状态变成 Active。域名需在同一个 Cloudflare 账号中。
3. 测试阶段也可以启用 **Public Development URL**，使用控制台提供的 `https://pub-….r2.dev` 地址；该地址有限流、不提供 CDN 缓存，正式分发使用自定义域名。
4. 在 R2 的 **Manage API Tokens** 创建该桶专用的 **Object Read & Write** 凭据，保存 **Access Key ID** 和 **Secret Access Key**。这两个值用于 S3 API，与一般的 Cloudflare API token 不同。

如果官网跨域读取版本 JSON，在桶的 **CORS Policy** 配置实际官网来源。例如：

```json
[
  {
    "AllowedOrigins": ["https://termish.dev", "https://www.termish.dev"],
    "AllowedMethods": ["GET", "HEAD"],
    "ExposeHeaders": ["Content-Length", "ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

公开下载不需要放行写入操作。固定 APK/AAB、版本 JSON 和固定校验和使用 `Cache-Control: no-store, max-age=0`，避免自定义域名的浏览器缓存时间设置让最新版下载仍命中旧包。按版本归档的安装包和校验和长期缓存。Cloudflare 缓存规则应尊重这些响应头，不要对固定入口强制缓存。

## GitHub Actions 仓库配置

仓库 **Settings → Secrets and variables → Actions**，在 **Secrets** 和 **Variables** 页签分别添加：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `R2_ACCESS_KEY_ID` | R2 S3 Access Key ID |
| Secret | `R2_SECRET_ACCESS_KEY` | R2 S3 Secret Access Key |
| Variable | `R2_ACCOUNT_ID` | Cloudflare 的 32 位 Account ID |
| Variable | `R2_BUCKET` | R2 桶名，例如 `termish-releases` |
| Variable | `R2_PUBLIC_BASE_URL` | 公开下载的 HTTPS 根域名，例如 `https://download.termish.dev`，不带 `/downloads` |
| Variable | `R2_PREFIX` | 可选，默认 `downloads` |

`R2_PUBLIC_BASE_URL` 不能填 `*.r2.cloudflarestorage.com`，这是需要鉴权的 S3 API 地址，不是公开下载地址。工作流自动用 Account ID 生成上传 endpoint。

GitHub Release 下载使用自动提供的 `GITHUB_TOKEN`，无需额外 PAT。工作流直接读取仓库级配置，无需创建 GitHub Environment。

本地 Android 签名配置保存在项目根 `.env`（已被 Git 忽略）。可以把其中各项密钥批量写入独立的仓库 Secret：

```bash
gh secret set --repo ttermish/termish --env-file .env
```

该命令只上传文件中已有的项，不会删除其他仓库密钥。普通 R2 配置仍放在 Variables；不要把密钥文件导入 Variables，也无需创建合并所有内容的 `ENV_FILE`。CI 从仓库配置注入进程环境变量，R2 发布脚本读取这些变量。

## 发布和补传

新版本按项目现有流程发布，GitHub Release 成功后自动同步 R2。

补传已有版本：**Actions → Publish Release to R2 → Run workflow**，分支选择 `main`，tag 填 `v1.7.1`；留空则同步最新正式版。不需要重新编译应用。

上传脚本先核对 GitHub Release 的 SHA-256、文件大小和 GitHub 提供的 digest，再上传并匿名下载校验每个对象。安装包和归档下载验证通过后，才更新最新入口，`version.json` 最后写入并读取校验。公开下载返回 403/404 或内容不一致会让工作流失败，避免把仅上传成功当作分发成功。

补传旧版本只更新归档，不覆盖最新入口。工作流串行执行，避免并发上传相互覆盖。脚本不创建桶、不删除旧归档，也不修改域名、CORS 或公开访问配置；匿名检查不代替浏览器 CORS 检查。

## 文件与下载地址

```text
downloads/
  version.json
  release.json
  termish.apk
  termish.aab
  SHA256SUMS
  releases/v1.7.1/
    Termish-1.7.1-release.apk
    Termish-1.7.1-release.aab
    SHA256SUMS
    release.json
```

官网读取 `version.json` 的 `download_url`，即可下载 R2 上的签名正式 APK：

```json
{
  "version": "v1.7.1",
  "version_name": "1.7.1",
  "download_url": "https://download.termish.dev/downloads/releases/v1.7.1/Termish-1.7.1-release.apk",
  "github_download_url": "https://github.com/ttermish/termish/releases/download/v1.7.1/Termish-1.7.1-release.apk",
  "sha256": "文件的 SHA-256",
  "assets": {"apk": {}, "aab": {}}
}
```

`assets.apk.url` 和 `assets.aab.url` 均为 R2 下载地址；`github_download_url` 指向对应的 GitHub Release 文件。固定下载对象 `/downloads/termish.apk` 也来自同一个正式 Release。

## 本地验证

无需密钥、不会访问云端的测试：

```bash
node --test scripts/tests/*.test.mjs
```

参考：[R2 S3 凭据](https://developers.cloudflare.com/r2/get-started/s3/)、[公开访问与自定义域名](https://developers.cloudflare.com/r2/buckets/public-buckets/)、[CORS](https://developers.cloudflare.com/r2/buckets/cors/)、[S3 API 兼容性](https://developers.cloudflare.com/r2/api/s3/api/)。
