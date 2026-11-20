"""Sending email (password reset codes)."""

import logging
import smtplib
import ssl
from email.message import EmailMessage
from typing import Protocol

log = logging.getLogger(__name__)


class EmailSender(Protocol):
    def send(self, to: str, subject: str, body: str) -> None: ...


class SmtpEmailSender:
    def __init__(self, *, host: str, port: int, username: str, password: str, sender: str,
                 security: str = "starttls", timeout: float = 15.0):
        self.host, self.port = host, port
        self.username, self.password = username, password
        self.sender = sender
        self.security = security
        self.timeout = timeout

    def send(self, to: str, subject: str, body: str) -> None:
        message = EmailMessage()
        message["From"] = self.sender
        message["To"] = to
        message["Subject"] = subject
        message.set_content(body)
        context = ssl.create_default_context()
        if self.security == "ssl":
            smtp = smtplib.SMTP_SSL(self.host, self.port, timeout=self.timeout, context=context)
        else:
            smtp = smtplib.SMTP(self.host, self.port, timeout=self.timeout)
        with smtp:
            if self.security == "starttls":
                smtp.starttls(context=context)
            if self.username:
                smtp.login(self.username, self.password)
            smtp.send_message(message)


class ConsoleEmailSender:
    """Development only: prints emails to the server log instead of sending them."""

    def send(self, to: str, subject: str, body: str) -> None:
        log.warning("Email is not configured (development mode), so here is the message instead.\n"
                    "----- To: %s | %s -----\n%s\n-----", to, subject, body.strip())
