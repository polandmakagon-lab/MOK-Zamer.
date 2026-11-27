"""Mail an original Android build artifact from the authenticated Mail.ru account to itself."""
import hashlib
import io
import json
import os
import re
import smtplib
import ssl
import sys
import urllib.parse
import urllib.request
import zipfile
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

MAX = 24 * 1024 * 1024


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise ValueError("Expected an HTTPS download")
        dest = super().redirect_request(req, fp, code, msg, headers, newurl)
        if dest and urllib.parse.urlparse(newurl).netloc != urllib.parse.urlparse(req.full_url).netloc:
            dest.remove_header("Authorization")
        return dest


def github(path, *, binary=False):
    repo = os.environ["GITHUB_REPOSITORY"]
    url = f"https://api.github.com/repos/{repo}/{path}"
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "User-Agent": "MOK-Android-build-delivery",
    })
    with urllib.request.build_opener(PublicRedirect).open(req, timeout=40) as response:
        body = response.read(MAX + 1)
    if len(body) > MAX:
        raise ValueError("Download exceeds mail attachment limit")
    return body if binary else json.loads(body)


def extract(archive, digest):
    if digest != "sha256:" + hashlib.sha256(archive).hexdigest():
        raise ValueError("Artifact checksum differs from GitHub Actions")
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        entries = [i for i in z.infolist() if not i.is_dir()]
        if len(entries) != 1 or entries[0].filename != "app-debug.apk" or not 0 < entries[0].file_size <= MAX:
            raise ValueError("Unexpected Android build artifact")
        apk = z.read(entries[0])
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        if not {"AndroidManifest.xml", "classes.dex"}.issubset(z.namelist()):
            raise ValueError("Expected an Android application")
    return apk


def send(run_id, preview):
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        raise ValueError("Invalid run ID")
    build = github(f"actions/runs/{run_id}")
    if (build["path"].split("@")[0] != ".github/workflows/android.yml"
            or build["conclusion"] != "success" or build["head_repository"]["full_name"] != os.environ["GITHUB_REPOSITORY"]):
        raise ValueError("Expected a successful Android build from this repository")
    artifacts = github(f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    matches = [a for a in artifacts if a["name"] == "MOK-Zamer-APK" and not a["expired"]]
    if len(matches) != 1:
        raise ValueError("Expected one active APK artifact")
    asset = matches[0]
    apk = extract(github(f'actions/artifacts/{int(asset["id"])}/zip', binary=True), asset.get("digest"))
    number = int(build["run_number"])
    filename = f"MOK-Zamer-{number}.apk"
    if preview:
        print(f"Verified {filename}, {len(apk)} bytes")
        return
    account = os.environ["MOK_MAILRU_LOGIN"].strip()
    password = os.environ["MOK_MAILRU_PASSWORD"].strip()
    if not re.fullmatch(r"[A-Za-z0-9._+-]+@mail\.ru", account, re.IGNORECASE) or not password:
        raise ValueError("Set the Mail.ru login and app password")
    message = EmailMessage()
    message["From"] = message["To"] = account
    message["Subject"] = f"МОК Замер — APK №{number}"
    message["Date"] = formatdate(localtime=False)
    message["Message-ID"] = make_msgid(domain="mail.ru")
    message.set_content(f"Виктор, во вложении {filename} для рабочего HONOR.")
    message.add_attachment(apk, maintype="application", subtype="vnd.android.package-archive", filename=filename)
    with smtplib.SMTP_SSL("smtp.mail.ru", 465, context=ssl.create_default_context(), timeout=45) as smtp:
        smtp.login(account, password)
        smtp.send_message(message)
    print(f"Mail.ru accepted the message for {filename}; inbox delivery must be confirmed separately.")


if __name__ == "__main__":
    send(sys.argv[1], "--preview" in sys.argv[2:])
