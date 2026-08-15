package dev.mssh.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
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
 */
@OptIn(ExperimentalForeignApi::class)
actual object SecretStore {

    private fun query(service: String, account: String): NSMutableDictionary {
        val q = NSMutableDictionary()
        q.setObject(kSecClassGenericPassword as NSString, forKey = kSecClass as NSString)
        q.setObject(service as NSString, forKey = kSecAttrService as NSString)
        q.setObject(account as NSString, forKey = kSecAttrAccount as NSString)
        return q
    }

    actual fun get(service: String, account: String): String? = memScoped {
        val q = query(service, account)
        q.setObject(kCFBooleanTrue, forKey = kSecReturnData as NSString)
        q.setObject(kSecMatchLimitOne as NSString, forKey = kSecMatchLimit as NSString)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q as CFDictionaryRef?, result.ptr)
        if (status != errSecSuccess) return null
        val data = result.value as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    actual fun set(service: String, account: String, value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        SecItemDelete(query(service, account) as CFDictionaryRef?)
        val attrs = query(service, account)
        attrs.setObject(data, forKey = kSecValueData as NSString)
        SecItemAdd(attrs as CFDictionaryRef?, null)
    }

    actual fun delete(service: String, account: String) {
        SecItemDelete(query(service, account) as CFDictionaryRef?)
    }
}
