package com.weshah.router.openwrt

/** Source of router passwords. Android: Keystore-backed [RouterCredentialManager]. */
interface CredentialProvider {
    fun getCredential(keyAlias: String): String?
}
