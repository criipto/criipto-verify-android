package eu.idura.verify.samples

import eu.idura.verify.Action
import eu.idura.verify.CriiptoVerify
import eu.idura.verify.eid.DanishMitID
import eu.idura.verify.eid.Other
import eu.idura.verify.eid.SwedishBankID

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
