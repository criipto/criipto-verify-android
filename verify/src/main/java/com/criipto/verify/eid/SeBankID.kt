package com.criipto.verify.eid

import kotlin.io.encoding.Base64

class SeBankID private constructor() : EID<SeBankID>(acrValue = "urn:grn:authn:se:bankid") {
  companion object {
    fun otherDevice() = SeBankID().withModifier("another-device:qr")

    fun selectorPage() = SeBankID()
  }

  override fun getThis(): SeBankID = this

  fun withSsn(ssn: String) = withLoginHint("sub:$ssn")

  fun withMessage(message: String) =
    withLoginHint("message:${Base64.Default.encode(message.toByteArray())}")
}
