package app.epistola.suite.cluster.schedules

interface ClusterScheduledTaskHandler {
    val taskType: String

    fun handle(task: ClusterScheduledTask)
}
