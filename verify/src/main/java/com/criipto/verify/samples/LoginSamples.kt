package com.criipto.verify.samples

import com.criipto.verify.CriiptoVerify
import com.criipto.verify.eid.DanishMitID
import com.criipto.verify.eid.Other

private suspend fun loginSample1(criiptoVerify: CriiptoVerify) {
  // Login with Danish MitID
  criiptoVerify.login(DanishMitID.substantial())

  // Login with Danish MitID, while prefilling SSN, setting a message, and requesting user address.
  criiptoVerify.login(
    DanishMitID
      .substantial()
      .prefillSsn("123456-7890")
      .withMessage("Hello there!")
      .withAddress(),
  )

  // Login with a type of eID not supported by the SDK
  criiptoVerify.login(Other("urn:grn:authn:foo:bar").withScope("address"))
}
