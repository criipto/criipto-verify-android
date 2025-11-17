package eu.idura.verify.eid

/**
 * A utility class that can represent any eID. Generally, you should prefer the specific eID classes (such as MitID, SeBankID etc.), since they have helper methods for the scopes and login hints supported by that specific provider.
 *
 * However, in the case where Idura adds a support for a new eID provider, but the SDK has not been updated, you can fall back to this class.
 */
class Other(
  acrValue: String,
) : EID<Other>(acrValue = acrValue) {
  override fun getThis(): Other = this
}
