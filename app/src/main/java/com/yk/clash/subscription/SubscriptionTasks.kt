package com.yk.clash.subscription

import com.yk.clash.ProfileStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Download tasks outlive a screen rotation and never retain an Activity. */
internal object SubscriptionTasks {
    class Task {
        @Volatile var result: Result<ProfileStore.Profile>? = null
        @Volatile var discard = false
    }
    private val tasks = ConcurrentHashMap<String, Task>()
    private val worker = Executors.newFixedThreadPool(2)
    fun get(id: String) = tasks[id]
    @Synchronized fun start(id: String, action: () -> ProfileStore.Profile): Task {
        tasks[id]?.takeIf { it.result == null }?.let { return it }
        val task = Task(); tasks[id] = task
        worker.execute {
            task.result = runCatching(action)
            if (task.discard) tasks.remove(id, task)
        }
        return task
    }
    fun forget(id: String) {
        tasks[id]?.let { task ->
            task.discard = true
            if (task.result != null) tasks.remove(id, task)
        }
    }
}
