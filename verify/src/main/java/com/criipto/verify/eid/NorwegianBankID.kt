package com.criipto.verify.eid

class NorwegianBankID private constructor() :
  EID<NorwegianBankID>(acrValue = "urn:grn:authn:no:bankid") {
    companion object {
      fun substantial() = NorwegianBankID().withModifier("substantial")

      fun high() = NorwegianBankID().withModifier("high")
    }

    override fun getThis(): NorwegianBankID = this

    fun withSsn() = withScope("ssn")
  }
