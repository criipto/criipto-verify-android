package com.criipto.verify.samples

import com.criipto.verify.CriiptoVerify
import com.criipto.verify.eid.DanishMitID
import com.criipto.verify.eid.NorwegianBankID
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

private suspend fun loginSample2(criiptoVerify: CriiptoVerify) {
  // Allow the user to choose between Danish MitID and Norwegian BankID. Notice that scopes are _not_ eID specific, so in this case both the "ssn" scope, and the "address" scope are applied to both eIDs. That makes it equivalent to the next example
  criiptoVerify.login(
    listOf(
      DanishMitID.substantial().withAddress(),
      NorwegianBankID.high().withSsn(),
    ),
  )

  // Equivalent to the example above, but it's more explicit that scopes are applied to both eIDs.
  criiptoVerify.login(
    listOf(
      DanishMitID.substantial(),
      NorwegianBankID.high(),
    ),
    scopes = setOf("address", "ssn"),
  )

  // Show all eIDs
  criiptoVerify.login(
    scopes = setOf("address"),
  )
}
