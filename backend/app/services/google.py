"""Checking "Sign in with Google" ID tokens sent by the app."""

from dataclasses import dataclass

import jwt

GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
GOOGLE_ISSUERS = ("accounts.google.com", "https://accounts.google.com")


class InvalidGoogleToken(Exception):
    pass


@dataclass(frozen=True)
class GoogleIdentity:
    subject: str  # Google's stable account id
    email: str
    name: str | None


class GoogleTokenVerifier:
    """Verifies the token's signature against Google's public keys, and that it was issued to one
    of our OAuth client IDs, by Google, for a verified email address."""

    def __init__(self, client_ids: list[str], jwks_client: jwt.PyJWKClient | None = None):
        self.client_ids = client_ids
        self._jwks = jwks_client or jwt.PyJWKClient(GOOGLE_CERTS_URL, cache_keys=True, lifespan=3600)

    def verify(self, token: str) -> GoogleIdentity:
        try:
            key = self._jwks.get_signing_key_from_jwt(token).key
            claims = jwt.decode(token, key, algorithms=["RS256"], audience=self.client_ids,
                                options={"require": ["exp", "iat", "iss", "aud", "sub"]})
        except (jwt.PyJWTError, jwt.PyJWKClientError) as exc:
            raise InvalidGoogleToken(f"Google sign-in failed: {exc}") from exc
        if claims.get("iss") not in GOOGLE_ISSUERS:
            raise InvalidGoogleToken("Google sign-in failed: the token wasn't issued by Google.")
        if not claims.get("email") or claims.get("email_verified") not in (True, "true"):
            raise InvalidGoogleToken("Google sign-in failed: this Google account has no verified email.")
        return GoogleIdentity(subject=str(claims["sub"]), email=claims["email"].lower(), name=claims.get("name"))
