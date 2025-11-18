package eu.idura.verify

// Re-export the RedirectUriReceiverActivity class, so SDK consumers can override the intent behaviour (such as changing callback URLs)
class RedirectUriReceiverActivity : net.openid.appauth.RedirectUriReceiverActivity()
