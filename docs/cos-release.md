# GitHub Release 同步到腾讯云 COS

`Build` 工作流完成正式 Release 发布后，会调用 `Publish Release to COS`，从 GitHub Release 下载签名 APK、AAB 和 SHA256SUMS，校验文件后上传到 COS。不会上传 debug 包。版本信息的 `download_url` 直接指向 GitHub Release 正式 APK。

也可以在 Actions → **Publish Release to COS** → **Run workflow** 手动补传已有版本；选择 `main`，tag 填 `v1.7.1`，留空则同步最新正式版。补传旧版只写该版本的归档目录，不覆盖最新版下载入口。

## GitHub Environment 配置

在仓库 **Settings → Environments** 新建 **`cos-release`**。所有腾讯云配置都放在这个 Environment 中，密钥不写入代码。

Environment secrets：

| 名称 | 内容 |
| --- | --- |
| `COS_SECRET_ID` | 腾讯云 SecretId，必填 |
| `COS_SECRET_KEY` | 腾讯云 SecretKey，必填 |
| `COS_SESSION_TOKEN` | 使用临时 STS 凭据时填写，永久密钥留空 |

Environment variables：

| 名称 | 内容 |
| --- | --- |
| `COS_BUCKET` | 完整桶名，含 APPID，例如 `termish-1250000000`，必填 |
| `COS_REGION` | 桶所在地域，例如 `ap-guangzhou`，必填 |
| `COS_PREFIX` | 上传目录前缀，默认 `downloads` |
| `COS_PUBLIC_BASE_URL` | COS 归档的自定义域名根地址，例如 `https://downloads.example.com`；留空使用 COS 标准 HTTPS 域名，不影响 GitHub 下载直链 |

GitHub 文件下载直接使用工作流提供的 `GITHUB_TOKEN`（`contents: read`），不需要另配 GitHub PAT。腾讯云账号需要具备目标前缀下的 `cos:PutObject` 权限。上传脚本不创建桶、不删除旧版本，也不修改桶的访问权限。

官网直接读取的 `downloads/version.json`（以及使用时的 `downloads/release.json`）需已允许公众读取，或由配置的 CDN/下载域名提供读取。若官网跨域获取 JSON，请为官网域名配置 COS/CDN 的 GET CORS。安装包默认从 GitHub 下载，COS 归档可以保持私有。若 Environment 配置了部署分支限制，允许 `main`（手动补传）和 `v*` 标签（自动发布）。

注意：新建存储桶的 COS 默认域名不能分发 APK/IPA，会返回 `403 DownloadForbidden`。若要使用 COS 的安装包下载入口，需绑定自定义域名或 CDN，配置 `COS_PUBLIC_BASE_URL`，并允许相应安装包读取。参见 [腾讯云域名使用限制](https://cloud.tencent.com/document/product/436/96243)。JSON 返回 `403 AccessDenied` 则需检查读取权限，参见 [匿名访问配置](https://cloud.tencent.com/document/product/436/68285)。

配置缺失时上传任务会明确失败，GitHub Release 已上传的文件仍然保留；补齐配置后手动补传即可，无需重新编译应用。

## 上传内容

默认 `COS_PREFIX=downloads`，以 `v1.7.1` 为例：

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

`version.json` 与 `release.json` 包含版本、发布日期、下载地址、大小和 SHA-256：

```json
{
  "version": "v1.7.1",
  "version_name": "1.7.1",
  "download_url": "https://github.com/ttermish/termish/releases/download/v1.7.1/Termish-1.7.1-release.apk",
  "cos_download_url": "https://downloads.example.com/downloads/releases/v1.7.1/Termish-1.7.1-release.apk",
  "github_download_url": "https://github.com/ttermish/termish/releases/download/v1.7.1/Termish-1.7.1-release.apk",
  "release_url": "https://github.com/ttermish/termish/releases/tag/v1.7.1",
  "sha256": "文件的 SHA-256",
  "assets": {"apk": {}, "aab": {}}
}
```

官网读取 `version.json` 的 `download_url` 即可直接下载 GitHub Release 正式 APK；`github_download_url` 保留为同地址的兼容字段。`cos_download_url` 和 `assets.*.url` 是 COS 归档地址。固定对象 `/downloads/termish.apk` 同样来自正式 Release，通过 COS 下载时也需要上述域名和读取配置。

所有文件先校验 GitHub Release 的 SHA256SUMS，再写入版本归档。只有最新正式版会更新固定入口，并且最后写入 `version.json`，避免版本信息先更新而包尚未上传。版本归档长期缓存，固定入口和 JSON 使用 `Cache-Control: no-cache`；CDN 应尊重这些响应头。

## 本地验证

上传逻辑测试不需要腾讯云密钥，也不会访问云端：

```bash
python3 -m unittest discover -s scripts/tests -v
```

参考：[腾讯云 COS Python SDK](https://cloud.tencent.com/document/product/436/12269)、[GitHub 可复用工作流与 Environment secrets](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)。使用可复用工作流直接接在发布任务后，避免 `GITHUB_TOKEN` 创建 Release 时不触发新的 `release` 事件工作流的问题。
