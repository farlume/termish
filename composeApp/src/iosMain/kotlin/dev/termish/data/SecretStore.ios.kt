package dev.termish.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS 安全存储：系统 Keychain（kSecClassGenericPassword）。
 *
 * 查询字典用 CFDictionaryCreate 构造真正的 CFDictionary：Kotlin/Native 的
 * NSMutableDictionary() 返回的是内部包装类（NSDictionaryAsKMap），强转
 * CFDictionaryRef 必然抛 ClassCastException，异常若穿过 UIKit 手势回调会让
 * 整个 app 触摸失效（表现为点保存后卡死）。字符串用 CFBridgingRetain 桥接，
 * 用完 CFBridgingRelease 释放，与 Apple Security API 的 CF 内存约定一致。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object SecretStore {
    /** errSecMissingEntitlement：app 未签名/未配置 keychain-access-groups 时 Keychain 不可用。 */
    private const val ERR_SEC_MISSING_ENTITLEMENT = -34018

    actual fun get(
        service: String,
        account: String,
    ): String? =
        withCfRetain(service, account) { cfService, cfAccount ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecReturnData to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                )
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFBridgingRelease(query)
            // 只记状态不记 service/account：避免把 host id 与凭据类型的映射写进系统日志
            NSLog("Termish-KEYCHAIN get status=$status")
            if (status != errSecSuccess) {
                if (status == ERR_SEC_MISSING_ENTITLEMENT) {
                    NSLog("Termish-KEYCHAIN get fallback to file (missing entitlement)")
                    return@withCfRetain fallbackRead(account)
                }
                return@withCfRetain null
            }
            val data = CFBridgingRelease(result.value) as? NSData
            NSLog("Termish-KEYCHAIN get data=${data?.length} bytes")
            data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
        }

    actual fun set(
        service: String,
        account: String,
        value: String,
    ) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        withCfRetain(service, account, data) { cfService, cfAccount, cfData ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                )
            val del = SecItemDelete(query)
            CFBridgingRelease(query)
            NSLog("Termish-KEYCHAIN set delete=$del")

            val attrs =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecValueData to cfData,
                )
            val add = SecItemAdd(attrs, null)
            CFBridgingRelease(attrs)
            NSLog("Termish-KEYCHAIN set add=$add")
            if (add == ERR_SEC_MISSING_ENTITLEMENT) {
                NSLog("Termish-KEYCHAIN set fallback to file (missing entitlement)")
                fallbackWrite(account, value)
            }
        }
    }

    actual fun delete(
        service: String,
        account: String,
    ) {
        withCfRetain(service, account) { cfService, cfAccount ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                )
            val del = SecItemDelete(query)
            CFBridgingRelease(query)
            NSLog("Termish-KEYCHAIN delete status=$del")
            if (del == ERR_SEC_MISSING_ENTITLEMENT) {
                fallbackDelete(account)
            }
        }
    }

    // ---------- 沙盒文件兜底 ----------
    // Keychain 需要 keychain-access-groups entitlement；CLI 构建/未签名的模拟器包
    // 会返回 -34018（errSecMissingEntitlement）。此时退到 app 私有目录存储，
    // 便于开发调试；正式签名（Xcode/真机）后自动走 Keychain，不会用到这段。

    private fun fallbackDir(): String {
        val docs =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return ""
        val dir = (docs as NSString).stringByAppendingPathComponent("termish-secrets")
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun fallbackPath(account: String): String = (fallbackDir() as NSString).stringByAppendingPathComponent("$account.txt")

    private fun fallbackRead(account: String): String? =
        NSString
            .create(contentsOfFile = fallbackPath(account), encoding = NSUTF8StringEncoding, error = null)
            ?.toString()

    private fun fallbackWrite(
        account: String,
        value: String,
    ) {
        NSString.create(string = value).writeToFile(fallbackPath(account), true, NSUTF8StringEncoding, null)
    }

    private fun fallbackDelete(account: String) {
        NSFileManager.defaultManager.removeItemAtPath(fallbackPath(account), null)
    }
}

/** 构造真正的 CFDictionary（K/N 的 NSMutableDictionary 无法安全转 CFDictionaryRef）。 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.cfDictionaryOf(vararg items: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val size = items.size
    val keys = allocArrayOf(*items.map { it.first }.toTypedArray())
    val values = allocArrayOf(*items.map { it.second }.toTypedArray())
    return CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        size.convert(),
        null,
        null,
    )
}

/** 桥接 Kotlin 对象为 CFTypeRef 供 Security API 使用，操作结束后统一释放。 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withCfRetain(
    value1: Any?,
    value2: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?) -> T,
): T =
    memScoped {
        val cfValue1 = CFBridgingRetain(value1)
        val cfValue2 = CFBridgingRetain(value2)
        try {
            block(cfValue1, cfValue2)
        } finally {
            CFBridgingRelease(cfValue1)
            CFBridgingRelease(cfValue2)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withCfRetain(
    value1: Any?,
    value2: Any?,
    value3: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?, CFTypeRef?) -> T,
): T =
    memScoped {
        val cfValue1 = CFBridgingRetain(value1)
        val cfValue2 = CFBridgingRetain(value2)
        val cfValue3 = CFBridgingRetain(value3)
        try {
            block(cfValue1, cfValue2, cfValue3)
        } finally {
            CFBridgingRelease(cfValue1)
            CFBridgingRelease(cfValue2)
            CFBridgingRelease(cfValue3)
        }
    }
