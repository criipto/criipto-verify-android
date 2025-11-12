package com.criipto.verify.eid

import com.criipto.verify.Action
import kotlin.io.encoding.Base64

class SwedishBankID private constructor() :
  EID<SwedishBankID>(acrValue = "urn:grn:authn:se:bankid") {
    companion object {
      fun otherDevice() = SwedishBankID().withModifier("another-device:qr")

      fun sameDevice() = SwedishBankID().withModifier("same-device")

      fun selectorPage() = SwedishBankID()
    }

    override fun getThis(): SwedishBankID = this

    fun withSsn(ssn: String) = withLoginHint("sub:$ssn")

    fun withMessage(message: String) =
      withLoginHint("message:${Base64.Default.encode(message.toByteArray())}")

    fun sign(message: String) = withMessage(message).withAction(Action.Sign)
  }
