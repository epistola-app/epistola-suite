package app.epistola.suite.templates.validation

import app.epistola.suite.documents.model.RequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TemplateSchemaCompatibilityPropertiesTest {
    @Test
    fun `defaults are valid`() {
        val props = TemplateSchemaCompatibilityProperties()

        assertThat(props.recentUsageWindowDays).isEqualTo(7)
        assertThat(props.recentUsageSampleLimit).isEqualTo(100)
        assertThat(props.statuses).contains(
            RequestStatus.PENDING,
            RequestStatus.IN_PROGRESS,
            RequestStatus.COMPLETED,
            RequestStatus.FAILED,
        )
    }

    @Test
    fun `recentUsageWindowDays must be positive`() {
        assertThatThrownBy {
            TemplateSchemaCompatibilityProperties(recentUsageWindowDays = 0)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("recentUsageWindowDays")
    }

    @Test
    fun `recentUsageSampleLimit must be positive`() {
        assertThatThrownBy {
            TemplateSchemaCompatibilityProperties(recentUsageSampleLimit = 0)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("recentUsageSampleLimit")
    }

    @Test
    fun `statuses must not be empty`() {
        assertThatThrownBy {
            TemplateSchemaCompatibilityProperties(statuses = emptySet())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("statuses")
    }
}
