package com.spoongecko.app

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

object BackgroundExecutor {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    
    fun execute(block: () -> Unit) {
        executor.execute(block)
    }
    
    fun shutdown() {
        executor.shutdown()
    }
}
