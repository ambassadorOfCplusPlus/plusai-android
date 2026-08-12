"""Раннер пользовательского Python-кода для агента Plus AI (S7).

Выполняет код с захватом stdout в рабочей директории workspace. matplotlib — без GUI
(Agg): графики автоматически сохраняются в PNG и возвращаются вложениями для чата.
Результат — JSON: {"stdout": "...", "images": ["/abs/path.png", ...]}.
"""
import io
import os
import re
import glob
import json
import ctypes
import threading
import contextlib


def _raise_async(tid, exctype):
    """Внедрить исключение в поток по его id (штатный C-API CPython)."""
    ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_long(tid), ctypes.py_object(exctype))


def _next_index(prefix: str) -> int:
    mx = 0
    for f in glob.glob(prefix + "*.png"):
        m = re.search(r"(\d+)\.png$", os.path.basename(f))
        if m:
            mx = max(mx, int(m.group(1)))
    return mx + 1


def run(code: str, workspace: str, timeout_sec: int = 25) -> str:
    os.environ.setdefault("MPLBACKEND", "Agg")
    try:
        if workspace and os.path.isdir(workspace):
            os.chdir(workspace)
    except Exception:
        pass

    before = set(glob.glob("*.png"))
    buf = io.StringIO()
    ns = {"__name__": "__main__"}

    # Выполняем код в ОТДЕЛЬНОМ потоке: при зависании (напр. while True) внедряем в него
    # исключение и он умирает, освобождая GIL — иначе следующий run_python был бы «кирпичом».
    def _target():
        try:
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                exec(code, ns)
        except Exception as e:  # noqa: BLE001 — показываем пользователю любую ошибку кода
            buf.write(f"\n[ошибка] {type(e).__name__}: {e}")

    worker = threading.Thread(target=_target, daemon=True)
    worker.start()
    worker.join(timeout_sec)
    if worker.is_alive():
        _raise_async(worker.ident, SystemExit)  # прервать зависший код
        worker.join(3)
        if worker.is_alive():  # не поддался (редкий C-цикл) — вторая попытка
            _raise_async(worker.ident, KeyboardInterrupt)
            worker.join(2)
        buf.write("\n[прервано] код превысил лимит времени (возможно, бесконечный цикл)")

    images = []
    # Открытые фигуры matplotlib сохраняем сами (пользователю не нужно savefig).
    try:
        import matplotlib.pyplot as plt
        idx = _next_index("plusai_chart_")
        for num in plt.get_fignums():
            fname = f"plusai_chart_{idx}.png"
            idx += 1
            plt.figure(num).savefig(fname, dpi=110, bbox_inches="tight")
            images.append(os.path.abspath(fname))
        plt.close("all")
    except Exception:
        pass
    # Плюс PNG, которые код сохранил сам.
    for f in sorted(set(glob.glob("*.png")) - before):
        ap = os.path.abspath(f)
        if ap not in images:
            images.append(ap)

    out = buf.getvalue()
    return json.dumps({"stdout": out if out.strip() else "(код выполнен, вывода нет)", "images": images}, ensure_ascii=False)
