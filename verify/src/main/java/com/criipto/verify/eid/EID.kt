package com.criipto.verify.eid

abstract class EID<T : EID<T>>(
  acrValue: String,
  internal val scopes: MutableSet<String> = mutableSetOf(),
  internal val loginHints: MutableSet<String> = mutableSetOf(),
) {
  private var acrValues = mutableListOf(acrValue)

  protected fun withModifier(modifier: String): T {
    acrValues.add(modifier.lowercase())
    return getThis()
  }

  // Allows us to return a concrete subtype for chaining, while defining the actual methods on the abstract class. https://stackoverflow.com/questions/5818504/can-i-have-an-abstract-builder-class-in-java-with-method-chaining-without-doing
  protected abstract fun getThis(): T

  fun withScope(scope: String): T {
    this.scopes.add(scope)
    return getThis()
  }

  fun withLoginHint(loginHint: String): T {
    this.loginHints.add(loginHint)
    return getThis()
  }

  internal val acrValue: String
    get() = acrValues.joinToString(":")
}
