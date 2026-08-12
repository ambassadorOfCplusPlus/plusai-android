"""Раннер СКРИПТОВЫХ расширений Plus AI (сторонние инструменты на Python).

Контракт расширения (entry-файл, напр. main.py):

    def run(tool_id: str, args: dict) -> dict | list | str:
        # args = разобранный JSON аргументов инструмента
        return {"result": ...}

Приложение зовёт run_ext(...) для конкретного tool_id; результат сериализуется в JSON и отдаётся
агенту как ToolResult. Исполняется в отдельном потоке с тайм-аутом (как plusai_runner) — зависшее
расширение не вешает агента. Песочница/денилист — на уровне приложения (тот же режим, что run_python).
"""
import json
import os
import ctypes
import importlib.util
import threading


def _raise_async(tid, exctype):
    """Внедрить исключение в поток по его id (штатный C-API CPython) — как в plusai_runner."""
    ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_long(tid), ctypes.py_object(exctype))


def run_ext(ext_dir: str, entry: str, tool_id: str, args_json: str, workspace: str, timeout_sec: int = 25) -> str:
    try:
        args = json.loads(args_json) if args_json else {}
    except Exception:
        args = {}
    if not isinstance(args, dict):
        args = {"value": args}
    if workspace and os.path.isdir(workspace):
        try:
            os.chdir(workspace)
        except Exception:
            pass

    box = {"out": None, "err": None}

    def _target():
        try:
            path = os.path.join(ext_dir, entry)
            spec = importlib.util.spec_from_file_location("plusai_ext_" + tool_id, path)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            fn = getattr(mod, "run", None)
            if fn is None:
                box["err"] = "в расширении нет функции run(tool_id, args)"
                return
            box["out"] = fn(tool_id, args)
        except Exception as e:  # noqa: BLE001 — любую ошибку показываем
            box["err"] = f"{type(e).__name__}: {e}"

    t = threading.Thread(target=_target, daemon=True)
    t.start()
    t.join(timeout_sec)
    if t.is_alive():
        # Зависший daemon-поток не убивается сам (в отличие от plusai_runner.run) — внедряем в него
        # исключение и он умирает, освобождая GIL, иначе следующий run_ext был бы «кирпичом».
        _raise_async(t.ident, SystemExit)  # прервать зависшее расширение
        t.join(3)
        if t.is_alive():  # не поддался (редкий C-цикл) — вторая попытка
            _raise_async(t.ident, KeyboardInterrupt)
            t.join(2)
        return json.dumps({"error": "расширение превысило лимит времени"}, ensure_ascii=False)
    if box["err"] is not None:
        return json.dumps({"error": box["err"]}, ensure_ascii=False)
    out = box["out"]
    if isinstance(out, (dict, list)):
        return json.dumps(out, ensure_ascii=False)
    return json.dumps({"result": out}, ensure_ascii=False)
