"""PatchNotify 执行用户脚本的入口。

Kotlin 侧通过 PythonRunner 调用 run()，脚本里可以直接使用这些变量：

    PATCH_ID / PATCH_TITLE / PATCH_CONDITION / PATCH_QUESTION
    PATCH_ANSWER     条件成立时模型给出的回答（没有追问时为空串）
    APP_FILES_DIR    App 私有目录，适合写持久文件
    SCRIPT_PATH      当前脚本的绝对路径

脚本可选地设置 RESULT，它会被写进 App 日志。stdout/stderr 也会被收集进日志。
"""

import os
import runpy
import sys
import traceback
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO

_MAX_OUT = 4000


def run(script_path, patch_id, title, condition, question, answer, files_dir):
    script_path = os.path.abspath(script_path)
    script_dir = os.path.dirname(script_path)
    old_cwd = os.getcwd()
    old_path = list(sys.path)
    stdout = StringIO()
    stderr = StringIO()

    env = {
        "PATCH_ID": patch_id,
        "PATCH_TITLE": title,
        "PATCH_CONDITION": condition,
        "PATCH_QUESTION": question,
        "PATCH_ANSWER": answer,
        "APP_FILES_DIR": files_dir,
        "SCRIPT_PATH": script_path,
    }

    try:
        # 让用户脚本里的相对路径和 import 同目录模块都按脚本所在目录解析
        os.chdir(script_dir)
        if script_dir not in sys.path:
            sys.path.insert(0, script_dir)

        with redirect_stdout(stdout), redirect_stderr(stderr):
            namespace = runpy.run_path(script_path, run_name="__main__", init_globals=env)

        parts = ["ok"]
        out = stdout.getvalue()
        err = stderr.getvalue()
        if out:
            parts.append("stdout=" + out[-_MAX_OUT:])
        if err:
            parts.append("stderr=" + err[-_MAX_OUT:])
        result = namespace.get("RESULT")
        if result is not None:
            parts.append("result=" + repr(result)[:1000])
        return " ".join(parts)
    except BaseException:
        detail = (stderr.getvalue() + traceback.format_exc(limit=12))[-_MAX_OUT:]
        return "ERR " + detail
    finally:
        os.chdir(old_cwd)
        sys.path[:] = old_path
