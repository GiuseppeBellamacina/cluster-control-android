package app.clustercontrol

import app.clustercontrol.core.JobEntry

/**
 * The queue as the user sees it: edits apply at once (optimistic) and are written back as ONE rewrite.
 * Main thread only. [items] differs from the last known remote queue exactly while there is something to write.
 */
class QueueEditor(initial: List<JobEntry> = emptyList()) {
    var items: List<JobEntry> = initial
        private set
    private var base = initial
    private var writing: List<JobEntry>? = null

    /** A fresh remote queue replaces the shown one unless the user has unwritten edits. */
    fun adopt(remote: List<JobEntry>) {
        if (items == base && writing == null) {
            items = remote
            base = remote
        }
    }

    fun edit(f: (List<JobEntry>) -> List<JobEntry>) {
        items = f(items)
    }

    /** The list to write now (and marks it as in flight), or null when there is nothing new or a write is running. */
    fun takeWrite(): List<JobEntry>? = if (writing == null && items != base) items.also { writing = it } else null

    /** The write succeeded; [remote] is the queue the cluster reports afterwards. */
    fun written(remote: List<JobEntry>) {
        base = checkNotNull(writing)
        writing = null
        if (items == base) {
            items = remote
            base = remote
        }
    }

    /** The write failed: show what the cluster really has. */
    fun failed(remote: List<JobEntry>) {
        writing = null
        items = remote
        base = remote
    }
}
