package eu.idura.verify

import eu.idura.verify.eid.DanishMitID
import eu.idura.verify.eid.Other
import org.junit.Assert.assertEquals
import org.junit.Test

class EidUnitTest {
  @Test
  fun mitid_substantial_with_scopes() {
    val mitid = DanishMitID.substantial().withSsn()

    assertEquals(mitid.acrValue, "urn:grn:authn:dk:mitid:substantial")
    assertEquals(mitid.scopes.toList(), listOf("ssn"))
  }

  @Test
  fun mitid_high_prefill() {
    val ssn = "1234564444"
    val mitid = DanishMitID.high().prefillSsn(ssn)

    assertEquals(mitid.acrValue, "urn:grn:authn:dk:mitid:high")
    assertEquals(mitid.scopes.toList(), listOf("ssn"))
    assertEquals(mitid.loginHints.toList(), listOf("sub:$ssn"))
  }

  @Test
  fun other_falback() {
    val acrValue = "urn:grn:authn:foo:bar"
    val other = Other(acrValue).withScope("something")

    assertEquals(other.acrValue, acrValue)
    assertEquals(other.scopes.toList(), listOf("something"))
  }
}
