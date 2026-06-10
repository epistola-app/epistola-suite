package app.epistola.suite.cluster

interface ClusterScheduledTaskHandler {
    val taskType: String

    fun handle(task: ClusterScheduledTask)
}
