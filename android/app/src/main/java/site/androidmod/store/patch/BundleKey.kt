package site.androidmod.store.patch

import java.security.PublicKey
import site.androidmod.patch.publicKeyFromPem

/**
 * The key patch bundles are signed with, pinned in the app: a bundle the server
 * hands out is trusted only if this key verifies it. Public half of
 * ~/.config/androidmod/bundle-signing.pem; the private key never enters a repo.
 */
object BundleKey {
    const val PEM: String = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZKpTE/mv2KR9io/iMdJKjhtyHeFt
OvL92wlnCdsAMTuzhm3GTyy7ds6YrWAIz/P8aPBbtgu5Nl8GFIEN3q3Jtg==
-----END PUBLIC KEY-----
"""

    val publicKey: PublicKey by lazy { publicKeyFromPem(PEM) }
}
