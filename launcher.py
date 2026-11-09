"""Starts bathy_picker.py when it is packaged as BathyPicker.exe.

The script itself is unchanged. This only adds what a single exe needs:
- The GDAL setup asks "this Python" to print its environment (python -c ...).
  Inside an exe there is no separate Python, so the exe answers that call itself.
- When double-clicked, keep the window open after an error so it can be read.
"""
import sys

if len(sys.argv) >= 3 and sys.argv[1] == "-c":
    exec(compile(sys.argv[2], "<-c>", "exec"), {"__name__": "__main__"})
    sys.exit(0)

import bathy_picker

if __name__ == "__main__":
    double_clicked = len(sys.argv) == 1
    try:
        code = bathy_picker.main()
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 1
    except Exception as exc:
        print(f"\nBathyPicker stopped: {exc}")
        code = 1
    if code and double_clicked:
        try:
            input("\nPress Enter to close.")
        except EOFError:
            pass
    sys.exit(code or 0)
