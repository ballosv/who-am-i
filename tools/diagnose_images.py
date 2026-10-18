import ssl, socket, urllib.request
UA = {"User-Agent": "WhoAmI-Android/0.4 (https://github.com/ballosv/who-am-i)"}
path = "/wikipedia/commons/thumb/c/cb/Adriano_Celentano.jpg/960px-Adriano_Celentano.jpg"
for host in ["thumb.wikimedia.org", "upload.wikimedia.org"]:
    try:
        req = urllib.request.Request("https://" + host + path, headers=UA)
        with urllib.request.urlopen(req, timeout=20) as r:
            print(host, r.status, r.url, len(r.read()))
    except Exception as e:
        print(host, "ERR", e)
    ctx = ssl.create_default_context()
    with socket.create_connection((host, 443)) as s, ctx.wrap_socket(s, server_hostname=host) as t:
        c = t.getpeercert()
        print("  issuer:", c["issuer"], "notBefore:", c["notBefore"], "cipher:", t.cipher(), t.version())
