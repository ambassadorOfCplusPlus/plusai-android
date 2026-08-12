"""Извлечение таблиц из PDF для агента Plus AI (pdfplumber / pdfminer.six).

Высокоуровневая функция extract_tables: агент передаёт путь к PDF, путь вывода и (опц.) страницы —
получаем CSV или JSON с таблицами. Возвращает JSON-статус {"tables": N, "rows": M, "out": "имя"}
или '[ошибка] ...' (совместимо с разбором в PdfTablesTool.kt).
"""
import csv
import json
import os


def _parse_pages(pages, total):
    """Разобрать "all" | "1,2,3" | "1-4" в список 0-based индексов страниц (в пределах документа)."""
    if not pages or str(pages).strip().lower() == "all":
        return list(range(total))
    idx = set()
    for part in str(pages).replace(";", ",").split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, _, b = part.partition("-")
            try:
                for p in range(int(a), int(b) + 1):
                    idx.add(p - 1)
            except ValueError:
                pass
        else:
            try:
                idx.add(int(part) - 1)
            except ValueError:
                pass
    return sorted(p for p in idx if 0 <= p < total)


def extract_tables(pdf_path, out_path, pages="all"):
    try:
        import pdfplumber
    except Exception as e:  # noqa: BLE001
        return f"[ошибка] pdfplumber недоступен: {e}"

    try:
        as_json = str(out_path).lower().endswith(".json")
        tables = []  # список {"page": int, "rows": [[str, ...], ...]}

        with pdfplumber.open(pdf_path) as pdf:
            for pi in _parse_pages(pages, len(pdf.pages)):
                page = pdf.pages[pi]
                for t in (page.extract_tables() or []):
                    # None-ячейки → "" (иначе csv/json засорятся null-ами)
                    rows = [["" if c is None else str(c) for c in row] for row in t]
                    if rows:
                        tables.append({"page": pi + 1, "rows": rows})

        total_rows = sum(len(t["rows"]) for t in tables)
        os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)

        if as_json:
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump(tables, f, ensure_ascii=False, indent=2)
        else:
            with open(out_path, "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                for i, t in enumerate(tables):
                    if i > 0:
                        w.writerow([])  # пустая строка-разделитель между таблицами
                    w.writerow([f"# таблица {i + 1} (стр. {t['page']})"])
                    for row in t["rows"]:
                        w.writerow(row)

        return json.dumps(
            {"tables": len(tables), "rows": total_rows, "out": os.path.basename(out_path)},
            ensure_ascii=False,
        )
    except Exception as e:  # noqa: BLE001
        return f"[ошибка] {type(e).__name__}: {e}"
