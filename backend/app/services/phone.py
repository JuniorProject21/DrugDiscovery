"""Phone numbers: normalising them, and sending the sign-up code as a text message.

No SMS provider is configured for the project, so by default the code goes to the server log,
exactly as password-reset emails do during development (see app.services.email). Sending real
messages needs an account with a provider such as Twilio; `SmsSender` is the one place that would
need an implementation for it, and nothing above this module changes.
"""

import logging
import re
from typing import Protocol

log = logging.getLogger(__name__)

# E.164: a leading "+", a country code that cannot start with 0, then up to 14 more digits.
_E164 = re.compile(r"^\+[1-9]\d{6,14}$")
# Characters people type that carry no meaning: spaces, dashes, dots and bracketed trunk codes.
_PUNCTUATION = re.compile(r"[\s\-.() ‐-―]")


class InvalidPhoneError(ValueError):
    pass


def normalise_phone(value: str, *, default_country_code: str = "") -> str:
    """Return `value` as E.164 ("+971501234567"), or raise InvalidPhoneError.

    Accepts the ways people actually write numbers: "+971 50 123 4567", "00971501234567" and,
    when `default_country_code` is set, a national number such as "050 123 4567".
    """
    raw = _PUNCTUATION.sub("", value or "")
    if not raw:
        raise InvalidPhoneError("Please enter a phone number.")
    if raw.startswith("00"):  # the other way of writing "+"
        raw = "+" + raw[2:]
    if not raw.startswith("+") and default_country_code:
        # A national number: drop the trunk "0" before adding the country code.
        raw = default_country_code + raw.lstrip("0")
    if not raw.startswith("+"):
        raise InvalidPhoneError(
            "Please include the country code, for example +971 50 123 4567."
        )
    if not raw[1:].isdigit():
        raise InvalidPhoneError("A phone number can only contain digits after the country code.")
    if not _E164.match(raw):
        raise InvalidPhoneError("That doesn't look like a valid phone number.")
    return raw


def mask_phone(phone: str) -> str:
    """"+971501234567" -> "+971 ***** 4567": safe to show in a message that confirms where a code went."""
    if len(phone) < 5:
        return phone
    return f"{phone[:4]} {'*' * (len(phone) - 8)} {phone[-4:]}"


class SmsSender(Protocol):
    def send(self, to: str, body: str) -> None: ...


class ConsoleSmsSender:
    """Development only: prints the message to the server log instead of sending it."""

    def send(self, to: str, body: str) -> None:
        log.warning("SMS is not configured (development mode), so here is the message instead.\n"
                    "----- To: %s -----\n%s\n-----", to, body.strip())
