package com.criipto.verify.eid

import kotlin.io.encoding.Base64

class MitID private constructor() : EID<MitID>(acrValue = "urn:grn:authn:dk:mitid") {
  companion object {
    fun substantial() = MitID().withModifier("substantial")

    fun high() = MitID().withModifier("high")

    fun low() = MitID().withModifier("low")

    fun business() = MitID().withModifier("business")
  }

  override fun getThis(): MitID = this

  fun withCpr(cpr: String) = withScope("ssn").withLoginHint("sub:$cpr")

  fun withVatID(vatId: String) = withLoginHint("vatid:DK$vatId")

  fun withSsn() = withScope("ssn")

  fun withAddress() = withScope("address")

  fun withMessage(message: String) =
    withLoginHint("message:${Base64.Default.encode(message.toByteArray())}")
}
