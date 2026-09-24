import kotlin.test.assertFailsWith
import larder.recipeimport.UnsafeImportUrlException
import larder.recipeimport.validateImportUrl

// IP-literal URLs throughout, not hostnames -- InetAddress resolves an IP literal locally with
// no real DNS lookup, keeping these hermetic (no network needed), same discipline as the rest
// of backend/test/. A real-hostname case is covered by live verification instead, not here.

fun testRejectsLoopbackAddress() {
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://127.0.0.1/recipe") }
}

fun testRejectsIpv6Loopback() {
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://[::1]/recipe") }
}

fun testRejectsLinkLocalIncludingCloudMetadataAddress() {
    // 169.254.169.254 is the classic cloud-provider instance-metadata SSRF target.
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://169.254.169.254/latest/meta-data/") }
}

fun testRejectsRfc1918PrivateRanges() {
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://10.0.0.5/recipe") }
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://172.16.0.5/recipe") }
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("http://192.168.1.1/recipe") }
}

fun testRejectsNonHttpScheme() {
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("file:///etc/passwd") }
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("ftp://example.com/recipe") }
}

fun testRejectsMalformedUrl() {
    assertFailsWith<UnsafeImportUrlException> { validateImportUrl("not a url at all") }
}

fun testAllowsPublicIpLiteral() {
    // 8.8.8.8 (a real public address) must NOT be rejected -- confirms the guard isn't
    // over-broad and blocking legitimate public addresses along with private ones.
    validateImportUrl("http://8.8.8.8/recipe")
}
