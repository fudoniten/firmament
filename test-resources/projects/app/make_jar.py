"""Build lib/greeting.jar reproducibly: python3 make_jar.py"""
import pathlib
import zipfile

FILES = {
    "greeting/core.clj": '(ns greeting.core)\n\n(defn greet [who] (str "Hello, " who "!"))\n',
    "greeting/banner.txt": "from a jar\n",
}

out = pathlib.Path(__file__).parent / "lib" / "greeting.jar"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name, text in sorted(FILES.items()):
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, text)
