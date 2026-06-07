package app.epistola.suite.support.backups.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class BackupsRoutes(
    private val handler: BackupsHandler,
) {
    @Bean
    fun backupsRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/backups".nest {
            GET("", handler::list)
            POST("", handler::backupNow)
            POST("/{snapshotId}/restore", handler::restore)
        }
    }
}
