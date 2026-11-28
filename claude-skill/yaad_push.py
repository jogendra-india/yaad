"""Push a learning to the Yaad inbox (ledgerbal.com/api/yaad).

    python yaad_push.py --title "..." --body-file note.html --subject Python \
        --keywords "gil,threads" [--source URL] [--file a.png --kind photo ...]
    python yaad_push.py --ping
    python yaad_push.py --pending

Body is HTML (<p>, <b>, <i>, <u>, <ul>/<ol><li>) or plain text (one <p> per line in the app).
Kinds: photo, handwriting, audio, video, document (guessed from extension if omitted).
"""
import argparse, json, mimetypes, os, sys, uuid, urllib.request, ssl

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
BASE = CFG["server_url"].rstrip("/")
HDR = {"X-Yaad-Key": CFG["key"], "User-Agent": "yaad-cli"}
CTX = ssl.create_default_context()


def req(method, path, data=None, ctype=None):
    h = dict(HDR)
    if ctype:
        h["Content-Type"] = ctype
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, context=CTX, timeout=120) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def guess_kind(path):
    mt = mimetypes.guess_type(path)[0] or ""
    if mt.startswith("image/"):
        return "photo"
    if mt.startswith("audio/"):
        return "audio"
    if mt.startswith("video/"):
        return "video"
    return "document"


def multipart(fields, files):
    b = uuid.uuid4().hex
    out = []
    for k, v in fields:
        out += [f"--{b}\r\n".encode(), f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode(),
                v.encode("utf-8"), b"\r\n"]
    for path in files:
        name = os.path.basename(path)
        mt = mimetypes.guess_type(path)[0] or "application/octet-stream"
        out += [f"--{b}\r\n".encode(),
                f'Content-Disposition: form-data; name="files"; filename="{name}"\r\n'.encode(),
                f"Content-Type: {mt}\r\n\r\n".encode(), open(path, "rb").read(), b"\r\n"]
    out.append(f"--{b}--\r\n".encode())
    return b"".join(out), f"multipart/form-data; boundary={b}"


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--ping", action="store_true")
    ap.add_argument("--pending", action="store_true")
    ap.add_argument("--title", default="")
    ap.add_argument("--body", default="")
    ap.add_argument("--body-file")
    ap.add_argument("--subject", default="")
    ap.add_argument("--keywords", default="", help="comma separated")
    ap.add_argument("--source", default="")
    ap.add_argument("--file", action="append", default=[])
    ap.add_argument("--kind", action="append", default=[])
    a = ap.parse_args()

    if a.ping:
        print(*req("GET", "/ping/")); return
    if a.pending:
        print(*req("GET", "/entries/?limit=50")); return

    body = open(a.body_file, encoding="utf-8").read() if a.body_file else a.body
    if not (a.title or body or a.file):
        sys.exit("Nothing to send: give --title, --body/--body-file or --file.")
    fields = [("title", a.title), ("body", body), ("subject", a.subject), ("source_url", a.source)]
    fields += [("keywords", k.strip()) for k in a.keywords.split(",") if k.strip()]
    kinds = a.kind + [guess_kind(f) for f in a.file[len(a.kind):]]
    fields += [("kinds", k) for k in kinds]
    data, ctype = multipart(fields, a.file)
    status, text = req("POST", "/entries/", data, ctype)
    print(status, text)
    sys.exit(0 if 200 <= status < 300 else 1)


if __name__ == "__main__":
    main()
