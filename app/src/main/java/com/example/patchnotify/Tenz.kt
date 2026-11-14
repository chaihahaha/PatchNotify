package com.example.patchnotify

/** tenz 解压器（cpp/tenz 是官方 tenz.c，Apache-2.0） */
object Tenz {
    init { System.loadLibrary("tenz") }

    /**
     * 把 inPath(.tenz) 解回 outPath。
     * @return 0 成功；非 0 失败
     */
    external fun unpack(inPath: String, outPath: String, threads: Int): Int
}
