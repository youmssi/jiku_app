package com.jiku.support

import com.jayway.jsonpath.JsonPath
import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import java.util.Base64

/**
 * A phone holding passkeys, for tests (JIKU-318): it answers the API's WebAuthn
 * options as a browser would, and returns the credential in the JSON form the
 * browser sends.
 */
class PasskeyDevice(
    origin: String = "http://localhost:3000",
) {
    private val platform = ClientPlatform(Origin(origin), WebAuthnAuthenticatorAdaptor(PackedAuthenticator()))
    private var credentialId: ByteArray? = null

    /** The credential JSON for the creation options of `POST /auth/me/passkeys/options`. */
    fun create(ceremony: String): String {
        val options = JsonPath.parse(ceremony)
        val algorithms = options.read<List<Int>>("$.options.pubKeyCredParams[*].alg")
        val created =
            platform.create(
                PublicKeyCredentialCreationOptions(
                    PublicKeyCredentialRpEntity(options.read("$.options.rp.id"), options.read("$.options.rp.name")),
                    PublicKeyCredentialUserEntity(
                        decode(options.read("$.options.user.id")),
                        options.read("$.options.user.name"),
                        options.read("$.options.user.displayName"),
                    ),
                    DefaultChallenge(decode(options.read("$.options.challenge"))),
                    algorithms.map {
                        PublicKeyCredentialParameters(
                            PublicKeyCredentialType.PUBLIC_KEY,
                            COSEAlgorithmIdentifier.create(it.toLong()),
                        )
                    },
                    null,
                    emptyList(),
                    AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
                    AttestationConveyancePreference.NONE,
                    null,
                ),
            )
        credentialId = created.rawId
        val response = requireNotNull(created.response)
        return """{"id":"${encode(
            created.rawId,
        )}","rawId":"${encode(created.rawId)}","type":"public-key","response":{"clientDataJSON":"${encode(
            response.clientDataJSON,
        )}","attestationObject":"${encode(response.attestationObject)}","transports":["internal"]},
                                     "clientExtensionResults":{},"authenticatorAttachment":"platform"}"""
    }

    /** The assertion JSON for the request options of `POST /auth/passkey/options`. */
    fun sign(ceremony: String): String {
        val options = JsonPath.parse(ceremony)
        val id = requireNotNull(credentialId) { "No passkey on this device yet" }
        val assertion =
            platform.get(
                PublicKeyCredentialRequestOptions(
                    DefaultChallenge(decode(options.read("$.options.challenge"))),
                    null,
                    options.read("$.options.rpId"),
                    listOf(PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, id, null)),
                    UserVerificationRequirement.REQUIRED,
                    null,
                ),
            )
        val response = requireNotNull(assertion.response)
        val userHandle = response.userHandle?.let { ""","userHandle":"${encode(it)}"""" }.orEmpty()
        return """{"id":"${encode(
            assertion.rawId,
        )}","rawId":"${encode(assertion.rawId)}","type":"public-key","response":{"clientDataJSON":"${encode(
            response.clientDataJSON,
        )}","authenticatorData":"${encode(response.authenticatorData)}","signature":"${encode(response.signature)}"$userHandle},
                                     "clientExtensionResults":{},"authenticatorAttachment":"platform"}"""
    }

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
