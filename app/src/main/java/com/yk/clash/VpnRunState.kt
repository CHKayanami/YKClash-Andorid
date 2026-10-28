package com.yk.clash

class VpnRunState {
    private var version = 0L
    private var wanted = false
    @Synchronized fun start(): Long? {
        if (wanted) return null
        wanted = true
        return ++version
    }
    @Synchronized fun stop(): Long { wanted = false; return ++version }
    @Synchronized fun current(ticket: Long) = wanted && ticket == version
    @Synchronized fun sameVersion(ticket: Long) = ticket == version
}
