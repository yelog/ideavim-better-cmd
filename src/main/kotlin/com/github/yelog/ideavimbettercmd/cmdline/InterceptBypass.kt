package com.github.yelog.ideavimbettercmd.cmdline

import java.util.concurrent.atomic.AtomicInteger

/**
 * 用于在需要模拟按键或直接传递原生输入时，临时绕过我们自己的 ':' '/' 拦截逻辑。
 */
object InterceptBypass {
    private val depth = AtomicInteger(0)

    fun active(): Boolean = depth.get() > 0

    fun <T> runWithoutIntercept(block: () -> T): T {
        depth.incrementAndGet()
        return try {
            block()
        } finally {
            depth.decrementAndGet()
        }
    }
}
