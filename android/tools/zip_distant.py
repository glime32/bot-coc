#!/usr/bin/env python3
# Lit un zip distant par morceaux (requêtes Range) : on ne télécharge que ce qu'on extrait.
import io, sys, urllib.request, zipfile

class Distant(io.RawIOBase):
    def __init__(self, url):
        self.url, self.pos, self.lus = url, 0, 0
        r = urllib.request.urlopen(urllib.request.Request(url, method="HEAD"))
        self.taille = int(r.headers["Content-Length"])
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos
    def seek(self, off, whence=0):
        self.pos = {0: off, 1: self.pos + off, 2: self.taille + off}[whence]
        return self.pos
    def readinto(self, b):
        n = len(b)
        if n == 0 or self.pos >= self.taille: return 0
        fin = min(self.taille, self.pos + n) - 1
        req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{fin}"})
        data = urllib.request.urlopen(req).read()
        self.pos += len(data); self.lus += len(data)
        b[:len(data)] = data
        return len(data)

# usage : zip_distant.py URL DOSSIER [fichier du zip ...] : extrait seulement ces fichiers
f = Distant(sys.argv[1])
z = zipfile.ZipFile(io.BufferedReader(f, 1 << 16))
for nom in sys.argv[3:]:
    z.extract(nom, sys.argv[2])
    print(nom)
print(f"téléchargé : {f.lus/1e6:.1f} Mo", file=sys.stderr)
