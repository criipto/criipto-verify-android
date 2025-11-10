package com.criipto.verify.samples

import com.criipto.verify.Action
import com.criipto.verify.CriiptoVerify
import com.criipto.verify.eid.DanishMitID
import com.criipto.verify.eid.Other
import com.criipto.verify.eid.SwedishBankID

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

  // Prompt the user to approve with MitID
  criiptoVerify.login(DanishMitID.substantial().withAction(Action.Approve))

  // Prompt the user to sign a message with Swedish BankId
  criiptoVerify.login(SwedishBankID.sameDevice().sign("All your base are belong to us"))

  // Login with a type of eID not supported by the SDK
  criiptoVerify.login(Other("urn:grn:authn:foo:bar").withScope("address"))
}
