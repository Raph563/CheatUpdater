from __future__ import annotations

import smtplib
from email.message import EmailMessage
from pathlib import Path

from ..config import settings


class EmailService:
    def __init__(self) -> None:
        self._log_dir = settings.data_dir / "mail_debug"
        self._log_dir.mkdir(parents=True, exist_ok=True)

    def send(self, to_email: str, subject: str, body: str) -> None:
        if settings.smtp_host:
            self._send_smtp(to_email, subject, body)
            return
        self._write_debug_mail(to_email, subject, body)

    def _send_smtp(self, to_email: str, subject: str, body: str) -> None:
        msg = EmailMessage()
        msg["Subject"] = subject
        msg["From"] = settings.smtp_from
        msg["To"] = to_email
        msg.set_content(body)

        if settings.smtp_use_tls:
            with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=30) as client:
                client.starttls()
                if settings.smtp_username:
                    client.login(settings.smtp_username, settings.smtp_password)
                client.send_message(msg)
        else:
            with smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port, timeout=30) as client:
                if settings.smtp_username:
                    client.login(settings.smtp_username, settings.smtp_password)
                client.send_message(msg)

    def _write_debug_mail(self, to_email: str, subject: str, body: str) -> None:
        safe = to_email.replace("@", "_at_").replace(".", "_")
        path = self._log_dir / f"{safe}.txt"
        path.write_text(f"TO: {to_email}\nSUBJECT: {subject}\n\n{body}\n", encoding="utf-8")

