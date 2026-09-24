package larder.recipeimport

import java.net.InetAddress
import java.net.URI

class UnsafeImportUrlException(message: String) : Exception(message)

// SSRF guard, required explicitly by SECURITY.md: recipe import fetches an arbitrary
// user-supplied URL server-side, so this rejects non-HTTP(S) schemes and refuses to fetch a
// URL whose host resolves to a loopback, link-local (this also covers the
// 169.254.169.254 cloud-metadata address), or private (RFC 1918 / IPv6 unique-local) address.
// Checked before every request AND before following every redirect (see RecipeUrlFetcher) --
// re-validating only the original URL would leave a redirect-to-internal-address hole open.
fun validateImportUrl(url: String): URI {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        throw UnsafeImportUrlException("Malformed URL")
    }

    if (uri.scheme?.lowercase() !in setOf("http", "https")) {
        throw UnsafeImportUrlException("Only http/https URLs can be imported")
    }

    val host = uri.host ?: throw UnsafeImportUrlException("URL has no host")

    // IP literals (e.g. "127.0.0.1") resolve locally, no network call -- only a real hostname
    // triggers an actual DNS lookup here.
    val addresses = try {
        InetAddress.getAllByName(host)
    } catch (e: Exception) {
        throw UnsafeImportUrlException("Could not resolve host")
    }

    for (address in addresses) {
        if (address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isAnyLocalAddress
        ) {
            throw UnsafeImportUrlException("URL resolves to a private or internal address")
        }
    }

    return uri
}
