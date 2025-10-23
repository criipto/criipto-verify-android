package com.criipto.verify.eid

class NoBankID private constructor() : EID<NoBankID>(acrValue = "urn:grn:authn:no:bankid") {
  companion object {
    fun substantial() = NoBankID().withModifier("substantial")

    fun high() = NoBankID().withModifier("high")

    // TODO: better name?
    fun default() = NoBankID()
  }

  override fun getThis(): NoBankID = this

  fun withSsn() = withScope("ssn")

  fun withAddress() = withScope("address")

  fun withEmail() = withScope("email")

  fun withPhone() = withScope("phone")
}
