"""DEPRECATED — nahrazeno events.py (SSE).

Tento modul zůstal jako tombstone, aby starý import `from . import fcm` v app.py
nepadl při deployi v období mezi commity. Po vyčištění app.py může být smazán.

Důvod pivotu: Firebase Cloud Messaging vyžadovalo service account JSON key,
což Google Workspace organizační policy (`iam.disableServiceAccountKeyCreation`)
blokovala v Benově účtu. SSE je vlastní řešení bez third-party závislosti.
"""


def push_alerts_for_new_subject(store, subject):  # noqa: ARG001
    """Stub — žádné FCM, vše přesunuto na SSE broadcast v events.py."""
    return {"deprecated": True, "use": "events.broadcast_alerts_for_new_subject"}
